plugins {
    `java-library`
}

description = "Nightly engine matrix (the demo): the catalog's Helm chart in a kind cluster, with Spark, Kafka Connect, PyIceberg and SingleStore against it."

// The catalog image packs the server's runnable jar, built first by this build.
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
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core")
}

tasks.test {
    val jar: FileCollection = serverJarFile.get()
    val engines = projectDir
    val root = rootDir
    val runDir = layout.buildDirectory.dir("engines-run")
    inputs.files(jar).withPropertyName("serverJar").withPathSensitivity(PathSensitivity.NONE)
    inputs.dir("images").withPropertyName("images").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("k8s").withPropertyName("k8s").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.file("deploy")).withPropertyName("deploy").withPathSensitivity(PathSensitivity.RELATIVE)
    listOf("postgres.image", "kafka.image").forEach { systemProperty(it, project.property(it)!!) }
    // -Pengines.cluster=<kubectl context> deploys into that existing cluster instead of a new kind cluster;
    // -Pengines.keep-stack=true leaves everything up afterwards; -Pengines.skip-build=true uses the images
    // already built.
    listOf("engines.cluster", "engines.keep-stack", "engines.skip-build").forEach { name ->
        providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
    }
    doFirst {
        // Paths of this checkout, set as the task starts so they stay out of the build cache key.
        systemProperty("engines.dir", engines.absolutePath)
        systemProperty("engines.repo-root", root.absolutePath)
        systemProperty("engines.catalog-jar", jar.singleFile.absolutePath)
        systemProperty("engines.run-dir", runDir.get().asFile.absolutePath)
    }
}
