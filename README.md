# seatwave-quorums

A simulated five-replica cart store for SeatWave with tunable R and W, hinted handoff, read repair (asynchronous and blocking) and Merkle-tree anti-entropy, run on a virtual clock. This is the project of Module 22, "Quorums", of the course System Design Fundamentals. It measures stale reads, refusals and latency for eleven (R, W) pairs, shows how a returned replica heals, what a sloppy quorum keeps and weakens, and three short histories in which R + W > N holds and the store is still not linearizable.

Every number the programs print is a number of the model: round trips are drawn from a fixed distribution (98 percent 2 to 8 ms, 2 percent 8 to 32 ms, p99 20.0 ms) on a virtual clock, so a run is the same on every machine. They are not measurements of Cassandra or of any real network. No Docker, no database and no container is involved.

## Prerequisites

- JDK 25 (the Gradle toolchain finds it; built and tested with Eclipse Temurin 25.0.2, where 25.0.5+8 is the current release on 12 November 2026).
- Nothing else: `./gradlew` downloads Gradle 9.9.1 (checksum pinned) and the test libraries.

## Dependencies (each checked on Maven Central and the Gradle site on 12 November 2026; OSV listed no vulnerability)

| What | Coordinates | Version | License | Why the JDK alone is not enough |
|---|---|---|---|---|
| Gradle wrapper | `gradle-9.9.1-bin.zip`, both checksums compared | 9.9.1 | Apache-2.0 | one build tool version for every reader |
| JUnit | `org.junit:junit-bom` (test) | 6.1.3 | EPL-2.0 | the test runner |
| AssertJ | `org.assertj:assertj-core` (test) | 3.27.7 | Apache-2.0 | readable assertions |
| Spotless, google-java-format | `com.diffplug.spotless`, `com.google.googlejavaformat:google-java-format` | 8.11.0, 2.0.0 | Apache-2.0 | the formatter |
| JaCoCo | `org.jacoco` | 0.8.16 | EPL-2.0 | line coverage, floor 0.80 |

The simulation itself uses only the JDK (`MessageDigest` for the Merkle tree, `SplittableRandom` for round trips).

## Commands and their real output

Build, test, format check and run all four programs: `./gradlew clean check sweepRun healingRun sloppyRun anomaliesRun` (Gradle 9.9.1, JDK 25.0.2, Apple-silicon laptop, 12 November 2026). The last lines of the build part:

```
> Task :jacocoTestCoverageVerification
> Task :spotlessJava
> Task :spotlessJavaCheck
> Task :spotlessCheck
> Task :check
> Task :jacocoTestReport
...
BUILD SUCCESSFUL in 14s
12 actionable tasks: 12 executed
```

76 tests (JUnit counts each parameter set of a parameterized test), 0 failed. Line coverage of everything except the `*Cli` programs: 96.8 percent (418 of 432 lines), against a floor of 0.80. The programs are run by hand and their output is checked in the module; the tests call `QuorumSweepCli.run`, which exercises the workload and coordinator end to end.

The programs, one task each:

| Task | Program | What it prints |
|---|---|---|
| `./gradlew -q sweepRun` | `QuorumSweepCli` | stale reads, refusals and latency for eleven (R, W) pairs |
| `./gradlew -q healingRun` | `ReplicaHealingCli` | read repair, hinted handoff and Merkle-tree anti-entropy after an outage; the cost of a Merkle comparison |
| `./gradlew -q sloppyRun` | `SloppyQuorumCli` | strict against sloppy quorums, and lost hints |
| `./gradlew -q anomaliesRun` | `QuorumAnomaliesCli` | the histories outside the overlap guarantee |

For example, `./gradlew -q sweepRun`:

