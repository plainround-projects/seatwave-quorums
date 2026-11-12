package io.plainround.seatwave.quorums;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs the same cart workload and failure schedule under eleven (R, W) pairs with N = 5, strict
 * quorums, no hints and no read repair, and prints stale reads, refused operations and latency for
 * each. The only program besides the other {@code *Cli} classes that prints.
 */
public final class QuorumSweepCli {

  static final int REPLICAS = 5;

  /** 2,000 carts: one store shard's share of the fans with an open cart at the peak (assumed). */
  static final int CART_COUNT = 2_000;

  static final long MICROS_PER_SECOND = 1_000_000;
  static final long RUN_MICROS = 120 * MICROS_PER_SECOND;

  /** One "add to cart" every 2 ms: 500 writes a second on this shard (assumed). */
  static final long WRITE_INTERVAL_MICROS = 2_000;

  /** One background cart read every 2 ms: 500 a second (assumed). */
  static final long READ_INTERVAL_MICROS = 2_000;

  /** The page re-reads the cart 5 ms after the add returns (assumed). */
  static final long FOLLOW_UP_DELAY_MICROS = 5_000;

  static final double UNIFORM = 1.0;
  static final long SEED = 22;

  /** Draws used to report the replica round-trip distribution the model was given. */
  private static final int DISTRIBUTION_DRAWS = 1_000_000;

  private static final double P50 = 50;
  private static final double P99 = 99;

  /**
   * The failure schedule, the same in every run: r5 down 1.2 s (1 percent of the 120 s run, the
   * brief's "one replica down 1 percent of the time"); then two replicas down together for 0.5 s;
   * then three down together for 0.5 s, so that even a majority is missing.
   */
  static final Map<String, List<Replica.Outage>> OUTAGES =
      Map.of(
          "r1", List.of(new Replica.Outage(90_000_000, 90_500_000)),
          "r2",
              List.of(
                  new Replica.Outage(60_000_000, 60_500_000),
                  new Replica.Outage(90_000_000, 90_500_000)),
          "r3", List.of(new Replica.Outage(90_000_000, 90_500_000)),
          "r4", List.of(new Replica.Outage(60_000_000, 60_500_000)),
          "r5", List.of(new Replica.Outage(20_000_000, 21_200_000)));

  /** The (R, W) pairs tried, in the order printed. */
  private static final int[][] READ_WRITE_PAIRS = {
    {1, 1}, {2, 2}, {1, 3}, {2, 3}, {3, 2}, {3, 3}, {2, 4}, {4, 2}, {1, 5}, {5, 1}, {5, 5}
  };

  private QuorumSweepCli() {}

  /** Prints one line per (R, W) pair. */
  public static void main(String[] arguments) {
    System.out.println(
        "SEATWAVE QUORUM SWEEP: N = 5 cart replicas r1 to r5, 2,000 carts, 120 s of virtual time");
    System.out.println(
        "traffic: 500 adds/s, a re-read 5 ms after each acknowledged add, 500 background reads/s");
    System.out.println(
        "failures: r5 down 20.0-21.2 s; r2 and r4 down 60.0-60.5 s; r1, r2 and r3 down 90.0-90.5"
            + " s");
    System.out.println(
        "strict quorums, no hints, no read repair; replica round trip " + distribution());
    System.out.println();
    System.out.println(
        "R W  R+W>N  writes ok refused partial   reads ok refused  stale  stale%  backwards"
            + "  write p50  p99  read p50  p99  down tolerated w/r");
    for (int[] pair : READ_WRITE_PAIRS) {
      QuorumSettings settings = new QuorumSettings(REPLICAS, pair[0], pair[1]);
      CartWorkload.Stats stats = run(settings);
      System.out.println(row(settings, stats));
    }
  }

  /** Runs the sweep's workload under {@code settings}, strict and without repair. */
  static CartWorkload.Stats run(QuorumSettings settings) {
    QuorumCoordinator coordinator =
        new QuorumCoordinator(
            settings,
            homeReplicas(),
            List.of(),
            ResponseTimes.seeded(SEED),
            QuorumCoordinator.WritePolicy.STRICT_NO_HINTS,
            QuorumCoordinator.ReadRepair.OFF);
    CartWorkload workload = new CartWorkload(coordinator, plan(RUN_MICROS));
    workload.runUntil(RUN_MICROS);
    return workload.stats();
  }

  static CartWorkload.Plan plan(long durationMicros) {
    return new CartWorkload.Plan(
        CART_COUNT,
        durationMicros,
        WRITE_INTERVAL_MICROS,
        durationMicros,
        READ_INTERVAL_MICROS,
        UNIFORM,
        FOLLOW_UP_DELAY_MICROS,
        List.of(),
        SEED);
  }

  static List<Replica> homeReplicas() {
    List<Replica> replicas = new ArrayList<>();
    for (int number = 1; number <= REPLICAS; number++) {
      String name = "r" + number;
      replicas.add(new Replica(name, OUTAGES.getOrDefault(name, List.of())));
    }
    return replicas;
  }

  private static String row(QuorumSettings settings, CartWorkload.Stats stats) {
    double staleShare = 100.0 * stats.staleReads() / Math.max(1, stats.readsAnswered());
    return String.format(
        Locale.ROOT,
        "%d %d  %-5s  %9d %7d %7d  %9d %7d %6d  %6.3f  %9d  %9s %4s  %8s %4s  %d / %d",
        settings.readQuorum(),
        settings.writeQuorum(),
        settings.overlaps() ? "yes" : "no",
        stats.writesAcknowledged(),
        stats.writesRefused(),
        stats.partialWrites(),
        stats.readsAnswered(),
        stats.readsUnanswered(),
        stats.staleReads(),
        staleShare,
        stats.backwardsReads(),
        CartWorkload.Stats.percentileMillis(stats.writeLatenciesMicros(), P50),
        CartWorkload.Stats.percentileMillis(stats.writeLatenciesMicros(), P99),
        CartWorkload.Stats.percentileMillis(stats.readLatenciesMicros(), P50),
        CartWorkload.Stats.percentileMillis(stats.readLatenciesMicros(), P99),
        settings.writeFailuresTolerated(),
        settings.readFailuresTolerated());
  }

  private static String distribution() {
    ResponseTimes responseTimes = ResponseTimes.seeded(SEED);
    List<Long> draws = new ArrayList<>();
    for (long operationId = 0; operationId < DISTRIBUTION_DRAWS; operationId++) {
      draws.add(responseTimes.roundTripMicros(operationId, 0));
    }
    draws.sort(Long::compare);
    return "p50 "
        + CartWorkload.Stats.percentileMillis(draws, P50)
        + " ms, p99 "
        + CartWorkload.Stats.percentileMillis(draws, P99)
        + " ms (1,000,000 draws of the model's input)";
  }
}
