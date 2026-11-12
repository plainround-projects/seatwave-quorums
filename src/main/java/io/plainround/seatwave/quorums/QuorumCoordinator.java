package io.plainround.seatwave.quorums;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The coordinator of a leaderless cart store: the one way in for reads and writes. It sends every
 * write to all N home replicas it believes are up and acknowledges after W replies; it sends every
 * read to all N, answers after R replies with the newest version among them, and can repair the
 * replicas that answered with older ones. Every outcome passes through the {@link AckLedger}, so no
 * read escapes the overlap check. Confined to the one simulation thread; not thread-safe.
 */
final class QuorumCoordinator {

  /** What a write may count toward W, and whether down replicas get hints. */
  enum WritePolicy {
    /** Only home replicas count; a down replica simply misses the write. */
    STRICT_NO_HINTS,
    /** Only home replicas count; a stand-in keeps a hint for each down home replica. */
    STRICT_WITH_HINTS,
    /** Stand-ins holding hints count toward W when home replicas are down (a sloppy quorum). */
    SLOPPY_WITH_HINTS
  }

  /**
   * Whether, and when, a read sends the newest version to replicas that answered with older ones.
   */
  enum ReadRepair {
    /** No repair. */
    OFF,
    /**
     * Asynchronous: every replica that replied with an older version is repaired after the answer.
     */
    ON,
    /**
     * Blocking: the replicas among the first R replies that were older are repaired before the
     * answer, and the read waits for their acknowledgements, so that of two successive reads the
     * second never returns something older than the first (monotonic quorum reads). Replicas whose
     * replies come later are repaired asynchronously, as under {@link #ON}.
     */
    BLOCKING
  }

  /** The result of a write: acknowledged after W replies, or refused. */
  sealed interface WriteOutcome {}

  /**
   * W replicas acknowledged.
   *
   * @param latencyMicros time from the start to the W-th acknowledgement
   * @param sequence the version's sequence
   * @param standInAcks how many of the W were stand-ins (0 unless sloppy)
   */
  record Acknowledged(long latencyMicros, long sequence, int standInAcks) implements WriteOutcome {}

  /**
   * Fewer than W replicas could acknowledge; the client is told the write failed.
   *
   * @param landedOn home replicas that stored the version anyway (above 0: a partial write)
   */
  record Refused(int landedOn) implements WriteOutcome {}

  /** The result of a read: answered after R replies, or refused. */
  sealed interface ReadOutcome {}

  /**
   * R replicas answered.
   *
   * @param latencyMicros time from the start to the R-th reply
   * @param version the newest version among the R replies
   * @param repairsSent replicas sent a newer version by read repair
   */
  record Answered(long latencyMicros, Version version, int repairsSent) implements ReadOutcome {}

  /** Fewer than R replicas were up to answer. */
  record Unanswered() implements ReadOutcome {}

  private record Reply(int replicaIndex, long arrivalMicros, long replyAtMicros, Version version) {}

  private static final Comparator<Reply> BY_REPLY_TIME =
      Comparator.comparingLong(Reply::replyAtMicros);

  private final QuorumSettings settings;
  private final List<Replica> homeReplicas;
  private final List<Replica> standIns;
  private final ResponseTimes responseTimes;
  private final AckLedger ledger;
  private final HintStore hintStore;
  private final WritePolicy writePolicy;
  private final ReadRepair readRepair;
  private long nextSequence = 1;

