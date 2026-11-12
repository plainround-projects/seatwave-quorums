package io.plainround.seatwave.quorums;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * One replica of the cart store: the versions it has received, each visible from the moment it
 * arrived, and the times it is down. A message that arrives while the replica is down is lost; data
 * received before an outage survives it (the disk is intact). Confined to the one simulation
 * thread; not thread-safe.
 */
final class Replica {

  private final String name;
  private final List<Outage> outages;
  private final Map<String, List<Stored>> versionsByKey = new HashMap<>();

  /**
   * One interval during which a replica answers nothing and drops what it is sent.
   *
   * @param startMicros first instant of the outage
   * @param endMicros first instant after it
   */
  record Outage(long startMicros, long endMicros) {

    /** Rejects a negative start and an outage that ends before it starts. */
    Outage {
      if (startMicros < 0 || endMicros <= startMicros) {
        throw new IllegalArgumentException(
            "an outage must start at 0 or later and end after it starts");
      }
    }

    boolean covers(long atMicros) {
      return atMicros >= startMicros && atMicros < endMicros;
    }
  }

  private record Stored(long visibleAtMicros, Version version) {}

  Replica(String name, List<Outage> outages) {
    this.name = Objects.requireNonNull(name, "name");
    this.outages = List.copyOf(outages);
  }

  String name() {
    return name;
  }

  boolean isUpAt(long atMicros) {
    return outages.stream().noneMatch(outage -> outage.covers(atMicros));
  }

  /**
   * Delivers {@code version} at {@code arrivalMicros}. Returns false, and keeps nothing, if the
   * replica is down at that instant: the sender learns it only by the missing acknowledgement.
   */
  boolean receive(Version version, long arrivalMicros) {
    if (!isUpAt(arrivalMicros)) {
      return false;
    }
    versionsByKey
        .computeIfAbsent(version.key(), absent -> new ArrayList<>())
        .add(new Stored(arrivalMicros, version));
    return true;
  }

  /** The newest version of {@code key} that had arrived by {@code atMicros}, if any had. */
  Optional<Version> newestAt(String key, long atMicros) {
    return versionsByKey.getOrDefault(key, List.of()).stream()
        .filter(stored -> stored.visibleAtMicros() <= atMicros)
        .map(Stored::version)
        .reduce((left, right) -> right.isNewerThan(left) ? right : left);
  }

  /**
   * Every key's newest version as of {@code atMicros}, in key order: what anti-entropy compares.
   */
  SortedMap<String, Version> snapshotAt(long atMicros) {
    SortedMap<String, Version> snapshot = new TreeMap<>();
    for (String key : versionsByKey.keySet()) {
      newestAt(key, atMicros).ifPresent(version -> snapshot.put(key, version));
    }
    return snapshot;
  }
}
