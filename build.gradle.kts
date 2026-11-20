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
val bitesApply = tasks.register("bitesApply") {
    description = "Checks that every recorded bite still applies (git apply --check)."
    val root = rootDir
    val patches = fileTree("bites") { include("*/*.patch") }
    doLast {
        val all = patches.files.sorted()
        val failed = all.mapNotNull { patch ->
            val git = ProcessBuilder("git", "apply", "--check", patch.path).directory(root)
                .redirectErrorStream(true).start()
            val output = git.inputStream.bufferedReader().readText().trim()
            if (git.waitFor() == 0) null else "${patch.relativeTo(root).invariantSeparatorsPath}: $output"
        }
        if (failed.isNotEmpty()) {
            throw GradleException("${failed.size} of ${all.size} bites no longer apply; record each again " +
                "against today's code (CLAUDE.md, Bites):\n" + failed.joinToString("\n"))
        }
        logger.lifecycle("All ${all.size} bites apply.")
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
