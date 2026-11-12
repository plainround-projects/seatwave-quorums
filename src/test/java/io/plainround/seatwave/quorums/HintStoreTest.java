package io.plainround.seatwave.quorums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class HintStoreTest {

  private static final String KEY = "cart/0001";
  private static final long TRANSFER_MICROS = 1_000;
  private static final Version VERSION = new Version(KEY, "ticket", 1);

  private final HintStore hints = new HintStore();

  private static Replica up(String name) {
    return new Replica(name, List.of());
  }

  private static Replica downUntil(String name, long endMicros) {
    return new Replica(name, List.of(new Replica.Outage(0, endMicros)));
  }

  @Test
  void replaysToTheHomeReplicaWhenBothEndsAreUp() {
    Replica standIn = up("s1");
    Replica target = up("r1");
    hints.store(new HintStore.Hint(standIn, target, VERSION, 0));

    hints.replay(5_000, TRANSFER_MICROS);

    assertThat(hints.pendingCount()).isZero();
    assertThat(hints.deliveredCount()).isEqualTo(1);
    assertThat(target.newestAt(KEY, 5_999)).isEmpty();
    assertThat(target.newestAt(KEY, 6_000)).contains(VERSION);
  }

  @Test
  void keepsTheHintWhileTheHomeReplicaIsDownEvenIfItIsBackBeforeTheTransferLands() {
    Replica target = downUntil("r1", 5_500);
    hints.store(new HintStore.Hint(up("s1"), target, VERSION, 0));

    // The replica is back at 5,500, before the transfer would land at 6,000, but not yet at 5,000.
    hints.replay(5_000, TRANSFER_MICROS);

    assertThat(hints.pendingCount()).isEqualTo(1);
    assertThat(hints.deliveredCount()).isZero();
    assertThat(target.newestAt(KEY, 10_000)).isEmpty();
  }

  @Test
  void keepsTheHintWhileTheStandInIsDown() {
    Replica target = up("r1");
    hints.store(new HintStore.Hint(downUntil("s1", 5_500), target, VERSION, 0));

    hints.replay(5_000, TRANSFER_MICROS);

    assertThat(hints.pendingCount()).isEqualTo(1);
    assertThat(hints.deliveredCount()).isZero();
    assertThat(target.newestAt(KEY, 10_000)).isEmpty();
  }

  @Test
  void deliversOnALaterReplayOnceBothEndsAreBack() {
    Replica target = downUntil("r1", 5_000);
    hints.store(new HintStore.Hint(up("s1"), target, VERSION, 0));
    hints.replay(2_000, TRANSFER_MICROS);

    hints.replay(5_000, TRANSFER_MICROS);

    assertThat(hints.pendingCount()).isZero();
    assertThat(hints.deliveredCount()).isEqualTo(1);
    assertThat(target.newestAt(KEY, 6_000)).contains(VERSION);
  }

  @Test
  void hintsOnAStandInThatLosesItsDiskAreGoneAndNeverReplayed() {
    Replica target = up("r1");
    Replica other = up("r2");
    hints.store(new HintStore.Hint(up("s1"), target, VERSION, 0));
    hints.store(new HintStore.Hint(up("s1"), target, new Version(KEY, "two", 2), 0));
    hints.store(new HintStore.Hint(up("s2"), other, VERSION, 0));

    hints.loseHintsOn("s1");
    hints.replay(5_000, TRANSFER_MICROS);

    assertThat(hints.lostCount()).isEqualTo(2);
    assertThat(hints.deliveredCount()).isEqualTo(1);
    assertThat(hints.storedCount()).isEqualTo(3);
    assertThat(target.newestAt(KEY, 10_000)).isEmpty();
    assertThat(other.newestAt(KEY, 10_000)).contains(VERSION);
  }

  @Test
  void losingHintsOnAStandInThatHoldsNoneChangesNothing() {
    hints.store(new HintStore.Hint(up("s1"), up("r1"), VERSION, 0));

    hints.loseHintsOn("s2");

    assertThat(hints.lostCount()).isZero();
    assertThat(hints.pendingCount()).isEqualTo(1);
  }

  @Test
  void reportsTheAgeOfTheOldestPendingHintAndThePeak() {
    assertThat(hints.oldestPendingAgeMicros(9_000)).isZero();
    hints.store(new HintStore.Hint(up("s1"), downUntil("r1", 99_000), VERSION, 4_000));
    hints.store(new HintStore.Hint(up("s1"), downUntil("r2", 99_000), VERSION, 1_000));

    assertThat(hints.oldestPendingAgeMicros(9_000)).isEqualTo(8_000);
    assertThat(hints.peakPendingCount()).isEqualTo(2);
  }

  @Test
  void rejectsAHintWithAMissingPart() {
    assertThatThrownBy(() -> new HintStore.Hint(null, up("r1"), VERSION, 0))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("standIn");
  }
}
