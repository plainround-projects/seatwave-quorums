package io.plainround.seatwave.quorums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class QuorumSettingsTest {

  @ParameterizedTest(name = "N = 5, R = {0}, W = {1} overlaps: {2}")
  @CsvSource({"3, 3, true", "2, 4, true", "1, 5, true", "2, 3, false", "1, 1, false", "5, 1, true"})
  void overlapsExactlyWhenRPlusWExceedsN(int readQuorum, int writeQuorum, boolean expected) {
    assertThat(new QuorumSettings(5, readQuorum, writeQuorum).overlaps()).isEqualTo(expected);
  }

  @Test
  void reportsHowManyReplicasMayBeDown() {
    QuorumSettings settings = new QuorumSettings(5, 2, 4);

    assertThat(settings.writeFailuresTolerated()).isEqualTo(1);
    assertThat(settings.readFailuresTolerated()).isEqualTo(3);
  }

  @Test
  void rejectsInvalidNAndWriteQuorum() {
    assertThatThrownBy(() -> new QuorumSettings(0, 1, 1))
        .hasMessageContaining("N must be 1 or more");
    assertThatThrownBy(() -> new QuorumSettings(5, 1, 0)).hasMessageContaining("W must be 1 to 5");
    assertThatThrownBy(() -> new QuorumSettings(5, 0, 1)).hasMessageContaining("R must be 1 to 5");
  }
}
