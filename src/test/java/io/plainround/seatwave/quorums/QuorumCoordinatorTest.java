package io.plainround.seatwave.quorums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.quorums.QuorumCoordinator.Acknowledged;
import io.plainround.seatwave.quorums.QuorumCoordinator.Answered;
import io.plainround.seatwave.quorums.QuorumCoordinator.ReadRepair;
import io.plainround.seatwave.quorums.QuorumCoordinator.Refused;
import io.plainround.seatwave.quorums.QuorumCoordinator.Unanswered;
import io.plainround.seatwave.quorums.QuorumCoordinator.WritePolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuorumCoordinatorTest {

  private static final String KEY = "cart/0001";
  private static final long FOREVER_MICROS = 1_000_000;
  private static final Version OLD = new Version(KEY, "empty", 1);
  private static final Version NEW = new Version(KEY, "one ticket", 2);

  /** Round trips by replica index, the same for every operation: 2, 4, 9 ms, then 3 ms. */
  private static final long[] ROUND_TRIPS_MICROS = {2_000, 4_000, 9_000, 3_000, 3_000};

  private static final ResponseTimes FIXED = (operation, index) -> ROUND_TRIPS_MICROS[index];

  private static Replica up(String name) {
    return new Replica(name, List.of());
  }

  private static Replica downFrom(String name, long startMicros) {
    return new Replica(name, List.of(new Replica.Outage(startMicros, FOREVER_MICROS)));
  }

  private static QuorumCoordinator coordinator(
      int readQuorum,
      int writeQuorum,
      List<Replica> homes,
      List<Replica> standIns,
      WritePolicy policy,
      ReadRepair repair) {
    return new QuorumCoordinator(
        new QuorumSettings(homes.size(), readQuorum, writeQuorum),
        homes,
        standIns,
        FIXED,
        policy,
        repair);
  }

  private static QuorumCoordinator strict(int readQuorum, int writeQuorum, List<Replica> homes) {
    return coordinator(
        readQuorum, writeQuorum, homes, List.of(), WritePolicy.STRICT_NO_HINTS, ReadRepair.OFF);
  }

  @Test
  void aReadAnswersWithTheNewestOfTheRRepliesNotTheFastest() {
    Replica fastWithOld = up("r1");
    Replica slowerWithNew = up("r2");
    fastWithOld.receive(OLD, 0);
    slowerWithNew.receive(NEW, 0);
    QuorumCoordinator coordinator = strict(2, 2, List.of(fastWithOld, slowerWithNew, up("r3")));

    QuorumCoordinator.ReadOutcome outcome = coordinator.read(1, KEY, 10_000);

    // r1 replies first at 2 ms with the old cart; r2 at 4 ms with the new one; r3 holds nothing.
    assertThat(outcome).isEqualTo(new Answered(4_000, NEW, 0));
  }

  @Test
  void aReadWaitsForTheRthReplyEvenWhenItIsTheSlowOne() {
    Replica fastWithOld = up("r1");
    Replica slowWithNew = up("r3");
    fastWithOld.receive(OLD, 0);
    slowWithNew.receive(NEW, 0);
    QuorumCoordinator coordinator = strict(2, 2, List.of(fastWithOld, up("r2"), slowWithNew));
    coordinator.homeReplicas().get(1).receive(OLD, 0);

    QuorumCoordinator.ReadOutcome outcome = coordinator.read(1, KEY, 10_000);

    // Replies: r1 (old) at 2 ms, r2 (old) at 4 ms: the two fastest answer, and the new cart on
    // the 9 ms replica is not seen. R = 3 must wait for it.
    assertThat(outcome).isEqualTo(new Answered(4_000, OLD, 0));
    QuorumCoordinator all =
        strict(3, 3, List.of(fastWithOld, coordinator.homeReplicas().get(1), slowWithNew));
    assertThat(all.read(2, KEY, 10_000)).isEqualTo(new Answered(9_000, NEW, 0));
  }

  @Test
  void aReadWithFewerThanRRepliesIsUnanswered() {
    Replica holder = up("r1");
    holder.receive(OLD, 0);
    QuorumCoordinator coordinator =
        strict(2, 2, List.of(holder, downFrom("r2", 0), downFrom("r3", 0)));

    assertThat(coordinator.read(1, KEY, 10_000)).isEqualTo(new Unanswered());
  }

  @Test
  void aWriteIsAcknowledgedAtTheWthFastestAcknowledgement() {
    List<Replica> homes = List.of(up("r1"), up("r2"), up("r3"));
    QuorumCoordinator coordinator = strict(1, 2, homes);

    QuorumCoordinator.WriteOutcome outcome = coordinator.write(1, KEY, "one ticket", 10_000);

    // Acknowledgements at 2, 4 and 9 ms: W = 2 waits for the second, not the first or the last.
    assertThat(outcome).isEqualTo(new Acknowledged(4_000, 1, 0));
  }

  @Test
  void aWriteThatNeedsEveryReplicaWaitsForTheSlowest() {
    QuorumCoordinator coordinator = strict(1, 3, List.of(up("r1"), up("r2"), up("r3")));

    assertThat(coordinator.write(1, KEY, "one ticket", 10_000))
        .isEqualTo(new Acknowledged(9_000, 1, 0));
  }

  @Test
  void aWriteIsRefusedBeforeSendingWhenFewerThanWReplicasAreUp() {
    Replica holder = up("r1");
    QuorumCoordinator coordinator =
        strict(1, 2, List.of(holder, downFrom("r2", 0), downFrom("r3", 0)));

    assertThat(coordinator.write(1, KEY, "one ticket", 10_000)).isEqualTo(new Refused(0));
    assertThat(holder.newestAt(KEY, FOREVER_MICROS)).isEmpty();
  }

  @Test
  void aReplicaThatDiesWhileTheWriteIsInFlightLeavesAPartialWriteTheClientSeesAsRefused() {
    Replica first = up("r1");
    Replica second = up("r2");
    // r3 is up when the write starts at 10,000 but down when it arrives 4.5 ms later.
    Replica dying = downFrom("r3", 12_000);
    QuorumCoordinator coordinator = strict(1, 3, List.of(first, second, dying));

    QuorumCoordinator.WriteOutcome outcome = coordinator.write(1, KEY, "one ticket", 10_000);

    assertThat(outcome).isEqualTo(new Refused(2));
    assertThat(first.newestAt(KEY, FOREVER_MICROS).orElseThrow().sequence()).isEqualTo(1);
    assertThat(second.newestAt(KEY, FOREVER_MICROS)).isPresent();
    assertThat(dying.newestAt(KEY, FOREVER_MICROS)).isEmpty();
    assertThat(coordinator.ledger().readCount()).isZero();
  }

  @Test
  void aRefusedPartialWriteIsNotAnAcknowledgementSoAReadOfOlderDataIsNotStale() {
    Replica first = up("r1");
    Replica second = up("r2");
    QuorumCoordinator coordinator = strict(3, 3, List.of(first, second, downFrom("r3", 12_000)));
    coordinator.seedEverywhere(KEY, "empty");
    coordinator.write(1, KEY, "one ticket", 10_000);

    coordinator.read(2, KEY, 50_000);

    assertThat(coordinator.ledger().staleReadCount()).isZero();
  }

  @Test
  void strictWithHintsKeepsAHintForTheDownReplicaButDoesNotCountTheStandIn() {
    Replica down = downFrom("r3", 0);
    Replica standIn = up("s1");
    QuorumCoordinator coordinator =
        coordinator(
            1,
            3,
            List.of(up("r1"), up("r2"), down),
            List.of(standIn),
            WritePolicy.STRICT_WITH_HINTS,
            ReadRepair.OFF);

    // W = 3 needs three home replicas: with one down the strict write is refused before sending.
    assertThat(coordinator.write(1, KEY, "one ticket", 10_000)).isEqualTo(new Refused(0));
    assertThat(coordinator.hintStore().storedCount()).isZero();
  }

  @Test
  void strictWithHintsStoresAHintWhenTheWriteStillMeetsW() {
    Replica late = new Replica("r3", List.of(new Replica.Outage(0, 20_000)));
    QuorumCoordinator coordinator =
        coordinator(
            1,
            2,
            List.of(up("r1"), up("r2"), late),
            List.of(up("s1")),
            WritePolicy.STRICT_WITH_HINTS,
            ReadRepair.OFF);

    QuorumCoordinator.WriteOutcome outcome = coordinator.write(1, KEY, "one ticket", 10_000);

    assertThat(outcome).isEqualTo(new Acknowledged(4_000, 1, 0));
    assertThat(coordinator.hintStore().storedCount()).isEqualTo(1);
    assertThat(late.newestAt(KEY, FOREVER_MICROS)).isEmpty();
    coordinator.replayHints(30_000, 1_000);
    assertThat(late.newestAt(KEY, FOREVER_MICROS).orElseThrow().sequence()).isEqualTo(1);
  }

  @Test
  void withoutHintsADownReplicaSimplyMissesTheWrite() {
    QuorumCoordinator coordinator =
        coordinator(
            1,
            2,
            List.of(up("r1"), up("r2"), downFrom("r3", 0)),
            List.of(up("s1")),
            WritePolicy.STRICT_NO_HINTS,
            ReadRepair.OFF);

    coordinator.write(1, KEY, "one ticket", 10_000);

    assertThat(coordinator.hintStore().storedCount()).isZero();
  }

  @Test
  void aSloppyQuorumCountsAStandInTowardWAndMarksTheAcknowledgementSloppy() {
    QuorumCoordinator coordinator =
        coordinator(
            3,
            3,
            List.of(up("r1"), up("r2"), downFrom("r3", 0)),
            List.of(up("s1")),
            WritePolicy.SLOPPY_WITH_HINTS,
            ReadRepair.OFF);

    QuorumCoordinator.WriteOutcome outcome = coordinator.write(1, KEY, "one ticket", 10_000);

    // The stand-in's round trip is the one after the three homes: index 3, 3 ms.
    assertThat(outcome).isEqualTo(new Acknowledged(4_000, 1, 1));
    assertThat(coordinator.hintStore().pendingCount()).isEqualTo(1);
  }

  @Test
  void aSloppyWriteIsRefusedWhenThereAreNotEnoughStandInsEither() {
    QuorumCoordinator coordinator =
        coordinator(
            1,
            3,
            List.of(up("r1"), downFrom("r2", 0), downFrom("r3", 0)),
            List.of(up("s1")),
            WritePolicy.SLOPPY_WITH_HINTS,
            ReadRepair.OFF);

    assertThat(coordinator.write(1, KEY, "one ticket", 10_000)).isEqualTo(new Refused(0));
  }

  @Test
  void readRepairSendsTheNewestVersionToTheReplicasThatAnsweredWithOlderOnes() {
    Replica stale = up("r1");
    Replica fresh = up("r2");
    Replica alsoFresh = up("r3");
    stale.receive(OLD, 0);
    fresh.receive(NEW, 0);
    alsoFresh.receive(NEW, 0);
    QuorumCoordinator coordinator =
        coordinator(
            2,
            2,
            List.of(stale, fresh, alsoFresh),
            List.of(),
            WritePolicy.STRICT_NO_HINTS,
            ReadRepair.ON);

    QuorumCoordinator.ReadOutcome outcome = coordinator.read(1, KEY, 10_000);

    assertThat(outcome).isEqualTo(new Answered(4_000, NEW, 1));
    assertThat(stale.newestAt(KEY, FOREVER_MICROS)).contains(NEW);
  }

  @Test
  void withoutReadRepairTheStaleReplicaStaysStale() {
    Replica stale = up("r1");
    stale.receive(OLD, 0);
    Replica fresh = up("r2");
    fresh.receive(NEW, 0);
    QuorumCoordinator coordinator = strict(2, 2, List.of(stale, fresh, up("r3")));

    QuorumCoordinator.ReadOutcome outcome = coordinator.read(1, KEY, 10_000);

    assertThat(outcome).isEqualTo(new Answered(4_000, NEW, 0));
    assertThat(stale.newestAt(KEY, FOREVER_MICROS)).contains(OLD);
  }

  @Test
  void rejectsAHomeReplicaListThatIsNotExactlyN() {
    List<Replica> two = List.of(up("r1"), up("r2"));

    assertThatThrownBy(() ->
            new QuorumCoordinator(
                new QuorumSettings(3, 2, 2),
                two,
                List.of(),
                FIXED,
                WritePolicy.STRICT_NO_HINTS,
                ReadRepair.OFF))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("N is 3 but 2 home replicas were given");
  }

  /** Operation 1 sees round trips of 2, 4 and 9 ms; every later operation sees 9, 2 and 4 ms. */
  private static final ResponseTimes SHIFTING =
      (operation, index) ->
          (operation == 1 ? new long[] {2_000, 4_000, 9_000} : new long[] {9_000, 2_000, 4_000})
              [index];

  private static QuorumCoordinator withOneNewReplica(ReadRepair repair) {
    Replica holdsNew = up("r1");
    Replica holdsOld = up("r2");
    Replica alsoOld = up("r3");
    holdsNew.receive(NEW, 0);
    holdsOld.receive(OLD, 0);
    alsoOld.receive(OLD, 0);
    return new QuorumCoordinator(
        new QuorumSettings(3, 2, 2),
        List.of(holdsNew, holdsOld, alsoOld),
        List.of(),
        SHIFTING,
        WritePolicy.STRICT_NO_HINTS,
        repair);
  }

  @Test
  void blockingRepairWaitsForTheRepairedReplicaBeforeAnswering() {
    QuorumCoordinator coordinator = withOneNewReplica(ReadRepair.BLOCKING);

    QuorumCoordinator.ReadOutcome outcome = coordinator.read(1, KEY, 0);

    // r1 (new) replies at 2 ms, r2 (old) at 4 ms: the answer is ready at 4 ms. r2's repair arrives
    // at 6 ms and is acknowledged at 8 ms; r3's late old reply (9 ms) is repaired asynchronously.
    assertThat(outcome).isEqualTo(new Answered(8_000, NEW, 2));
    assertThat(coordinator.homeReplicas().get(1).newestAt(KEY, 6_000)).contains(NEW);
    assertThat(coordinator.homeReplicas().get(1).newestAt(KEY, 5_999)).contains(OLD);
  }

  @Test
  void asynchronousRepairAnswersAtOnceAndRepairsLater() {
    QuorumCoordinator coordinator = withOneNewReplica(ReadRepair.ON);

    QuorumCoordinator.ReadOutcome outcome = coordinator.read(1, KEY, 0);

    assertThat(outcome).isEqualTo(new Answered(4_000, NEW, 2));
    // Both repairs leave when the last reply is in, at 9 ms: r2's arrives at 11 ms.
    assertThat(coordinator.homeReplicas().get(1).newestAt(KEY, 10_999)).contains(OLD);
    assertThat(coordinator.homeReplicas().get(1).newestAt(KEY, 11_000)).contains(NEW);
  }

  @Test
  void aSecondReadNeverGoesBackwardsAfterABlockingRepairButCanAfterAnAsynchronousOne() {
    QuorumCoordinator blocking = withOneNewReplica(ReadRepair.BLOCKING);
    QuorumCoordinator asynchronous = withOneNewReplica(ReadRepair.ON);

    for (QuorumCoordinator coordinator : List.of(blocking, asynchronous)) {
      coordinator.read(1, KEY, 0);
      // The second read asks r2 and r3 first (2 ms and 4 ms), and begins after the first finished.
      coordinator.read(2, KEY, 9_000);
    }

    assertThat(blocking.ledger().backwardsReadCount()).isZero();
    assertThat(asynchronous.ledger().backwardsReadCount()).isEqualTo(1);
  }
}
