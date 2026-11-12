package io.plainround.seatwave.quorums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Pins the module's invariant: with R + W > N and strict quorums, no read misses an acknowledged
 * write.
 */
class QuorumOverlapTest {

  private static final int REPLICAS = 5;

  @ParameterizedTest(name = "R = {0}, W = {1}")
  @CsvSource({"3, 3", "2, 4", "4, 2", "1, 5", "5, 1"})
  void readsNeverReturnOlderThanTheLatestAcknowledgedWriteWhenQuorumsOverlap(
      int readQuorum, int writeQuorum) {
    QuorumSettings settings = new QuorumSettings(REPLICAS, readQuorum, writeQuorum);

    // The ledger throws if any read breaks the overlap rule, so finishing the run is half the
    // proof.
    CartWorkload.Stats stats = QuorumSweepCli.run(settings);

    assertThat(stats.staleReads()).isZero();
    assertThat(stats.readsAnswered()).isPositive();
  }

  @Test
  void readsGoStaleWhenQuorumsDoNotOverlap() {
    QuorumSettings settings = new QuorumSettings(REPLICAS, 1, 1);

    CartWorkload.Stats stats = QuorumSweepCli.run(settings);

    assertThat(stats.staleReads()).isPositive();
  }

  @Test
  void refusesWriteThatCannotReachWriteQuorumAndRecordsNoAcknowledgement() {
    List<Replica.Outage> down = List.of(new Replica.Outage(0, 1_000_000));
    List<Replica> replicas =
        List.of(
            new Replica("r1", List.of()),
            new Replica("r2", List.of()),
            new Replica("r3", down),
            new Replica("r4", down),
            new Replica("r5", down));
    QuorumCoordinator coordinator =
        new QuorumCoordinator(
            new QuorumSettings(REPLICAS, 2, 3),
            replicas,
            List.of(),
            ResponseTimes.seeded(QuorumSweepCli.SEED),
            QuorumCoordinator.WritePolicy.STRICT_NO_HINTS,
            QuorumCoordinator.ReadRepair.OFF);
    coordinator.seedEverywhere("cart/0001", "empty");

    QuorumCoordinator.WriteOutcome outcome = coordinator.write(1, "cart/0001", "ticket", 10);

    assertThat(outcome).isEqualTo(new QuorumCoordinator.Refused(0));
    assertThat(replicas.getFirst().newestAt("cart/0001", 2_000_000).orElseThrow().value())
        .isEqualTo("empty");
  }

  @Test
  void rejectsQuorumLargerThanReplicationFactor() {
    assertThatThrownBy(() -> new QuorumSettings(REPLICAS, 6, 3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("R must be 1 to 5");
  }
}