```
SEATWAVE QUORUM SWEEP: N = 5 cart replicas r1 to r5, 2,000 carts, 120 s of virtual time
traffic: 500 adds/s, a re-read 5 ms after each acknowledged add, 500 background reads/s
failures: r5 down 20.0-21.2 s; r2 and r4 down 60.0-60.5 s; r1, r2 and r3 down 90.0-90.5 s
strict quorums, no hints, no read repair; replica round trip p50 5.1 ms, p99 20.0 ms (1,000,000 draws of the model's input)

R W  R+W>N  writes ok refused partial   reads ok refused  stale  stale%  backwards  write p50  p99  read p50  p99  down tolerated w/r
1 1  no         59999       0       0     119994       0   1002   0.835        335        2.8  5.7       2.8  5.7  4 / 4
2 2  no         59999       0       0     119993       0     98   0.082         93        3.9  6.8       3.9  6.9  3 / 3
1 3  no         59748     251       1     119742       0    593   0.495        197        5.1  7.5       2.8  5.7  2 / 4
2 3  no         59748     251       1     119742       0     23   0.019         22        5.1  7.5       3.9  6.8  2 / 3
3 2  no         59999       0       0     119493     500     22   0.018         12        3.9  6.8       5.1  7.5  3 / 2
3 3  yes        59748     251       1     119487     255      0   0.000          0        5.1  7.5       5.1  7.5  2 / 2
2 4  yes        59498     501       1     119491       0      0   0.000          0        6.2  7.9       3.9  6.8  1 / 3
4 2  yes        59999       0       0     118992    1001      0   0.000          0        3.9  6.8       6.2  7.9  3 / 1
1 5  yes        58895    1104       4     118888       0      0   0.000          0        7.3 29.8       2.8  5.7  0 / 4
5 1  yes        59999       0       0     117786    2208      0   0.000          0        2.8  5.7       7.3 29.6  4 / 0
5 5  yes        58895    1104       4     117767    1121      0   0.000          0        7.3 29.8       7.3 29.6  0 / 0
```

The other three programs print similar tables; their full output is in the module, Section 8. All four were run again through Gradle on 12 November 2026 and match the output captured when the module was written, byte for byte.

## Mutation checks

Every test that guards a rule was checked by removing the rule in a scratch copy and running `./gradlew test --continue`. A mutant is killed when a test fails. Full output: `module-prompts/Generated/_tools/module-22-outputs/mutants.txt` of the course folder.

| Rule removed | Tests that fail |
|---|---|
| A read answers with the newest of the R replies (mutant: the fastest reply) | 8: four in `QuorumCoordinatorTest` (newest not fastest, waits for the R-th reply, both repair tests) and four `QuorumOverlapTest` parameter sets |
| A write is acknowledged only with W acknowledgements (mutant: any one acknowledgement counts after sending) | 5: `QuorumCoordinatorTest` (the partial write is refused; a refused write is not an acknowledgement) and three `QuorumOverlapTest` parameter sets |
| A write is not sent when fewer than W replicas are up (mutant: sent anyway) | 4: three in `QuorumCoordinatorTest`, `QuorumOverlapTest.refusesWriteThatCannotReachWriteQuorum...` |
| A write is acknowledged at the W-th acknowledgement (mutant: the first) | 6: four in `QuorumCoordinatorTest`, two `QuorumOverlapTest` parameter sets |
| A hint replays only when the stand-in is up (mutant: the check removed) | 1: `HintStoreTest.keepsTheHintWhileTheStandInIsDown` |
| A hint replays only when the home replica is up (mutant: the check removed) | 1: `HintStoreTest.keepsTheHintWhileTheHomeReplicaIsDownEvenIfItIsBackBeforeTheTransferLands` |
| A Merkle root does not depend on insertion order (mutant: the sort inside a leaf removed) | 1: `MerkleTreeTest.rootsDoNotDependOnTheOrderEntriesAreInserted` |

Repeated runs (12 November 2026, three busy loops beside the build, load average 10): `./gradlew cleanTest test` 40 times in a row, 40 passes, 0 failures. `./gradlew clean check` also passes from a path of 493 characters and from a folder with spaces in its name.

## Project structure

```
src/main/java/io/plainround/seatwave/quorums/   the model and the four *Cli programs
src/test/java/io/plainround/seatwave/quorums/   unit tests, one class per type
build.gradle.kts  gradle/libs.versions.toml  gradle.lockfile  settings-gradle.lockfile
ARCHITECTURE.md  CHANGELOG.md  LICENSE
```

## What is simplified

- One virtual clock and one thread: no real network, no contention, no clock drift.
- One write sequence from one coordinator (last writer wins). Module 25 replaces it with vector clocks.
- Round trips are drawn from an assumed distribution, not measured. Replica operation rates and the 20 ms p99 are assumptions (the module marks them `[uncertain]`); a run against a real Cassandra cluster would replace them and was not done.
- Replicas keep their disks across an outage; only stand-ins can lose their hints, and only when the workload says so.
- The client's per-workload R and W, mutual TLS between nodes, hint caps and the repair schedule are described in the module, not coded.

## No CI on purpose

There is no `.github/` folder and no workflow. `./gradlew clean check` is the gate and runs on a laptop in under a minute.
