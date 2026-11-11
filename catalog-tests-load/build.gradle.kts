plugins {
    `java-library`
}

description = "M8 hardening gates: load, contention, resilience and SLO runs against running servers. Every test is heavy."

// The release before this one, for the rolling upgrade: main as M7 landed it. Its servers migrate
// only the shared steps (V1 to V3); this release adds PostgreSQL's V4.
val previousRelease = "75df4ff"

// The server under test runs as its own process, from the runnable jar this build just made.
val serverJar = configurations.dependencyScope("serverJar")
val serverJarFile = configurations.resolvable("serverJarFile") {
    extendsFrom(serverJar.get())
}

dependencies {
    serverJar(project(path = ":catalog-server", configuration = "execJar"))

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
    listOf("postgres.image", "silo.image", "kafka.image").forEach { systemProperty(it, project.property(it)!!) }
    systemProperty("m8.previous-release", previousRelease)
    doFirst {
        // Paths of this checkout, set as the task starts so they stay out of the build cache key.
        systemProperty("m8.server-jar", jar.singleFile.absolutePath)
        systemProperty("m8.work", work.get().asFile.absolutePath)
        systemProperty("m8.repo-root", root.absolutePath)
    }
}
