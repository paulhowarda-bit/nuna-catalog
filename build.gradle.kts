import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import java.nio.file.Files
import org.springframework.boot.gradle.plugin.SpringBootPlugin

// fidice catalog: Iceberg REST catalog server. Author: Paul Howard.
//
//   ./gradlew build            the fast build: every test not tagged heavy or cas (PostgreSQL only)
//   ./gradlew build -Pheavy    every test but the CAS test: the heavy pass (CLAUDE.md)
//   ./gradlew build -Pnightly  the overnight run, never part of a build: only the CAS test, on all
//                              four backends, at 1,000 rounds

plugins {
    id("org.springframework.boot") version "4.1.2" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

val heavy = providers.gradleProperty("heavy").isPresent
val nightly = providers.gradleProperty("nightly").isPresent

// OneDrive marks every new folder read-only within seconds, and Java cannot delete a read-only
// folder. In a checkout under OneDrive, Gradle then fails as soon as a task replaces its own old
// output ("Unable to delete directory"): a second test run, a result reused from the build cache,
// clean. So before each task runs, the folders under its project's build directory are made
// deletable again. OneDrive does not mark a folder twice.
// Gradle has deprecated task execution listeners, and they do not work with the configuration
// cache (off here); nothing else runs before a task's old output is removed.
if (System.getProperty("os.name").startsWith("Windows")) {
    val buildDirectories = allprojects.associate { it.path to it.layout.buildDirectory.get().asFile }
    @Suppress("DEPRECATION")
    val makeDeletable = object : TaskExecutionListener {
        override fun beforeExecute(task: Task) {
            buildDirectories.getValue(task.path.substringBeforeLast(':').ifEmpty { ":" }).walkTopDown()
                .filter { it.isDirectory && Files.getAttribute(it.toPath(), "dos:readonly") == true }
                .forEach { Files.setAttribute(it.toPath(), "dos:readonly", false) }
        }

        override fun afterExecute(task: Task, state: TaskState) {
        }
    }
    gradle.addListener(makeDeletable)
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "io.spring.dependency-management")

    group = "fidice"
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

    dependencies {
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test>().configureEach {
        // Tests tagged heavy (multi-node clusters, fault injection) run only with -Pheavy.
        // The CAS test (tagged cas) runs in no build: only overnight, on its own, with -Pnightly.
        useJUnitPlatform {
            when {
                nightly -> includeTags("cas")
                heavy -> excludeTags("cas")
                else -> excludeTags("heavy", "cas")
            }
        }
        if (nightly) {
            systemProperty("fidice.suite.cas-rounds", "1000")
            // The CAS test looks for a race, so each overnight run is a new sample: never reused.
            outputs.upToDateWhen { false }
            outputs.cacheIf { false }
        }
        // In the fast build every test of some modules is tagged out.
        failOnNoDiscoveredTests = false
        // The JVM's own default on this project's 16 GB machines; Gradle's is 512 MB.
        maxHeapSize = "4g"
    }
}
