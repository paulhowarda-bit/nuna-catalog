plugins {
    `java-library`
}

description = "M8 hardening gates: load, contention, resilience and SLO runs against running servers. Every test runs overnight only (tagged overnight)."

// The release before this one, for the rolling upgrade: main as the rename to nuna landed it (backlog 59), the
// first release any install can be running. Releases before it derive other storage profile ids, since the id is
// made from "<project>-storage-profile:<name>" (backlog 117). Its PostgreSQL schema stops at V8; this release adds
// V9 to V11.
val previousRelease = "a60daba"

// The server under test runs as its own process, from the runnable jar this build just made; so does the maintenance
// worker, whose DR verifier the DR drill runs (D37).
val serverJar = configurations.dependencyScope("serverJar")
val serverJarFile = configurations.resolvable("serverJarFile") {
    extendsFrom(serverJar.get())
}
val workerJar = configurations.dependencyScope("workerJar")
val workerJarFile = configurations.resolvable("workerJarFile") {
    extendsFrom(workerJar.get())
}

dependencies {
    serverJar(project(path = ":catalog-server", configuration = "execJar"))
    workerJar(project(path = ":catalog-maintenance", configuration = "execJar"))

    // The run's S3 store: -Ps3=silo (default), ceph or aws.
    testImplementation(project(":catalog-tests-s3"))
    testImplementation(project(":catalog-server"))
    // The audit table writer's OTLP receiver, as the DR drill's archive writer (ArchiveFromLog).
    testImplementation(project(":catalog-audit-tables"))
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
    val worker: FileCollection = workerJarFile.get()
    val work = layout.buildDirectory.dir("m8")
    val root = rootDir
    inputs.files(jar).withPropertyName("serverJar").withPathSensitivity(PathSensitivity.NONE)
    inputs.files(worker).withPropertyName("workerJar").withPathSensitivity(PathSensitivity.NONE)
    listOf("postgres.image", "kafka.image").forEach { systemProperty(it, project.property(it)!!) }
    systemProperty("m8.previous-release", previousRelease)
    doFirst {
        // Paths of this checkout, set as the task starts so they stay out of the build cache key.
        systemProperty("m8.server-jar", jar.singleFile.absolutePath)
        systemProperty("m8.worker-jar", worker.singleFile.absolutePath)
        systemProperty("m8.work", work.get().asFile.absolutePath)
        systemProperty("m8.repo-root", root.absolutePath)
    }
}
