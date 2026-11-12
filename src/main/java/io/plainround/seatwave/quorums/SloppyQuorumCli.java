package io.plainround.seatwave.quorums;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sloppy quorums and hinted handoff. Three of the five home replicas (r1, r2, r3) are down from 3.0
 * s to 4.0 s, so a strict W = 3 cannot be met; two stand-ins (s1, s2) can take hinted writes
 * instead. Three runs: strict; sloppy with hints replayed every 500 ms; sloppy where both stand-ins
 * lose their hints at 4.1 s, before the first replay after the outage.
 */
public final class SloppyQuorumCli {

  private static final long MICROS_PER_SECOND = QuorumSweepCli.MICROS_PER_SECOND;
  private static final QuorumSettings MAJORITIES = new QuorumSettings(5, 3, 3);
  private static final long RUN_MICROS = 8 * MICROS_PER_SECOND;
  private static final long OUTAGE_START_MICROS = 3 * MICROS_PER_SECOND;
  private static final long OUTAGE_END_MICROS = 4 * MICROS_PER_SECOND;

  /** The first hint replay after the outage: replays run at 0.25 s, 0.75 s, and so on. */
  private static final long FIRST_REPLAY_AFTER_MICROS = 4_250_000;

  private static final long HINT_LOSS_MICROS = 4_100_000;

  /**
   * Replicas are counted 100 ms after the last write starts, once every write in flight has landed.
   */
  private static final long SETTLE_MICROS = 100_000;

  private static final List<String> DOWN = List.of("r1", "r2", "r3");
  private static final List<String> STAND_INS = List.of("s1", "s2");
  private static final long[] WINDOW_ENDS_MICROS = {
    OUTAGE_START_MICROS,
    OUTAGE_END_MICROS,
    FIRST_REPLAY_AFTER_MICROS + CartWorkload.HINT_TRANSFER_MICROS,
    RUN_MICROS
  };

  private SloppyQuorumCli() {}

  /** Prints one block per run. */
  public static void main(String[] arguments) {
    System.out.println(
        "SLOPPY QUORUM: N = 5, R = 3, W = 3; 2,000 carts, 8 s; 500 adds/s with a re-read 5 ms after"
            + " each");
    System.out.println(
        "acknowledged add, 500 background reads/s; r1, r2, r3 down 3.0-4.0 s; stand-ins s1, s2;"
            + " hint replay every 500 ms");
    run("strict quorum (no hints)", QuorumCoordinator.WritePolicy.STRICT_NO_HINTS, List.of());
    run(
        "sloppy quorum, hints replayed",
        QuorumCoordinator.WritePolicy.SLOPPY_WITH_HINTS,
        List.of());
    run(
        "sloppy quorum, both stand-ins lose their hints at 4.1 s",
        QuorumCoordinator.WritePolicy.SLOPPY_WITH_HINTS,
        STAND_INS.stream().map(name -> new CartWorkload.HintLoss(name, HINT_LOSS_MICROS)).toList());
  }

  private static void run(
      String label, QuorumCoordinator.WritePolicy writePolicy, List<CartWorkload.HintLoss> losses) {
    List<Replica> homes = new ArrayList<>();
    for (int number = 1; number <= MAJORITIES.replicas(); number++) {
      String name = "r" + number;
      homes.add(
          new Replica(
              name,
              DOWN.contains(name)
                  ? List.of(new Replica.Outage(OUTAGE_START_MICROS, OUTAGE_END_MICROS))
                  : List.of()));
    }
    List<Replica> standIns = STAND_INS.stream().map(name -> new Replica(name, List.of())).toList();
    QuorumCoordinator coordinator =
        new QuorumCoordinator(
            MAJORITIES,
            homes,
            standIns,
            ResponseTimes.seeded(QuorumSweepCli.SEED),
            writePolicy,
            QuorumCoordinator.ReadRepair.OFF);
    CartWorkload.Plan plan =
        new CartWorkload.Plan(
            QuorumSweepCli.CART_COUNT,
            RUN_MICROS,
            QuorumSweepCli.WRITE_INTERVAL_MICROS,
            RUN_MICROS,
            QuorumSweepCli.READ_INTERVAL_MICROS,
            QuorumSweepCli.UNIFORM,
            QuorumSweepCli.FOLLOW_UP_DELAY_MICROS,
            losses,
            QuorumSweepCli.SEED);
    CartWorkload workload = new CartWorkload(coordinator, plan);
    List<Integer> staleByWindow = new ArrayList<>();
    int pendingAtOutageEnd = 0;
    long oldestAgeAtOutageEnd = 0;
    int staleSoFar = 0;
    for (long windowEnd : WINDOW_ENDS_MICROS) {
      workload.runUntil(windowEnd);
      int staleNow = workload.stats().staleReads();
      staleByWindow.add(staleNow - staleSoFar);
      staleSoFar = staleNow;
      if (windowEnd == OUTAGE_END_MICROS) {
        pendingAtOutageEnd = coordinator.hintStore().pendingCount();
        oldestAgeAtOutageEnd = coordinator.hintStore().oldestPendingAgeMicros(windowEnd);
      }
    }
    CartWorkload.Stats stats = workload.stats();
    HintStore hints = coordinator.hintStore();
    System.out.println();
    System.out.println(label);
    System.out.println(
        String.format(
            Locale.ROOT,
            "  writes acknowledged %d, refused %d, of which sloppy (stand-ins counted toward W) %d;"
                + " reads answered %d, unanswered %d (stand-ins hold hints only and serve no"
                + " reads)",
            stats.writesAcknowledged(),
            stats.writesRefused(),
            stats.sloppyWrites(),
            stats.readsAnswered(),
            stats.readsUnanswered()));
    System.out.println(
        String.format(
            Locale.ROOT,
            "  hints stored %d, pending at 4.0 s %d (oldest %.0f ms), peak pending %d, delivered"
                + " %d, lost %d",
            hints.storedCount(),
            pendingAtOutageEnd,
            oldestAgeAtOutageEnd / CartWorkload.MICROS_PER_MILLI,
            hints.peakPendingCount(),
            hints.deliveredCount(),
            hints.lostCount()));
    System.out.println(
        String.format(
            Locale.ROOT,
            "  stale reads: before 3.0 s %d; 3.0-4.0 s %d; 4.0-4.252 s %d; 4.252-8.0 s %d (all"
                + " after sloppy writes: %d)",
            staleByWindow.get(0),
            staleByWindow.get(1),
            staleByWindow.get(2),
            staleByWindow.get(3),
            stats.staleReadsAfterSloppyWrites()));
    System.out.println(
        "  carts whose newest version is on fewer than W = 3 home replicas at 8.1 s: "
            + underReplicated(coordinator.homeReplicas(), RUN_MICROS + SETTLE_MICROS));
  }

  private static int underReplicated(List<Replica> homes, long atMicros) {
    int count = 0;
    for (int cart = 0; cart < QuorumSweepCli.CART_COUNT; cart++) {
      String key = CartWorkload.cartKey(cart);
      long newest =
          homes.stream().mapToLong(replica -> sequenceAt(replica, key, atMicros)).max().orElse(0);
      long holding =
          homes.stream().filter(replica -> sequenceAt(replica, key, atMicros) == newest).count();
      if (holding < MAJORITIES.writeQuorum()) {
        count++;
      }
    }
    return count;
  }

  private static long sequenceAt(Replica replica, String key, long atMicros) {
    return replica.newestAt(key, atMicros).map(Version::sequence).orElse(0L);
  }
}
