import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.springframework.boot.gradle.plugin.SpringBootPlugin

// nuna catalog: Iceberg REST catalog server. Author: Paul Howard.
//
//   ./gradlew build            the fast build: every test not tagged heavy, overnight or cas, and a check
//                              that every recorded bite still applies; it needs no Docker, since every
//                              test that starts a container is tagged heavy (D44)
//   ./gradlew build -Pheavy    every test but those tagged overnight or cas, each run afresh: the build
//                              half of the heavy pass, which ./heavy.sh runs before every bite
//   ./gradlew build -Pnightly  the overnight run, never part of a build: the tests tagged overnight
//                              (load, engine matrix, per-mutation fault sweeps) and the CAS test on
//                              all four backends at 1,000 rounds
//   -Pbites                    what bites/run.sh passes: every test but CAS, so a bite can select any
//                              test, heavy and overnight ones included

plugins {
    base
    id("org.springframework.boot") version "4.1.2" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

val heavy = providers.gradleProperty("heavy").isPresent
val nightly = providers.gradleProperty("nightly").isPresent
val bites = providers.gradleProperty("bites").isPresent

// Every recorded bite (bites/*/*.patch) must still apply to the tree. A change that moves the code a bite
// breaks fails here, in the branch that moved it, which records the bite again (CLAUDE.md, Bites); before, it
// surfaced only in the next heavy pass. Each patch is checked on its own, as bites/run.sh applies it.
// Applying is not biting: only bites/run.sh shows a bite still makes its test fail.
// Whether a patch applies depends only on its bytes and those of the files it touches, so a bite that applied once
// with the same patch and the same files applies again: it leaves a marker named by a hash of those bytes in the
// Gradle user home, shared by every worktree, and is checked again only when one of them changes. Checking all 571
// bites, one git process each, took 1 to 2 minutes of every fast build (backlog 176).
val bitesApply = tasks.register("bitesApply") {
    description = "Checks that every recorded bite still applies (git apply --check), skipping each bite that " +
        "applied before with the same patch and the same files."
    val root = rootDir
    val patches = fileTree("bites") { include("*/*.patch") }
    val applied = gradle.gradleUserHomeDir.resolve("nuna/bites-applied")
    doLast {
        val all = patches.files.sorted()
        val touches = Regex("""^diff --git a/(\S+) b/(\S+)""", RegexOption.MULTILINE)
        fun fingerprint(patch: File): String {
            val sha = java.security.MessageDigest.getInstance("SHA-256")
            val bytes = patch.readBytes()
            sha.update(bytes)
            touches.findAll(String(bytes, Charsets.UTF_8)).flatMap { sequenceOf(it.groupValues[1], it.groupValues[2]) }
                .distinct().sorted().forEach { path ->
                    sha.update(0.toByte())
                    sha.update(path.toByteArray(Charsets.UTF_8))
                    val file = root.resolve(path)
                    if (file.isFile) {
                        sha.update(1.toByte())
                        sha.update(file.readBytes())
                    } else {
                        sha.update(2.toByte())
                    }
                }
            return sha.digest().joinToString("") { "%02x".format(it) }
        }
        var checked = 0
        val failed = all.mapNotNull { patch ->
            val marker = applied.resolve(fingerprint(patch))
            if (marker.isFile) return@mapNotNull null
            checked++
            val git = ProcessBuilder("git", "apply", "--check", patch.path).directory(root)
                .redirectErrorStream(true).start()
            val output = git.inputStream.bufferedReader().readText().trim()
            if (git.waitFor() == 0) {
                marker.parentFile.mkdirs()
                marker.createNewFile()
                null
            } else {
                "${patch.relativeTo(root).invariantSeparatorsPath}: $output"
            }
        }
        if (failed.isNotEmpty()) {
            throw GradleException("${failed.size} of ${all.size} bites no longer apply; record each again " +
                "against today's code (CLAUDE.md, Bites):\n" + failed.joinToString("\n"))
        }
        logger.lifecycle("All ${all.size} bites apply ($checked checked; the others applied before as they are).")
    }
}
tasks.named("check") {
    dependsOn(bitesApply)
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "io.spring.dependency-management")

    group = "nuna"
    version = "0.1.0-SNAPSHOT"

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    // Boot's BOM supplies the library versions. A version it manages is overridden by a property of
    // the BOM's own name: in gradle.properties for every module, or as `extra` in one module.
    // Boot's BOM is imported last, so where the two BOMs name the same thing Boot's wins.
    configure<DependencyManagementExtension> {
        imports {
            mavenBom("software.amazon.awssdk:bom:${property("aws-sdk.version")}")
            mavenBom(SpringBootPlugin.BOM_COORDINATES)
        }
        dependencies {
            dependency("org.apache.iceberg:iceberg-core:${property("iceberg.version")}")
        }
    }

    // Dependency convergence: on the compile and runtime classpaths, every library must resolve to
    // one version that all its users asked for, after the BOM's versions are applied; a
    // disagreement fails the build instead of being settled silently. Test-only libraries are not
    // held to this (hadoop-common's tree, in catalog-tests-conformance, does not converge).
    listOf("compileClasspath", "runtimeClasspath").forEach {
        configurations.named(it) {
            resolutionStrategy.failOnVersionConflict()
        }
    }
    // Compiling resolves the compile classpath; this resolves the runtime one in every module.
    val dependencyConvergence = tasks.register("dependencyConvergence") {
        val runtimeClasspath = configurations.named("runtimeClasspath")
        doLast {
            runtimeClasspath.get().incoming.resolutionResult.rootComponent.get()
        }
    }
    tasks.named("check") {
        dependsOn(dependencyConvergence)
    }

    // OTLP export goes over the JDK's HttpClient (opentelemetry-exporter-sender-jdk, which the server and the worker
    // add), never OkHttp: work's mirror then needs neither OkHttp nor the Kotlin standard library it brings (PLT-6).
    configurations.configureEach {
        exclude(group = "io.opentelemetry", module = "opentelemetry-exporter-sender-okhttp")
    }

    dependencies {
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 25
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test>().configureEach {
        // Tests tagged heavy (every test that starts a container, D44) run only with -Pheavy. Tests
        // tagged overnight (load, engine matrix, per-mutation fault sweeps) and the CAS test (tagged
        // cas) run in no build: only overnight, with -Pnightly (Paul, 2026-11-11).
        useJUnitPlatform {
            when {
                nightly -> includeTags("cas", "overnight")
                bites -> excludeTags("cas")
                heavy -> excludeTags("cas", "overnight")
                else -> excludeTags("heavy", "cas", "overnight")
            }
        }
        if (nightly) {
            systemProperty("nuna.suite.cas-rounds", "1000")
        }
        if (nightly || heavy) {
            // The CAS test and the heavy tier's race and fault tests look for a race, and the load runs
            // measure, so each run is a new sample: never a result reused from an earlier run with the same
            // inputs, from this checkout or from the build cache every worktree shares.
            outputs.upToDateWhen { false }
            outputs.cacheIf { false }
        }
        if (nightly || heavy || bites) {
            // A test task past this has hung: Gradle stops it and it fails, and with --continue (the overnight run's
            // legs, nightly.sh) the other modules still run, where one hung test would have held its leg, or a bite
            // the rest of the bites, until the run's deadline. The longest seen: 16 min for a heavy module
            // (catalog-server), 66 min for a nightly one (catalog-maintenance, 2026-11-21, with Windows paging the
            // Docker VM out); a bite runs one class of either (backlog 164).
            timeout.set(java.time.Duration.ofMinutes(if (heavy) 60 else 120))
        }
        // The one S3 store every test uses (catalog-tests-s3, TestS3): -Ps3=silo, ceph or aws.
        listOf("s3", "silo.image", "ceph.image").forEach { systemProperty(it, project.property(it)!!) }
        systemProperty("nuna.aws.profile", providers.gradleProperty("nuna.aws.profile").getOrElse(""))
        // In the fast build every test of some modules is tagged out.
        failOnNoDiscoveredTests = false
        // What the test JVMs had under Maven on this 16 GB machine (the JVM's default, a quarter
        // of memory); Gradle's default is 512 MB.
        maxHeapSize = "4g"
    }
}
