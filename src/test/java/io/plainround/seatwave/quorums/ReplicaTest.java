package io.plainround.seatwave.quorums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReplicaTest {

  private static final String KEY = "cart/0001";
  private static final Replica.Outage OUTAGE = new Replica.Outage(1_000, 2_000);

  @Test
  void aMessageArrivingWhileDownIsLostForGood() {
    Replica replica = new Replica("r1", List.of(OUTAGE));

    boolean received = replica.receive(new Version(KEY, "ticket", 1), 1_500);

    assertThat(received).isFalse();
    assertThat(replica.newestAt(KEY, 10_000)).isEmpty();
    assertThat(replica.snapshotAt(10_000)).isEmpty();
  }

  @Test
  void dataReceivedBeforeAnOutageSurvivesIt() {
    Replica replica = new Replica("r1", List.of(OUTAGE));
    Version version = new Version(KEY, "ticket", 1);

    assertThat(replica.receive(version, 500)).isTrue();

    assertThat(replica.newestAt(KEY, 1_500)).contains(version);
    assertThat(replica.newestAt(KEY, 3_000)).contains(version);
  }

  @Test
  void anOutageStartsAtItsFirstInstantAndEndsBeforeItsLast() {
    Replica replica = new Replica("r1", List.of(OUTAGE));

    assertThat(replica.isUpAt(999)).isTrue();
    assertThat(replica.isUpAt(1_000)).isFalse();
    assertThat(replica.isUpAt(1_999)).isFalse();
    assertThat(replica.isUpAt(2_000)).isTrue();
  }

  @Test
  void aVersionIsVisibleOnlyFromTheMomentItArrived() {
    Replica replica = new Replica("r1", List.of());
    Version version = new Version(KEY, "ticket", 1);
    replica.receive(version, 400);

    assertThat(replica.newestAt(KEY, 399)).isEmpty();
    assertThat(replica.newestAt(KEY, 400)).contains(version);
  }

  @Test
  void theNewestVersionWinsBySequenceNotByArrivalOrder() {
    Replica replica = new Replica("r1", List.of());
    Version newer = new Version(KEY, "two tickets", 5);
    Version older = new Version(KEY, "one ticket", 2);
    replica.receive(newer, 100);
    replica.receive(older, 200);

    assertThat(replica.newestAt(KEY, 300)).contains(newer);
    assertThat(replica.newestAt(KEY, 150)).contains(newer);
  }

  @Test
  void snapshotHoldsEachKeysNewestVersionInKeyOrder() {
    Replica replica = new Replica("r1", List.of());
    Version second = new Version("cart/0002", "a", 3);
    Version firstOld = new Version("cart/0001", "a", 1);
    Version firstNew = new Version("cart/0001", "b", 4);
    replica.receive(second, 10);
    replica.receive(firstOld, 20);
    replica.receive(firstNew, 30);

    assertThat(replica.snapshotAt(25).values()).containsExactly(firstOld, second);
    assertThat(replica.snapshotAt(35).values()).containsExactly(firstNew, second);
  }

  @Test
  void rejectsAnOutageThatEndsBeforeItStartsOrStartsBeforeZero() {
    assertThatThrownBy(() -> new Replica.Outage(5, 5))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("end after it starts");
    assertThatThrownBy(() -> new Replica.Outage(-1, 5))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
