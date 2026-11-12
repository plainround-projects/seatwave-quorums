# Changelog

## 1.0.0 (12 November 2026)

- Built from the Module 22 code seed: `QuorumSettings`, `Version`, `Replica`, `HintStore`, `ResponseTimes`, `QuorumCoordinator`, `AckLedger`, `MerkleTree`, `CartWorkload` and the four programs. The four programs print byte for byte what the seed printed.
- Gradle 9.9.1 wrapper pinned by checksum, Kotlin DSL, version catalog, dependency and settings lockfiles, `-Xlint:all -Werror`, Spotless with google-java-format 2.0.0, JaCoCo with a line floor of 0.80, one `JavaExec` task per program.
- Added `ReadRepair.BLOCKING` (the read waits for the repair of the replicas among its first R replies). `QuorumAnomaliesCli` now lists `OFF` and `ON` explicitly so its output does not change.
- Tests for `MerkleTree`, `HintStore`, `Replica`, `QuorumCoordinator` (including the partial-write path and both repair modes), `AckLedger`, `QuorumSettings`, `Version` and `ResponseTimes`, each checked by removing the rule it guards (README, "Mutation checks").
