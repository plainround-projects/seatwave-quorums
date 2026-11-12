package io.plainround.seatwave.quorums;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A hash tree over one replica's keys. Each key falls in one leaf by its token, the first bits of a
 * hash of the key (its place on the ring, as in Module 18); a leaf's hash covers every (key,
 * sequence) pair in it, and each inner node's hash covers its two children. Two replicas compare
 * roots first and descend only where hashes differ, so a few differing keys cost a few hashes per
 * level instead of a scan of every key. Immutable once built; safe to share between threads.
 */
final class MerkleTree {

  private static final String HASH_ALGORITHM = "SHA-256";
  private static final int TOKEN_BYTES = Long.BYTES;

  private final int leafCount;

  /**
   * Heap layout: node 1 is the root, node i has children 2i and 2i + 1, leaves are leafCount up.
   */
  private final byte[][] nodeHashes;

  private final List<List<Version>> leaves;

  /**
   * What a comparison of two trees found and what it cost.
   *
   * @param hashesCompared tree nodes compared, each one hash sent across
   * @param differingLeaves leaves whose hashes differ
   * @param entriesSent (key, sequence) pairs of the differing leaves sent across to be compared
   * @param newerHere versions this tree's replica holds that the other lacks or holds older
   * @param newerThere versions the other replica holds that this one lacks or holds older
   */
  record Difference(
      int hashesCompared,
      List<Integer> differingLeaves,
      int entriesSent,
      List<Version> newerHere,
      List<Version> newerThere) {

    /** Copies the lists. */
    Difference {
      differingLeaves = List.copyOf(differingLeaves);
      newerHere = List.copyOf(newerHere);
      newerThere = List.copyOf(newerThere);
    }

    /** Keys whose versions must cross, in either direction. */
    int keysTransferred() {
      return newerHere.size() + newerThere.size();
    }
  }

  private MerkleTree(int leafCount, byte[][] nodeHashes, List<List<Version>> leaves) {
    this.leafCount = leafCount;
    this.nodeHashes = nodeHashes;
    this.leaves = leaves;
  }

  /**
   * Builds the tree over {@code entries} (one newest version per key) with {@code leafCount}
   * leaves.
   *
   * @throws IllegalArgumentException if {@code leafCount} is not a power of two of at least 2
   */
  static MerkleTree build(Collection<Version> entries, int leafCount) {
    if (leafCount < 2 || Integer.bitCount(leafCount) != 1) {
      throw new IllegalArgumentException(
          "leafCount must be a power of two, at least 2, was " + leafCount);
    }
    List<List<Version>> leaves = new ArrayList<>();
    for (int leaf = 0; leaf < leafCount; leaf++) {
      leaves.add(new ArrayList<>());
    }
    entries.forEach(version -> leaves.get(leafOf(version.key(), leafCount)).add(version));
    byte[][] nodeHashes = new byte[2 * leafCount][];
    for (int leaf = 0; leaf < leafCount; leaf++) {
      // Sort inside the leaf so that two replicas with the same entries hash the same bytes.
      leaves.get(leaf).sort((left, right) -> left.key().compareTo(right.key()));
      nodeHashes[leafCount + leaf] = hashLeaf(leaves.get(leaf));
    }
    for (int node = leafCount - 1; node >= 1; node--) {
      nodeHashes[node] = hashPair(nodeHashes[2 * node], nodeHashes[2 * node + 1]);
    }
    return new MerkleTree(leafCount, nodeHashes, leaves.stream().map(List::copyOf).toList());
  }

  /** The leaf of {@code key}: the top bits of its token, so a leaf is a contiguous token range. */
  static int leafOf(String key, int leafCount) {
    byte[] digest = newDigest().digest(key.getBytes(StandardCharsets.UTF_8));
    long token = ByteBuffer.wrap(digest, 0, TOKEN_BYTES).getLong();
    int leafBits = Integer.numberOfTrailingZeros(leafCount);
    return (int) (token >>> (Long.SIZE - leafBits));
  }

  /**
   * Compares this tree with {@code other}, top down, and lists the versions each side must send.
   *
   * @throws IllegalArgumentException if the trees have different leaf counts
   */
  Difference compareWith(MerkleTree other) {
    if (other.leafCount != leafCount) {
      throw new IllegalArgumentException("trees must have the same leaf count to be compared");
    }
    int hashesCompared = 0;
    List<Integer> differingLeaves = new ArrayList<>();
    Deque<Integer> toCompare = new ArrayDeque<>(List.of(1));
    while (!toCompare.isEmpty()) {
      int node = toCompare.poll();
      hashesCompared++;
      if (Arrays.equals(nodeHashes[node], other.nodeHashes[node])) {
        continue;
      }
      if (node >= leafCount) {
        differingLeaves.add(node - leafCount);
      } else {
        toCompare.add(2 * node);
        toCompare.add(2 * node + 1);
      }
    }
    int entriesSent = 0;
    List<Version> newerHere = new ArrayList<>();
    List<Version> newerThere = new ArrayList<>();
    for (int leaf : differingLeaves) {
      entriesSent += leaves.get(leaf).size();
      sortNewer(leaves.get(leaf), other.leaves.get(leaf), newerHere, newerThere);
    }
    return new Difference(hashesCompared, differingLeaves, entriesSent, newerHere, newerThere);
  }

  int leafCount() {
    return leafCount;
  }

  private static void sortNewer(
      List<Version> here, List<Version> there, List<Version> newerHere, List<Version> newerThere) {
    Map<String, Version> thereByKey = new HashMap<>();
    there.forEach(version -> thereByKey.put(version.key(), version));
    for (Version mine : here) {
      Version theirs = thereByKey.remove(mine.key());
      if (theirs == null || mine.isNewerThan(theirs)) {
        newerHere.add(mine);
      } else if (theirs.isNewerThan(mine)) {
        newerThere.add(theirs);
      }
    }
    newerThere.addAll(thereByKey.values());
  }

  private static byte[] hashLeaf(List<Version> entries) {
    MessageDigest digest = newDigest();
    for (Version version : entries) {
      digest.update(version.key().getBytes(StandardCharsets.UTF_8));
      // A separator and a fixed-width sequence keep ("a", 12) and ("a1", 2) from hashing alike.
      digest.update((byte) 0);
      digest.update(ByteBuffer.allocate(Long.BYTES).putLong(version.sequence()).array());
    }
    return digest.digest();
  }

  private static byte[] hashPair(byte[] left, byte[] right) {
    MessageDigest digest = newDigest();
    digest.update(left);
    digest.update(right);
    return digest.digest();
  }

  private static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance(HASH_ALGORITHM);
    } catch (NoSuchAlgorithmException missing) {
      // Every Java platform must provide SHA-256, so this is a broken runtime, not an input error.
      throw new IllegalStateException(HASH_ALGORITHM + " is not available", missing);
    }
  }
}
