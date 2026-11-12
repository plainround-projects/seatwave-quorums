package io.plainround.seatwave.quorums;

import java.util.SplittableRandom;

/**
 * The round trip from the coordinator to one replica and back, for one operation. A request reaches
 * the replica after half the round trip; the reply arrives after all of it.
 */
@FunctionalInterface
public interface ResponseTimes {

  /** Share of round trips in the fast band; the rest fall in the slow tail. */
  double FAST_SHARE = 0.98;

  /** Fast band: 2 to 8 ms, a replica in the same region on a quiet day (assumed). */
  long FAST_MIN_MICROS = 2_000;

  /** Upper end of the fast band and lower end of the slow tail. */
  long FAST_MAX_MICROS = 8_000;

  /**
   * Slow tail: 8 to 32 ms (a pause, a busy disk). With 2 percent of round trips spread evenly over
   * 8 to 32 ms, the 99th percentile sits half way along the tail, at 20 ms: the brief's replica
   * p99.
   */
  long SLOW_MAX_MICROS = 32_000;

  /** Mixes the operation id into the seed so that each pair gets its own independent stream. */
  long OPERATION_MIX = 0x9E37_79B9_7F4A_7C15L;

  /** Mixes the replica index into the seed. */
  long REPLICA_MIX = 0xC2B2_AE3D_27D4_EB4FL;

  /**
   * The round trip, in microseconds, of operation {@code operationId} to the replica at {@code
   * replicaIndex} (0 to N - 1 for home replicas, N and up for stand-ins).
   */
  long roundTripMicros(long operationId, int replicaIndex);

  /**
   * Round trips drawn from the fixed distribution above. The draw depends only on the seed, the
   * operation and the replica, so the same write meets the same replica delays under every R and W:
   * settings are compared on identical luck.
   */
  static ResponseTimes seeded(long seed) {
    return (operationId, replicaIndex) -> {
      SplittableRandom random =
          new SplittableRandom(seed ^ (operationId * OPERATION_MIX) ^ (replicaIndex * REPLICA_MIX));
      return random.nextDouble() < FAST_SHARE
          ? random.nextLong(FAST_MIN_MICROS, FAST_MAX_MICROS)
          : random.nextLong(FAST_MAX_MICROS, SLOW_MAX_MICROS);
    };
  }
}
