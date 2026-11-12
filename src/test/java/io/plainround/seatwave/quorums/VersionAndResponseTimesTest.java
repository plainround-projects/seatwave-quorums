package io.plainround.seatwave.quorums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class VersionAndResponseTimesTest {

  private static final long SEED = 42;

  @Test
  void theHigherSequenceWinsAndEqualSequencesDoNot() {
    Version older = new Version("cart/0001", "a", 1);
    Version newer = new Version("cart/0001", "b", 2);

    assertThat(newer.isNewerThan(older)).isTrue();
    assertThat(older.isNewerThan(newer)).isFalse();
    assertThat(older.isNewerThan(older)).isFalse();
  }

  @Test
  void rejectsASequenceBelowOneAndMissingParts() {
    assertThatThrownBy(() -> new Version("cart/0001", "a", 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cart/0001");
    assertThatThrownBy(() -> new Version(null, "a", 1)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Version("cart/0001", null, 1))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void roundTripsRepeatForTheSameOperationAndReplicaAndStayInsideTheBands() {
    ResponseTimes times = ResponseTimes.seeded(SEED);

    long slow =
        LongStream.range(0, 5_000)
            .peek(operation -> {
              long micros = times.roundTripMicros(operation, 2);
              assertThat(micros).isEqualTo(times.roundTripMicros(operation, 2));
              assertThat(micros)
                  .isBetween(ResponseTimes.FAST_MIN_MICROS, ResponseTimes.SLOW_MAX_MICROS);
            })
            .filter(
                operation -> times.roundTripMicros(operation, 2) >= ResponseTimes.FAST_MAX_MICROS)
            .count();

    // About 2 percent of 5,000 round trips are slow: 100, give or take chance.
    assertThat(slow).isBetween(50L, 150L);
  }

  @Test
  void differentReplicasDrawDifferentRoundTripsForOneOperation() {
    ResponseTimes times = ResponseTimes.seeded(SEED);

    long distinct =
        LongStream.range(0, 5)
            .map(replica -> times.roundTripMicros(1, (int) replica))
            .distinct()
            .count();

    assertThat(distinct).isGreaterThan(1);
  }
}
