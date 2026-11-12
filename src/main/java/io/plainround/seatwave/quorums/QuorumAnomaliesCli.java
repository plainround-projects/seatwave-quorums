package io.plainround.seatwave.quorums;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Three scripted histories, N = 5, R = 3, W = 3, in which R + W > N holds and still no single copy
 * could have produced what clients see: (A) a read sees a write in flight and a later read does
 * not; (B) a write refused to the client has landed on two replicas and shows up later; (C) two
 * acknowledged writes of one cart at the same moment, of which last-writer-wins keeps one. Round
 * trips are scripted per operation and replica, so each history is exact.
 */
public final class QuorumAnomaliesCli {

  private static final QuorumSettings MAJORITIES = new QuorumSettings(5, 3, 3);
  private static final String CART = "cart/0042";

  /** Round trip for any pair the script does not name: a fast-band 4 ms. */
  private static final long DEFAULT_ROUND_TRIP_MICROS = 4_000;

  private static final double MICROS_PER_MILLI = CartWorkload.MICROS_PER_MILLI;

  /** Scripted round trips, in microseconds, per operation id: one entry per replica r1 to r5. */
  private static final Map<Long, long[]> SCRIPT =
      Map.of(
          1L, new long[] {2_000, 4_000, 30_000, 32_000, 34_000},
          2L, new long[] {2_000, 2_200, 2_400, 10_000, 10_000},
          3L, new long[] {10_000, 10_000, 2_000, 2_200, 2_400},
          5L, new long[] {2_000, 3_000, 4_000, 10_000, 10_000},
          6L, new long[] {10_000, 10_000, 2_000, 2_200, 2_400});

  private QuorumAnomaliesCli() {}

  /** Prints the three histories. */
  public static void main(String[] arguments) {
    ResponseTimes scripted =
        (operationId, replicaIndex) ->
            SCRIPT.containsKey(operationId)
                ? SCRIPT.get(operationId)[replicaIndex]
                : DEFAULT_ROUND_TRIP_MICROS;
    System.out.println(
        "QUORUM ANOMALIES: N = 5, R = 3, W = 3 (R + W > N), no sloppy writes; cart " + CART);
    System.out.println();
    System.out.println("A. A read during a write in flight, then a later read (read repair off)");
    QuorumCoordinator inFlight = coordinator(List.of(), scripted, QuorumCoordinator.ReadRepair.OFF);
    inFlight.seedEverywhere(CART, "empty");
    report("write 'ticket A' starts at 0.0 ms", inFlight.write(1, CART, "ticket A", 0), 0);
    report("read 1 starts at 3.0 ms", inFlight.read(2, CART, 3_000), 3_000);
    report(
        "read 2 starts at 6.0 ms, after read 1 has returned", inFlight.read(3, CART, 6_000), 6_000);
    System.out.println(
        "   ledger: stale reads "
            + inFlight.ledger().staleReadCount()
            + ", reads that went backwards "
            + inFlight.ledger().backwardsReadCount());
    System.out.println();
    // The two asynchronous settings the module reports; blocking repair has its own tests.
    for (QuorumCoordinator.ReadRepair readRepair :
        List.of(QuorumCoordinator.ReadRepair.OFF, QuorumCoordinator.ReadRepair.ON)) {
      System.out.println(
          "B. A write refused to the client that landed on two replicas (read repair "
              + readRepair.name().toLowerCase(Locale.ROOT)
              + ")");
      // r3, r4 and r5 fail 1 ms after the write is sent, before it reaches them, and return at 50
      // ms.
      List<Replica.Outage> failsInFlight = List.of(new Replica.Outage(1_000, 50_000));
      QuorumCoordinator partial = coordinator(failsInFlight, scripted, readRepair);
      partial.seedEverywhere(CART, "empty");
      report("write 'ticket B' starts at 0.0 ms", partial.write(4, CART, "ticket B", 0), 0);
      report("read 1 starts at 60.0 ms", partial.read(5, CART, 60_000), 60_000);
      report("read 2 starts at 100.0 ms", partial.read(6, CART, 100_000), 100_000);
      System.out.println(
          "   ledger: stale reads "
              + partial.ledger().staleReadCount()
              + ", reads that went backwards "
              + partial.ledger().backwardsReadCount());
      System.out.println();
    }
    System.out.println(
        "C. Two devices of one fan write the cart at the same moment, each from the empty cart");
    QuorumCoordinator concurrent =
        coordinator(List.of(), scripted, QuorumCoordinator.ReadRepair.OFF);
    concurrent.seedEverywhere(CART, "empty");
    report("phone writes 'ticket C' at 0.0 ms", concurrent.write(7, CART, "ticket C", 0), 0);
    report("laptop writes 'ticket D' at 0.1 ms", concurrent.write(8, CART, "ticket D", 100), 100);
    report("read starts at 50.0 ms", concurrent.read(9, CART, 50_000), 50_000);
    System.out.println(
        "   both writes were acknowledged; last-writer-wins kept one (Module 25 keeps both)");
  }

  private static QuorumCoordinator coordinator(
      List<Replica.Outage> lastThreeOutages,
      ResponseTimes responseTimes,
      QuorumCoordinator.ReadRepair readRepair) {
    List<Replica> replicas = new ArrayList<>();
    for (int number = 1; number <= MAJORITIES.replicas(); number++) {
      replicas.add(new Replica("r" + number, number >= 3 ? lastThreeOutages : List.of()));
    }
    return new QuorumCoordinator(
        MAJORITIES,
        replicas,
        List.of(),
        responseTimes,
        QuorumCoordinator.WritePolicy.STRICT_NO_HINTS,
        readRepair);
  }

  private static void report(
      String step, QuorumCoordinator.WriteOutcome outcome, long startMicros) {
    String result =
        switch (outcome) {
          case QuorumCoordinator.Acknowledged acknowledged ->
              String.format(
                  Locale.ROOT,
                  "acknowledged at %.1f ms (sequence %d)",
                  (startMicros + acknowledged.latencyMicros()) / MICROS_PER_MILLI,
                  acknowledged.sequence());
          case QuorumCoordinator.Refused refused ->
              "REFUSED to the client (fewer than W acknowledgements), but stored on "
                  + refused.landedOn()
                  + " replicas";
        };
    System.out.println("   " + step + ": " + result);
  }

  private static void report(String step, QuorumCoordinator.ReadOutcome outcome, long startMicros) {
    String result =
        switch (outcome) {
          case QuorumCoordinator.Answered answered ->
              String.format(
                  Locale.ROOT,
                  "returns '%s' (sequence %d) at %.1f ms, repairs sent %d",
                  answered.version().value(),
                  answered.version().sequence(),
                  (startMicros + answered.latencyMicros()) / MICROS_PER_MILLI,
                  answered.repairsSent());
          case QuorumCoordinator.Unanswered unanswered -> "unanswered (fewer than R replicas up)";
        };
    System.out.println("   " + step + ": " + result);
  }
}
