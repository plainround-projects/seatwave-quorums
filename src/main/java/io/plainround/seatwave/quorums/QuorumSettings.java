package io.plainround.seatwave.quorums;

/**
 * The three numbers of a leaderless store: how many replicas hold each key, how many must answer a
 * read, and how many must acknowledge a write.
 *
 * @param replicas N, the replication factor
 * @param readQuorum R, the replies a read waits for
 * @param writeQuorum W, the acknowledgements a write waits for
 */
public record QuorumSettings(int replicas, int readQuorum, int writeQuorum) {

  /** Rejects N below 1, and R or W outside 1 to N. */
  public QuorumSettings {
    if (replicas < 1) {
      throw new IllegalArgumentException("N must be 1 or more, was " + replicas);
    }
    if (readQuorum < 1 || readQuorum > replicas) {
      throw new IllegalArgumentException("R must be 1 to " + replicas + ", was " + readQuorum);
    }
    if (writeQuorum < 1 || writeQuorum > replicas) {
      throw new IllegalArgumentException("W must be 1 to " + replicas + ", was " + writeQuorum);
    }
  }

  /** Whether every read set shares at least one replica with every write set: R + W > N. */
  public boolean overlaps() {
    return readQuorum + writeQuorum > replicas;
  }

  /** How many replicas may be down while writes still succeed: N - W. */
  public int writeFailuresTolerated() {
    return replicas - writeQuorum;
  }

  /** How many replicas may be down while reads still succeed: N - R. */
  public int readFailuresTolerated() {
    return replicas - readQuorum;
  }
}
