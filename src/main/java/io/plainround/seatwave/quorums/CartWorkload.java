package io.plainround.seatwave.quorums;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.SplittableRandom;

/**
 * Drives a {@link QuorumCoordinator} with SeatWave cart traffic on a virtual clock: "add to cart"
 * writes at a fixed rate, a re-read of the cart a few milliseconds after each acknowledged write,
 * background cart reads, and periodic hint replay. The same plan and seed give the same operations,
 * in the same order, for every R and W. Confined to one thread; not thread-safe.
 */
final class CartWorkload {

  /** Each stand-in drops its hints at a set time (it restarts with an empty disk). */
  record HintLoss(String standInName, long atMicros) {

    /** Rejects a missing name and a negative time. */
    HintLoss {
      Objects.requireNonNull(standInName, "standInName");
      if (atMicros < 0) {
        throw new IllegalArgumentException("a hint loss cannot happen before time 0");
      }
    }
  }

  /**
   * What to run.
   *
   * @param cartCount carts in the store, keys {@code cart/0000} upward
   * @param writeEndMicros writes stop here
   * @param writeIntervalMicros one write per interval
   * @param readEndMicros background reads stop here
   * @param readIntervalMicros one background read per interval
   * @param readSkew 1 for uniform background reads; above 1, reads favor low-numbered (hot) carts
   * @param followUpDelayMicros the re-read after each acknowledged write, or a negative value for
   *     none
   * @param hintLosses stand-ins that lose their hints, and when
   * @param seed fixes which carts are written and read
   */
  record Plan(
      int cartCount,
      long writeEndMicros,
      long writeIntervalMicros,
      long readEndMicros,
      long readIntervalMicros,
      double readSkew,
      long followUpDelayMicros,
      List<HintLoss> hintLosses,
      long seed) {

    /** Rejects an empty store, intervals below 1 microsecond and a skew below 1. */
    Plan {
      if (cartCount < 1 || writeIntervalMicros < 1 || readIntervalMicros < 1 || readSkew < 1) {
        throw new IllegalArgumentException("carts, intervals and skew must be at least 1");
      }
      hintLosses = List.copyOf(hintLosses);
    }
  }

  /** Counts and latencies of one run so far. */
  record Stats(
      int writesAcknowledged,
      int writesRefused,
      int partialWrites,
      int sloppyWrites,
      int readsAnswered,
      int readsUnanswered,
      int staleReads,
      int staleReadsAfterSloppyWrites,
      int backwardsReads,
      int repairsSent,
      List<Long> writeLatenciesMicros,
      List<Long> readLatenciesMicros) {

    /** Copies the latency lists, sorted, so percentiles can be read. */
    Stats {
      writeLatenciesMicros = writeLatenciesMicros.stream().sorted().toList();
      readLatenciesMicros = readLatenciesMicros.stream().sorted().toList();
    }

    /** The value at {@code percentile} (0 to 100) of sorted {@code latencies}, in milliseconds. */
    static String percentileMillis(List<Long> latencies, double percentile) {
      if (latencies.isEmpty()) {
        return "-";
      }
      int rank = (int) Math.ceil(percentile / PERCENT * latencies.size());
      return String.format(
          Locale.ROOT, "%.1f", latencies.get(Math.max(rank, 1) - 1) / MICROS_PER_MILLI);
    }
  }

  private enum Kind {
    WRITE,
    READ,
    HINT_REPLAY,
    HINT_LOSS
  }

  private record Event(long atMicros, long order, Kind kind, long operationId, String detail) {}

  static final double MICROS_PER_MILLI = 1_000.0;
  private static final double PERCENT = 100.0;

  /** Hints are replayed every 500 ms (assumed), so a returning replica waits at most that long. */
  static final long HINT_REPLAY_INTERVAL_MICROS = 500_000;

  /** Moving one hinted version to its home replica takes 2 ms (assumed: a fast-band round trip). */
  static final long HINT_TRANSFER_MICROS = 2_000;

  /** Operation ids of follow-up reads sit above every planned id, so their round trips differ. */
  private static final long FOLLOW_UP_ID_OFFSET = 1_000_000_000L;

