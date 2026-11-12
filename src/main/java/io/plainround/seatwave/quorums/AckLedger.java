package io.plainround.seatwave.quorums;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The simulation's record of truth: every acknowledged write and every answered read. It checks
 * each read against the latest write acknowledged before the read began, and it enforces the
 * module's invariant: when R + W > N and that write was acknowledged by home replicas only, a read
 * never returns anything older. A read that breaks it stops the run. Confined to the one simulation
 * thread; not thread-safe.
 */
final class AckLedger {

  /** Whether an acknowledgement came only from home replicas or counted stand-ins. */
  enum Membership {
    /** W home replicas acknowledged: the overlap rule applies. */
    STRICT,
    /** Stand-ins counted toward W: the overlap rule does not apply until the hints are home. */
    SLOPPY
  }

  private record Ack(long sequence, long acknowledgedAtMicros, Membership membership) {}

  private record AnsweredRead(long sequence, long completedAtMicros) {}

  private final boolean quorumsOverlap;
  private final Map<String, List<Ack>> acksByKey = new HashMap<>();
  private final Map<String, List<AnsweredRead>> readsByKey = new HashMap<>();
  private int reads;
  private int staleReads;
  private int staleReadsAfterSloppyWrites;
  private int backwardsReads;

  AckLedger(QuorumSettings settings) {
    this.quorumsOverlap = settings.overlaps();
  }

  void recordAck(String key, long sequence, long acknowledgedAtMicros, Membership membership) {
    acksByKey
        .computeIfAbsent(key, absent -> new ArrayList<>())
        .add(new Ack(sequence, acknowledgedAtMicros, membership));
  }

  /**
   * Checks one answered read. Stale: older than the newest write acknowledged before the read
   * began. Backwards: older than what an earlier read of the key returned, when that read finished
   * before this one began (a history no single copy could give; not linearizable).
   *
   * @throws IllegalStateException if the read is stale although R + W > N and the write it missed
   *     was acknowledged by home replicas only: the overlap rule, which this model must keep, broke
   */
  void recordRead(String key, long returnedSequence, long startMicros, long completedMicros) {
    reads++;
    Ack latest = null;
    for (Ack ack : acksByKey.getOrDefault(key, List.of())) {
      if (ack.acknowledgedAtMicros() < startMicros
          && (latest == null || ack.sequence() > latest.sequence())) {
        latest = ack;
      }
    }
    if (latest != null && returnedSequence < latest.sequence()) {
      staleReads++;
      if (latest.membership() == Membership.SLOPPY) {
        staleReadsAfterSloppyWrites++;
      } else if (quorumsOverlap) {
        throw new IllegalStateException(
            "overlap broken: a read of "
                + key
                + " at "
                + startMicros
                + " us returned sequence "
                + returnedSequence
                + ", older than acknowledged sequence "
                + latest.sequence());
      }
    }
    long newestSeenBefore = 0;
    for (AnsweredRead earlier : readsByKey.getOrDefault(key, List.of())) {
      if (earlier.completedAtMicros() < startMicros) {
        newestSeenBefore = Math.max(newestSeenBefore, earlier.sequence());
      }
    }
    if (returnedSequence < newestSeenBefore) {
      backwardsReads++;
    }
    readsByKey
        .computeIfAbsent(key, absent -> new ArrayList<>())
        .add(new AnsweredRead(returnedSequence, completedMicros));
  }

  int readCount() {
    return reads;
  }

  int staleReadCount() {
    return staleReads;
  }

  int staleReadsAfterSloppyWritesCount() {
    return staleReadsAfterSloppyWrites;
  }

  int backwardsReadCount() {
    return backwardsReads;
  }
}
