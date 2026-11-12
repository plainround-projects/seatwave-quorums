package io.plainround.seatwave.quorums;

import java.util.Objects;

/**
 * One write of one cart key, as every replica stores it: the value and the coordinator's write
 * sequence. The sequence stands for the write timestamp a store such as Cassandra attaches to every
 * write; replicas keep the version with the highest sequence (last writer wins). Module 25 replaces
 * this single number with vector clocks, which can tell concurrent writes apart.
 *
 * @param key the cart key, for example {@code cart/0042}
 * @param value what the fan's cart holds after this write
 * @param sequence the write's place in the coordinator's order; 1 or more, never reused
 */
public record Version(String key, String value, long sequence) {

  /** Rejects a missing key or value and a sequence below 1. */
  public Version {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(value, "value");
    if (sequence < 1) {
      throw new IllegalArgumentException("sequence must be 1 or more for " + key);
    }
  }

  /** Whether this version wins over {@code other} under last-writer-wins. */
  public boolean isNewerThan(Version other) {
    return sequence > other.sequence;
  }
}
