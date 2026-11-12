package io.plainround.seatwave.quorums;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * How replicas heal. Part 1: r5 misses 5 s of cart writes, then comes back; reads run with and
 * without read repair, and the stale keys left on r5 are counted over time. Part 2: a Merkle-tree
 * comparison of r5 with r1 finds and copies what reads never touched. Part 3: the cost of a Merkle
 * comparison against a full comparison for 100,000 keys, by how many keys differ and by leaf count.
 */
public final class ReplicaHealingCli {

  private static final long MICROS_PER_SECOND = QuorumSweepCli.MICROS_PER_SECOND;
  private static final QuorumSettings MAJORITIES = new QuorumSettings(5, 3, 3);

  /** Writes run for the first 10 s; r5 is down for the second half of them. */
  private static final long WRITE_END_MICROS = 10 * MICROS_PER_SECOND;

  private static final Replica.Outage R5_OUTAGE =
      new Replica.Outage(5 * MICROS_PER_SECOND, WRITE_END_MICROS);

  /** Reads go on alone until 40 s, at 200 a second (assumed: a quiet period after the burst). */
  private static final long READ_END_MICROS = 40 * MICROS_PER_SECOND;

  private static final long READ_INTERVAL_MICROS = 5_000;

  /** Skew 3: a read picks cart floor(2,000 * u^3) for uniform u, so a few carts are read often. */
  private static final double READ_SKEW = 3.0;

  private static final long[] CHECKPOINTS_SECONDS = {10, 11, 12, 15, 20, 30, 40};

  /** 64 leaves for 2,000 keys: about 31 keys per leaf. */
  private static final int SMALL_TREE_LEAVES = 64;

  private static final int LARGE_KEY_COUNT = 100_000;
  private static final int[] DIFFERING_KEY_COUNTS = {1, 10, 100, 1_000, 10_000};
  private static final int DEFAULT_LEAVES = 1_024;
  private static final int[] LEAF_COUNTS = {64, 1_024, 16_384};
  private static final int LEAF_STUDY_DIFFERENCES = 100;

  /**
   * Bytes on the wire, for the estimate column: a SHA-256 hash, and an entry (11-byte key, 8-byte
   * sequence).
   */
  private static final int HASH_BYTES = 32;

  private static final int ENTRY_BYTES = 19;
  private static final long LARGE_STUDY_SEED = 2_207;

  private ReplicaHealingCli() {}

  /** Prints the three parts. */
  public static void main(String[] arguments) {
    System.out.println(
        String.format(
            Locale.ROOT,
            "PART 1. READ REPAIR: N = %d, R = %d, W = %d; 500 adds/s for 10 s on 2,000 carts; r5"
                + " down 5-10 s;",
            MAJORITIES.replicas(),
            MAJORITIES.readQuorum(),
            MAJORITIES.writeQuorum()));
    System.out.println("then only reads, 200/s, skewed toward hot carts, until 40 s");
    System.out.println(
        "time   stale keys on r5 (repair off)   stale keys on r5 (repair on)   repairs sent so far"
            + " (on)");
    QuorumCoordinator withoutRepair = coordinator(QuorumCoordinator.ReadRepair.OFF);
    QuorumCoordinator withRepair = coordinator(QuorumCoordinator.ReadRepair.ON);
    CartWorkload workloadWithout = new CartWorkload(withoutRepair, plan());
    CartWorkload workloadWith = new CartWorkload(withRepair, plan());
    for (long seconds : CHECKPOINTS_SECONDS) {
      long atMicros = seconds * MICROS_PER_SECOND;
      workloadWithout.runUntil(atMicros);
      workloadWith.runUntil(atMicros);
      System.out.println(
          String.format(
              Locale.ROOT,
              "%4d s  %30d  %29d  %25d",
              seconds,
              staleKeys(withoutRepair, atMicros),
              staleKeys(withRepair, atMicros),
              workloadWith.stats().repairsSent()));
    }
    System.out.println(
        "stale reads seen by fans: repair off "
            + workloadWithout.stats().staleReads()
            + ", repair on "
            + workloadWith.stats().staleReads()
            + (MAJORITIES.overlaps()
                ? " (R + W > N hides r5 either way)"
                : " (R + W <= N: fans see what r5 lacks)"));
    System.out.println();
    System.out.println(
        "PART 2. ANTI-ENTROPY AT 40 s: r5 against r1, Merkle tree of 64 leaves over 2,000 carts");
    antiEntropy("repair off", withoutRepair);
    antiEntropy("repair on", withRepair);
    System.out.println();
    System.out.println(
        "PART 3. COST AGAINST A FULL COMPARISON: 100,000 keys; one replica lacks the newest version"
            + " of d keys");
    System.out.println(
        "(bytes estimated at 32 per hash and 19 per entry; a full comparison sends all 100,000"
            + " entries, 1,900,000 bytes)");
    System.out.println(
        "    d  leaves  hashes compared  leaves differing  entries sent  keys transferred  bytes"
            + " sent  share of full");
    for (int differing : DIFFERING_KEY_COUNTS) {
      System.out.println(largeStudyRow(differing, DEFAULT_LEAVES));
    }
    for (int leaves : LEAF_COUNTS) {
      if (leaves != DEFAULT_LEAVES) {
        System.out.println(largeStudyRow(LEAF_STUDY_DIFFERENCES, leaves));
      }
    }
  }

