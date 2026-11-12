package io.plainround.seatwave.quorums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AckLedgerTest {

  private static final String KEY = "cart/0001";

  @Test
  void aReadOlderThanAStrictAcknowledgedWriteBreaksTheOverlapRuleWhenQuorumsOverlap() {
    AckLedger ledger = new AckLedger(new QuorumSettings(5, 3, 3));
    ledger.recordAck(KEY, 7, 100, AckLedger.Membership.STRICT);

    assertThatThrownBy(() -> ledger.recordRead(KEY, 6, 200, 300))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("overlap broken")
        .hasMessageContaining(KEY);
  }

  @Test
  void aStaleReadIsCountedNotFatalWhenQuorumsDoNotOverlap() {
    AckLedger ledger = new AckLedger(new QuorumSettings(5, 1, 1));
    ledger.recordAck(KEY, 7, 100, AckLedger.Membership.STRICT);

    ledger.recordRead(KEY, 6, 200, 300);

    assertThat(ledger.staleReadCount()).isEqualTo(1);
    assertThat(ledger.staleReadsAfterSloppyWritesCount()).isZero();
  }

  @Test
  void aStaleReadAfterASloppyWriteIsCountedSeparatelyEvenWhenQuorumsOverlap() {
    AckLedger ledger = new AckLedger(new QuorumSettings(5, 3, 3));
    ledger.recordAck(KEY, 7, 100, AckLedger.Membership.SLOPPY);

    ledger.recordRead(KEY, 6, 200, 300);

    assertThat(ledger.staleReadCount()).isEqualTo(1);
    assertThat(ledger.staleReadsAfterSloppyWritesCount()).isEqualTo(1);
  }

  @Test
  void aWriteAcknowledgedAfterTheReadBeganDoesNotMakeItStale() {
    AckLedger ledger = new AckLedger(new QuorumSettings(5, 3, 3));
    ledger.recordAck(KEY, 7, 250, AckLedger.Membership.STRICT);

    ledger.recordRead(KEY, 6, 200, 300);

    assertThat(ledger.staleReadCount()).isZero();
    assertThat(ledger.readCount()).isEqualTo(1);
  }

  @Test
  void aReadOlderThanAnEarlierFinishedReadGoesBackwards() {
    AckLedger ledger = new AckLedger(new QuorumSettings(5, 1, 1));
    ledger.recordRead(KEY, 7, 100, 150);

    ledger.recordRead(KEY, 6, 200, 250);
    ledger.recordRead(KEY, 6, 120, 260);

    // The second read began after the first finished, so it went backwards; the third overlapped.
    assertThat(ledger.backwardsReadCount()).isEqualTo(1);
  }
}