  private final QuorumCoordinator coordinator;
  private final Plan plan;
  private final PriorityQueue<Event> events =
      new PriorityQueue<>(
          Comparator.comparingLong(Event::atMicros).thenComparingLong(Event::order));
  private final List<Long> writeLatencies = new ArrayList<>();
  private final List<Long> readLatencies = new ArrayList<>();
  private long eventOrder;
  private int writesAcknowledged;
  private int writesRefused;
  private int partialWrites;
  private int sloppyWrites;
  private int readsAnswered;
  private int readsUnanswered;
  private int repairsSent;

  /** Seeds every cart on every home replica and schedules the whole plan. */
  CartWorkload(QuorumCoordinator coordinator, Plan plan) {
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    this.plan = Objects.requireNonNull(plan, "plan");
    for (int cart = 0; cart < plan.cartCount(); cart++) {
      coordinator.seedEverywhere(cartKey(cart), "empty");
    }
    SplittableRandom random = new SplittableRandom(plan.seed());
    long operationId = 0;
    for (long at = plan.writeIntervalMicros();
        at < plan.writeEndMicros();
        at += plan.writeIntervalMicros()) {
      schedule(at, Kind.WRITE, operationId++, cartKey(random.nextInt(plan.cartCount())));
    }
    for (long at = plan.readIntervalMicros();
        at < plan.readEndMicros();
        at += plan.readIntervalMicros()) {
      int cart = (int) (plan.cartCount() * Math.pow(random.nextDouble(), plan.readSkew()));
      schedule(at, Kind.READ, operationId++, cartKey(cart));
    }
    long lastMicros = Math.max(plan.writeEndMicros(), plan.readEndMicros());
    for (long at = HINT_REPLAY_INTERVAL_MICROS / 2;
        at < lastMicros;
        at += HINT_REPLAY_INTERVAL_MICROS) {
      schedule(at, Kind.HINT_REPLAY, 0, "");
    }
    plan.hintLosses()
        .forEach(loss -> schedule(loss.atMicros(), Kind.HINT_LOSS, 0, loss.standInName()));
  }

  static String cartKey(int cart) {
    return String.format(Locale.ROOT, "cart/%04d", cart);
  }

  /** Runs every event before {@code endMicros}; later calls continue from there. */
  void runUntil(long endMicros) {
    while (!events.isEmpty() && events.peek().atMicros() < endMicros) {
      Event event = events.poll();
      switch (event.kind()) {
        case WRITE -> write(event);
        case READ -> read(event);
        case HINT_REPLAY -> coordinator.replayHints(event.atMicros(), HINT_TRANSFER_MICROS);
        case HINT_LOSS -> coordinator.hintStore().loseHintsOn(event.detail());
      }
    }
  }

  Stats stats() {
    AckLedger ledger = coordinator.ledger();
    return new Stats(
        writesAcknowledged,
        writesRefused,
        partialWrites,
        sloppyWrites,
        readsAnswered,
        readsUnanswered,
        ledger.staleReadCount(),
        ledger.staleReadsAfterSloppyWritesCount(),
        ledger.backwardsReadCount(),
        repairsSent,
        writeLatencies,
        readLatencies);
  }

  private void write(Event event) {
    String value = "add#" + event.operationId();
    switch (coordinator.write(event.operationId(), event.detail(), value, event.atMicros())) {
      case QuorumCoordinator.Acknowledged acknowledged -> {
        writesAcknowledged++;
        writeLatencies.add(acknowledged.latencyMicros());
        sloppyWrites += acknowledged.standInAcks() > 0 ? 1 : 0;
        if (plan.followUpDelayMicros() >= 0) {
          // The fan's page re-reads the cart once the add has returned.
          long readAt =
              event.atMicros() + acknowledged.latencyMicros() + plan.followUpDelayMicros();
          schedule(readAt, Kind.READ, FOLLOW_UP_ID_OFFSET + event.operationId(), event.detail());
        }
      }
      case QuorumCoordinator.Refused refused -> {
        writesRefused++;
        partialWrites += refused.landedOn() > 0 ? 1 : 0;
      }
    }
  }

  private void read(Event event) {
    switch (coordinator.read(event.operationId(), event.detail(), event.atMicros())) {
      case QuorumCoordinator.Answered answered -> {
        readsAnswered++;
        readLatencies.add(answered.latencyMicros());
        repairsSent += answered.repairsSent();
      }
      case QuorumCoordinator.Unanswered unanswered -> readsUnanswered++;
    }
  }

  private void schedule(long atMicros, Kind kind, long operationId, String detail) {
    events.add(new Event(atMicros, eventOrder++, kind, operationId, detail));
  }
}
