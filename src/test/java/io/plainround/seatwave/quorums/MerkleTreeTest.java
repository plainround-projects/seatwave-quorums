package io.plainround.seatwave.quorums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MerkleTreeTest {

  private static final int LEAVES = 16;
  private static final int KEYS = 200;
  private static final long SHUFFLE_SEED = 7;

  private static List<Version> versions(int count) {
    return IntStream.range(0, count)
        .mapToObj(number -> new Version("cart/%04d".formatted(number), "ticket", number + 1L))
        .toList();
  }

  @Test
  void rootsDoNotDependOnTheOrderEntriesAreInserted() {
    List<Version> inOrder = versions(KEYS);
    List<Version> shuffled = new ArrayList<>(inOrder);
    Collections.shuffle(shuffled, new Random(SHUFFLE_SEED));
    List<Version> reversed = new ArrayList<>(inOrder.reversed());

    MerkleTree fromOrder = MerkleTree.build(inOrder, LEAVES);

    for (List<Version> otherOrder : List.of(shuffled, reversed)) {
      MerkleTree.Difference difference =
          fromOrder.compareWith(MerkleTree.build(otherOrder, LEAVES));

      assertThat(difference.hashesCompared()).isEqualTo(1);
      assertThat(difference.differingLeaves()).isEmpty();
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {-4, 0, 1, 3, 6, 1_000})
  void rejectsLeafCountsThatAreNotAPowerOfTwoOfAtLeastTwo(int leafCount) {
    List<Version> entries = versions(1);

    assertThatThrownBy(() -> MerkleTree.build(entries, leafCount))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("power of two")
        .hasMessageContaining(String.valueOf(leafCount));
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 4, 1_024})
  void acceptsPowersOfTwo(int leafCount) {
    assertThat(MerkleTree.build(versions(5), leafCount).leafCount()).isEqualTo(leafCount);
  }

  @Test
  void equalTreesCostOneHashAndSendNothing() {
    MerkleTree left = MerkleTree.build(versions(KEYS), LEAVES);
    MerkleTree right = MerkleTree.build(versions(KEYS), LEAVES);

    MerkleTree.Difference difference = left.compareWith(right);

    assertThat(difference.hashesCompared()).isEqualTo(1);
    assertThat(difference.differingLeaves()).isEmpty();
    assertThat(difference.entriesSent()).isZero();
    assertThat(difference.keysTransferred()).isZero();
  }

  @Test
  void emptyTreesAreEqual() {
    MerkleTree.Difference difference =
        MerkleTree.build(List.of(), LEAVES).compareWith(MerkleTree.build(List.of(), LEAVES));

    assertThat(difference.differingLeaves()).isEmpty();
  }

  @Test
  void oneDifferingKeyCostsOneRootToLeafPathAndNamesTheNewerSide() {
    List<Version> older = versions(KEYS);
    List<Version> newer = new ArrayList<>(older);
    Version changed = new Version("cart/0042", "two tickets", 1_000);
    newer.set(42, changed);

    MerkleTree.Difference difference =
        MerkleTree.build(older, LEAVES).compareWith(MerkleTree.build(newer, LEAVES));

    int levels = Integer.numberOfTrailingZeros(LEAVES);
    // The root, then both children of each node on the path down to the one differing leaf.
    assertThat(difference.hashesCompared()).isEqualTo(1 + 2 * levels);
    assertThat(difference.differingLeaves())
        .containsExactly(MerkleTree.leafOf("cart/0042", LEAVES));
    assertThat(difference.newerHere()).isEmpty();
    assertThat(difference.newerThere()).containsExactly(changed);
  }

  @Test
  void aKeyOnlyOneSideHoldsIsNewerOnThatSide() {
    List<Version> shorter = versions(KEYS - 1);
    List<Version> longer = versions(KEYS);

    MerkleTree.Difference difference =
        MerkleTree.build(longer, LEAVES).compareWith(MerkleTree.build(shorter, LEAVES));

    assertThat(difference.newerHere()).containsExactly(longer.getLast());
    assertThat(difference.newerThere()).isEmpty();
    assertThat(difference.keysTransferred()).isEqualTo(1);
    assertThat(difference.entriesSent()).isPositive();
  }

  @Test
  void rejectsComparingTreesWithDifferentLeafCounts() {
    MerkleTree small = MerkleTree.build(versions(3), 2);
    MerkleTree large = MerkleTree.build(versions(3), 4);

    assertThatThrownBy(() -> small.compareWith(large))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("same leaf count");
  }

  @Test
  void aKeyAlwaysFallsInTheSameLeafInsideTheRange() {
    for (Version version : versions(KEYS)) {
      int leaf = MerkleTree.leafOf(version.key(), LEAVES);

      assertThat(leaf).isBetween(0, LEAVES - 1);
      assertThat(MerkleTree.leafOf(version.key(), LEAVES)).isEqualTo(leaf);
    }
  }

  @Test
  void keysSpreadOverMoreThanOneLeaf() {
    long distinctLeaves =
        versions(KEYS).stream().map(v -> MerkleTree.leafOf(v.key(), LEAVES)).distinct().count();

    assertThat(distinctLeaves).isGreaterThan(LEAVES / 2);
  }
}
