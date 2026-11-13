plugins {
    `java-library`
}

description = "M8 hardening gates: load, contention, resilience and SLO runs against running servers. Every test runs overnight only (tagged overnight)."

// The release before this one, for the rolling upgrade: main as the heavy pass left it, after the rename
// to keel and before v1.x's phase 1. Its PostgreSQL schema stops at V4; this release adds V5 to V7.
val previousRelease = "2b1af36"

// The server under test runs as its own process, from the runnable jar this build just made.
val serverJar = configurations.dependencyScope("serverJar")
val serverJarFile = configurations.resolvable("serverJarFile") {
    extendsFrom(serverJar.get())
}

dependencies {
    serverJar(project(path = ":catalog-server", configuration = "execJar"))

    // The run's S3 store: -Ps3=silo (default), ceph or aws.
    testImplementation(project(":catalog-tests-s3"))
    testImplementation(project(":catalog-server"))
    testImplementation(project(path = ":catalog-security", configuration = "testJar"))
    testImplementation("org.postgresql:postgresql")
    testImplementation("software.amazon.awssdk:s3")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
}

tasks.test {
    val jar: FileCollection = serverJarFile.get()
    val work = layout.buildDirectory.dir("m8")
    val root = rootDir
    inputs.files(jar).withPropertyName("serverJar").withPathSensitivity(PathSensitivity.NONE)
    listOf("postgres.image", "kafka.image").forEach { systemProperty(it, project.property(it)!!) }
    systemProperty("m8.previous-release", previousRelease)
    doFirst {
        // Paths of this checkout, set as the task starts so they stay out of the build cache key.
        systemProperty("m8.server-jar", jar.singleFile.absolutePath)
        systemProperty("m8.work", work.get().asFile.absolutePath)
        systemProperty("m8.repo-root", root.absolutePath)
    }
}
