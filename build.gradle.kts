plugins {
    java
    jacoco
    alias(libs.plugins.spotless)
}

group = "io.plainround"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // The simulation needs only the JDK; the tests need JUnit and AssertJ.
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Transitive versions are written to gradle.lockfile, so two builds can never resolve different jars.
dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    // Every warning is an error: a warning that is allowed to stay is a defect nobody owns.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

spotless {
    java {
        googleJavaFormat(libs.versions.googleJavaFormat.get())
        removeUnusedImports()
        endWithNewline()
    }
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
    finalizedBy(tasks.jacocoTestReport)
}

// The programs (*Cli) are run by hand and their output is printed in the README and the module.
tasks.jacocoTestReport {
    reports {
        xml.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    classDirectories.setFrom(sourceSets.main.get().output.asFileTree.matching { exclude("**/*Cli*") })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

// One task per program, so a reader runs one command per example.
mapOf(
    "sweepRun" to ("io.plainround.seatwave.quorums.QuorumSweepCli" to "Lab 1 and the sweep: stale reads, latency and refusals for each R and W"),
    "healingRun" to ("io.plainround.seatwave.quorums.ReplicaHealingCli" to "Read repair, hinted handoff and Merkle-tree anti-entropy after an outage"),
    "sloppyRun" to ("io.plainround.seatwave.quorums.SloppyQuorumCli" to "Strict against sloppy quorums, and lost hints"),
    "anomaliesRun" to ("io.plainround.seatwave.quorums.QuorumAnomaliesCli" to "The histories outside the overlap guarantee"),
).forEach { (taskName, program) ->
    tasks.register<JavaExec>(taskName) {
        group = "seatwave"
        description = program.second
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = program.first
    }
}
