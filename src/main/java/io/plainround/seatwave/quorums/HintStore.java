package io.plainround.seatwave.quorums;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Hints held by stand-in nodes: "this version belongs on that home replica, which was down when it
 * was written". Hints are replayed to their home replica once it is back, and are lost if the
 * stand-in loses its disk first. Confined to the one simulation thread; not thread-safe.
 */
final class HintStore {

  /**
   * One undelivered write.
   *
   * @param standIn the node that holds the hint
   * @param target the home replica the version belongs on
   * @param version the write itself
   * @param storedAtMicros when the stand-in received it
   */
  record Hint(Replica standIn, Replica target, Version version, long storedAtMicros) {

    /** Rejects missing parts. */
    Hint {
      Objects.requireNonNull(standIn, "standIn");
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(version, "version");
    }
  }

  private final List<Hint> pending = new ArrayList<>();
  private int stored;
  private int delivered;
  private int lost;
  private int peakPending;

  void store(Hint hint) {
    pending.add(hint);
    stored++;
    peakPending = Math.max(peakPending, pending.size());
  }

  /**
   * Replays every hint whose stand-in and target are both up at {@code nowMicros}; the version
   * reaches the target one transfer later. A hint whose target is still down stays pending.
   */
  void replay(long nowMicros, long transferMicros) {
    Iterator<Hint> hints = pending.iterator();
    while (hints.hasNext()) {
      Hint hint = hints.next();
      if (hint.standIn().isUpAt(nowMicros)
          && hint.target().isUpAt(nowMicros)
          && hint.target().receive(hint.version(), nowMicros + transferMicros)) {
        hints.remove();
        delivered++;
      }
    }
  }

  /** The stand-in named {@code standInName} restarts with an empty disk: its hints are gone. */
  void loseHintsOn(String standInName) {
    int before = pending.size();
    pending.removeIf(hint -> hint.standIn().name().equals(standInName));
    lost += before - pending.size();
  }

  int pendingCount() {
    return pending.size();
  }

  /** Age of the oldest pending hint at {@code nowMicros}, or 0 when none is pending. */
  long oldestPendingAgeMicros(long nowMicros) {
    return pending.stream().mapToLong(hint -> nowMicros - hint.storedAtMicros()).max().orElse(0);
  }

  int storedCount() {
    return stored;
  }

  int deliveredCount() {
    return delivered;
  }

  int lostCount() {
    return lost;
  }

  int peakPendingCount() {
    return peakPending;
  }
}