  private static QuorumCoordinator coordinator(QuorumCoordinator.ReadRepair readRepair) {
    List<Replica> replicas = new ArrayList<>();
    for (int number = 1; number <= MAJORITIES.replicas(); number++) {
      String name = "r" + number;
      replicas.add(new Replica(name, name.equals("r5") ? List.of(R5_OUTAGE) : List.of()));
    }
    return new QuorumCoordinator(
        MAJORITIES,
        replicas,
        List.of(),
        ResponseTimes.seeded(QuorumSweepCli.SEED),
        QuorumCoordinator.WritePolicy.STRICT_NO_HINTS,
        readRepair);
  }

  private static CartWorkload.Plan plan() {
    return new CartWorkload.Plan(
        QuorumSweepCli.CART_COUNT,
        WRITE_END_MICROS,
        QuorumSweepCli.WRITE_INTERVAL_MICROS,
        READ_END_MICROS,
        READ_INTERVAL_MICROS,
        READ_SKEW,
        QuorumSweepCli.FOLLOW_UP_DELAY_MICROS,
        List.of(),
        QuorumSweepCli.SEED);
  }

  /** Keys whose newest version on r5 is older than the newest on any home replica. */
  private static int staleKeys(QuorumCoordinator coordinator, long atMicros) {
    List<Replica> replicas = coordinator.homeReplicas();
    Map<String, Version> lagging = replicas.getLast().snapshotAt(atMicros);
    int stale = 0;
    for (int cart = 0; cart < QuorumSweepCli.CART_COUNT; cart++) {
      String key = CartWorkload.cartKey(cart);
      long newest = 0;
      for (Replica replica : replicas) {
        newest =
            Math.max(newest, replica.newestAt(key, atMicros).map(Version::sequence).orElse(0L));
      }
      if (lagging.get(key).sequence() < newest) {
        stale++;
      }
    }
    return stale;
  }

  private static void antiEntropy(String label, QuorumCoordinator coordinator) {
    long atMicros = READ_END_MICROS;
    Replica healthy = coordinator.homeReplicas().getFirst();
    Replica lagging = coordinator.homeReplicas().getLast();
    MerkleTree healthyTree =
        MerkleTree.build(healthy.snapshotAt(atMicros).values(), SMALL_TREE_LEAVES);
    MerkleTree laggingTree =
        MerkleTree.build(lagging.snapshotAt(atMicros).values(), SMALL_TREE_LEAVES);
    MerkleTree.Difference difference = healthyTree.compareWith(laggingTree);
    // Copy each newer version to the side that lacks it; the transfer lands one hint-transfer
    // later.
    long landsAtMicros = atMicros + CartWorkload.HINT_TRANSFER_MICROS;
    difference.newerHere().forEach(version -> lagging.receive(version, landsAtMicros));
    difference.newerThere().forEach(version -> healthy.receive(version, landsAtMicros));
    System.out.println(
        String.format(
            Locale.ROOT,
            "%-10s: %d stale keys before; %d hashes compared, %d of 64 leaves differ, %d entries"
                + " sent, %d keys transferred (a full comparison sends 2,000 entries); stale keys"
                + " after: %d",
            label,
            difference.keysTransferred(),
            difference.hashesCompared(),
            difference.differingLeaves().size(),
            difference.entriesSent(),
            difference.keysTransferred(),
            staleKeys(coordinator, landsAtMicros)));
  }

  private static String largeStudyRow(int differing, int leafCount) {
    List<Version> complete = new ArrayList<>();
    List<Version> missing = new ArrayList<>();
    Set<Integer> missedKeys = pickDistinct(differing);
    for (int index = 0; index < LARGE_KEY_COUNT; index++) {
      String key = String.format(Locale.ROOT, "cart/%06d", index);
      // Every key was written twice; the lagging replica missed the second write of the picked
      // keys.
      complete.add(new Version(key, "second", 2));
      missing.add(
          missedKeys.contains(index)
              ? new Version(key, "first", 1)
              : new Version(key, "second", 2));
    }
    MerkleTree.Difference difference =
        MerkleTree.build(complete, leafCount).compareWith(MerkleTree.build(missing, leafCount));
    long bytesSent =
        (long) difference.hashesCompared() * HASH_BYTES
            + (long) difference.entriesSent() * ENTRY_BYTES;
    long fullBytes = (long) LARGE_KEY_COUNT * ENTRY_BYTES;
    return String.format(
        Locale.ROOT,
        "%6d  %6d  %15d  %16d  %12d  %16d  %10d  %12.2f%%",
        differing,
        leafCount,
        difference.hashesCompared(),
        difference.differingLeaves().size(),
        difference.entriesSent(),
        difference.keysTransferred(),
        bytesSent,
        100.0 * bytesSent / fullBytes);
  }

  private static Set<Integer> pickDistinct(int count) {
    SplittableRandom random = new SplittableRandom(LARGE_STUDY_SEED);
    Set<Integer> picked = new HashSet<>();
    while (picked.size() < count) {
      picked.add(random.nextInt(LARGE_KEY_COUNT));
    }
    return picked;
  }
}
