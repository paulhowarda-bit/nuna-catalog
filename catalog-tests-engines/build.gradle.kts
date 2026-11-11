plugins {
    `java-library`
}

description = "Nightly engine matrix: Spark, Flink, Trino, PyIceberg, DuckDB and SingleStore against the catalog, in docker compose."

// The compose stack runs the server's runnable jar, built first by this build.
val serverJar = configurations.dependencyScope("serverJar")
val serverJarFile = configurations.resolvable("serverJarFile") {
    extendsFrom(serverJar.get())
}

dependencies {
    serverJar(project(path = ":catalog-server", configuration = "execJar"))

    testImplementation(project(":catalog-server"))
    testImplementation(project(path = ":catalog-security", configuration = "testJar"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core")
}

tasks.test {
    val jar: FileCollection = serverJarFile.get()
    val compose = file("compose")
    val runDir = layout.buildDirectory.dir("engines-run")
    inputs.files(jar).withPropertyName("serverJar").withPathSensitivity(PathSensitivity.NONE)
    inputs.dir(compose).withPropertyName("compose").withPathSensitivity(PathSensitivity.RELATIVE)
    // -Pengines.keep-stack=true leaves the stack up afterwards.
    providers.gradleProperty("engines.keep-stack").orNull?.let { systemProperty("engines.keep-stack", it) }
    doFirst {
        // Paths of this checkout, set as the task starts so they stay out of the build cache key.
        systemProperty("engines.compose", compose.resolve("compose.yaml").absolutePath)
        systemProperty("engines.catalog-jar", jar.singleFile.absolutePath)
        systemProperty("engines.run-dir", runDir.get().asFile.absolutePath)
    }
}
