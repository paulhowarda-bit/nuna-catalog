plugins {
    `java-library`
}

description = "Nightly engine matrix (the demo): the catalog's Helm chart in the kind cluster on Podman (deploy/kind), with Spark, Kafka Connect, PyIceberg and SingleStore against it, and the audit table writer's chart beside it."

// The catalog image packs the server's runnable jar, built first by this build.
val serverJar = configurations.dependencyScope("serverJar")
val serverJarFile = configurations.resolvable("serverJarFile") {
    extendsFrom(serverJar.get())
}
// So does the audit table writer's image, the writer's (EngineMatrixTest#auditTables).
val auditTablesJar = configurations.dependencyScope("auditTablesJar")
val auditTablesJarFile = configurations.resolvable("auditTablesJarFile") {
    extendsFrom(auditTablesJar.get())
}

dependencies {
    serverJar(project(path = ":catalog-server", configuration = "execJar"))
    auditTablesJar(project(path = ":catalog-audit-tables", configuration = "execJar"))

    // The run's S3 store: -Ps3=silo (default), ceph or aws.
    testImplementation(project(":catalog-tests-s3"))
    testImplementation(project(":catalog-server"))
    // The maintenance worker's compaction Job path (EngineMatrixTest#compaction).
    testImplementation(project(":catalog-maintenance"))
    testImplementation(project(path = ":catalog-security", configuration = "testJar"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core")
}

tasks.test {
    val jar: FileCollection = serverJarFile.get()
    val auditJar: FileCollection = auditTablesJarFile.get()
    val engines = projectDir
    val root = rootDir
    val runDir = layout.buildDirectory.dir("engines-run")
    inputs.files(jar).withPropertyName("serverJar").withPathSensitivity(PathSensitivity.NONE)
    inputs.files(auditJar).withPropertyName("auditTablesJar").withPathSensitivity(PathSensitivity.NONE)
    inputs.dir("images").withPropertyName("images").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("k8s").withPropertyName("k8s").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.file("deploy")).withPropertyName("deploy").withPathSensitivity(PathSensitivity.RELATIVE)
    // The stack the matrix shares with the demo.
    inputs.dir(rootProject.file("demo/k8s")).withPropertyName("stack").withPathSensitivity(PathSensitivity.RELATIVE)
    listOf("postgres.image", "kafka.image").forEach { systemProperty(it, project.property(it)!!) }
    // -Pengines.cluster=<kubectl context> deploys into that existing cluster instead of kind-nuna (kind makes
    // a new kind cluster);
    // -Pengines.keep-stack=true leaves everything up afterwards; -Pengines.skip-build=true uses the images
    // already built; -Pengines.stack=<namespace> with -Pengines.token-file=<file> runs the engines against the
    // stack already up there (demo/demo.sh engines).
    listOf("engines.cluster", "engines.keep-stack", "engines.skip-build", "engines.stack", "engines.token-file").forEach { name ->
        providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
    }
    doFirst {
        // Paths of this checkout, set as the task starts so they stay out of the build cache key.
        systemProperty("engines.dir", engines.absolutePath)
        systemProperty("engines.repo-root", root.absolutePath)
        systemProperty("engines.catalog-jar", jar.singleFile.absolutePath)
        systemProperty("engines.audit-tables-jar", auditJar.singleFile.absolutePath)
        systemProperty("engines.run-dir", runDir.get().asFile.absolutePath)
    }
}