  /**
   * Builds a coordinator; {@code homeReplicas} must hold exactly N replicas, in preference order.
   *
   * @throws IllegalArgumentException if the number of home replicas is not N
   */
  QuorumCoordinator(
      QuorumSettings settings,
      List<Replica> homeReplicas,
      List<Replica> standIns,
      ResponseTimes responseTimes,
      WritePolicy writePolicy,
      ReadRepair readRepair) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.homeReplicas = List.copyOf(homeReplicas);
    this.standIns = List.copyOf(standIns);
    this.responseTimes = Objects.requireNonNull(responseTimes, "responseTimes");
    this.writePolicy = Objects.requireNonNull(writePolicy, "writePolicy");
    this.readRepair = Objects.requireNonNull(readRepair, "readRepair");
    if (this.homeReplicas.size() != settings.replicas()) {
      throw new IllegalArgumentException(
          "N is "
              + settings.replicas()
              + " but "
              + this.homeReplicas.size()
              + " home replicas were given");
    }
    this.ledger = new AckLedger(settings);
    this.hintStore = new HintStore();
  }

  /**
   * Puts {@code value} on every home replica at time 0, before the run: the store's starting state.
   */
  void seedEverywhere(String key, String value) {
    Version version = new Version(key, value, nextSequence++);
    homeReplicas.forEach(replica -> replica.receive(version, 0));
    ledger.recordAck(key, version.sequence(), 0, AckLedger.Membership.STRICT);
  }

  /**
   * Writes {@code value} to {@code key}, starting at {@code startMicros}. Refuses before sending
   * when fewer than W replicas are known to be up (fail safe); a replica that dies while the write
   * is in flight may leave the write on fewer than W replicas, which is refused too but has landed.
   */
  WriteOutcome write(long operationId, String key, String value, long startMicros) {
    Version version = new Version(key, value, nextSequence++);
    List<Replica> upAtStart =
        homeReplicas.stream().filter(replica -> replica.isUpAt(startMicros)).toList();
    List<Replica> standInsUp =
        standIns.stream().filter(replica -> replica.isUpAt(startMicros)).toList();
    int missingHomes = settings.replicas() - upAtStart.size();
    int usableStandIns =
        writePolicy == WritePolicy.SLOPPY_WITH_HINTS
            ? Math.min(missingHomes, standInsUp.size())
            : 0;
    if (upAtStart.size() + usableStandIns < settings.writeQuorum()) {
      return new Refused(0);
    }
    List<Long> acknowledgements = new ArrayList<>();
    List<Replica> missed = new ArrayList<>();
    for (int index = 0; index < homeReplicas.size(); index++) {
      Replica replica = homeReplicas.get(index);
      long roundTripMicros = responseTimes.roundTripMicros(operationId, index);
      // Sent only to replicas believed up; one that is down at arrival drops it silently.
      if (upAtStart.contains(replica)
          && replica.receive(version, startMicros + roundTripMicros / 2)) {
        acknowledgements.add(startMicros + roundTripMicros);
      } else {
        missed.add(replica);
      }
    }
    int landedOnHomes = acknowledgements.size();
    int standInAcks =
        handOffHints(operationId, version, startMicros, missed, standInsUp, acknowledgements);
    if (acknowledgements.size() < settings.writeQuorum()) {
      return new Refused(landedOnHomes);
    }
    acknowledgements.sort(Comparator.naturalOrder());
    // The client waits for the W-th fastest acknowledgement: quorum latency is an order statistic.
    long acknowledgedAt = acknowledgements.get(settings.writeQuorum() - 1);
    boolean isSloppy = landedOnHomes < settings.writeQuorum();
    ledger.recordAck(
        key,
        version.sequence(),
        acknowledgedAt,
        isSloppy ? AckLedger.Membership.SLOPPY : AckLedger.Membership.STRICT);
    return new Acknowledged(
        acknowledgedAt - startMicros, version.sequence(), isSloppy ? standInAcks : 0);
  }

  /**
   * Reads {@code key}, starting at {@code startMicros}: asks every home replica, answers after R
   * replies with the newest version among those R, and records the answer in the ledger.
   */
  ReadOutcome read(long operationId, String key, long startMicros) {
    List<Reply> replies = new ArrayList<>();
    for (int index = 0; index < homeReplicas.size(); index++) {
      Replica replica = homeReplicas.get(index);
      int replicaIndex = index;
      long roundTripMicros = responseTimes.roundTripMicros(operationId, index);
      long arrivalMicros = startMicros + roundTripMicros / 2;
      long replyAtMicros = startMicros + roundTripMicros;
      // A replica that is down when the request arrives sends no reply at all.
      if (replica.isUpAt(startMicros) && replica.isUpAt(arrivalMicros)) {
        replica
            .newestAt(key, arrivalMicros)
            .ifPresent(version ->
                replies.add(new Reply(replicaIndex, arrivalMicros, replyAtMicros, version)));
      }
    }
    if (replies.size() < settings.readQuorum()) {
      return new Unanswered();
    }
    replies.sort(BY_REPLY_TIME);
    List<Reply> quorum = replies.subList(0, settings.readQuorum());
    Version newest = newestOf(quorum);
    long answeredAt = quorum.get(settings.readQuorum() - 1).replyAtMicros();
    long completedAt = answeredAt;
    int repairsSent = 0;
    if (readRepair == ReadRepair.BLOCKING) {
      for (Reply reply : quorum) {
        if (newest.isNewerThan(reply.version())) {
          long oneWayMicros = reply.replyAtMicros() - reply.arrivalMicros();
          // The repair leaves with the answer, arrives one way later and is acknowledged one way
          // after that; the read is not complete until then. A repair that finds the replica down
          // is
          // not waited for.
          if (homeReplicas.get(reply.replicaIndex()).receive(newest, answeredAt + oneWayMicros)) {
            repairsSent++;
            completedAt = Math.max(completedAt, answeredAt + 2 * oneWayMicros);
          }
        }
      }
    }
    ledger.recordRead(key, newest.sequence(), startMicros, completedAt);
    if (readRepair == ReadRepair.ON) {
      repairsSent = repair(replies, replies, answeredAt);
    } else if (readRepair == ReadRepair.BLOCKING) {
      repairsSent +=
          repair(replies.subList(settings.readQuorum(), replies.size()), replies, answeredAt);
    }
    return new Answered(completedAt - startMicros, newest, repairsSent);
  }

  /** Replays pending hints to home replicas that are back, at {@code nowMicros}. */
  void replayHints(long nowMicros, long transferMicros) {
    hintStore.replay(nowMicros, transferMicros);
  }

  HintStore hintStore() {
    return hintStore;
  }

  AckLedger ledger() {
    return ledger;
  }

  List<Replica> homeReplicas() {
    return homeReplicas;
  }

  /** Stores one hint per missed home replica on a stand-in that is up; returns the acks counted. */
  private int handOffHints(
      long operationId,
      Version version,
      long startMicros,
      List<Replica> missed,
      List<Replica> standInsUp,
      List<Long> acknowledgements) {
    if (writePolicy == WritePolicy.STRICT_NO_HINTS) {
      return 0;
    }
    int standInAcks = 0;
    for (int slot = 0; slot < Math.min(missed.size(), standInsUp.size()); slot++) {
      Replica standIn = standInsUp.get(slot);
      // Stand-ins draw their own round trips, after the N home replicas' indexes.
      long roundTripMicros = responseTimes.roundTripMicros(operationId, settings.replicas() + slot);
      hintStore.store(
          new HintStore.Hint(
              standIn, missed.get(slot), version, startMicros + roundTripMicros / 2));
      if (writePolicy == WritePolicy.SLOPPY_WITH_HINTS) {
        acknowledgements.add(startMicros + roundTripMicros);
        standInAcks++;
      }
    }
    return standInAcks;
  }

  /**
   * Sends the newest version among all {@code replies} to each reply in {@code targets} that was
   * older, timed to leave when the last reply is in.
   */
  private int repair(List<Reply> targets, List<Reply> replies, long answeredAtMicros) {
    Version newest = newestOf(replies);
    int repairsSent = 0;
    for (Reply reply : targets) {
      if (newest.isNewerThan(reply.version())) {
        // The repair leaves once the last reply is in and takes the replica's one-way time again.
        long lastReplyMicros = Math.max(answeredAtMicros, replies.getLast().replyAtMicros());
        long oneWayMicros = reply.replyAtMicros() - reply.arrivalMicros();
        if (homeReplicas
            .get(reply.replicaIndex())
            .receive(newest, lastReplyMicros + oneWayMicros)) {
          repairsSent++;
        }
      }
    }
    return repairsSent;
  }

  private static Version newestOf(List<Reply> replies) {
    Version newest = replies.getFirst().version();
    for (Reply reply : replies) {
      if (reply.version().isNewerThan(newest)) {
        newest = reply.version();
      }
    }
    return newest;
  }
}
