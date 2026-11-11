plugins {
    `java-library`
    id("org.springframework.boot")
}

description = "The Spring Boot server: REST routes, config, error mapping, self-checks."

dependencies {
    api(project(":catalog-core"))
    api(project(":catalog-iceberg"))
    api(project(":catalog-store-sql"))
    api(project(":catalog-objectstore"))
    api(project(":catalog-audit"))
    api(project(":catalog-security"))
    api("org.springframework.boot:spring-boot-starter-webmvc")
    api("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation(project(path = ":catalog-security", configuration = "testJar"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:testcontainers-kafka")
}

// The runnable jar gets a classifier so other modules can still depend on the plain jar.
tasks.bootJar {
    archiveClassifier = "exec"
}
tasks.jar {
    archiveClassifier = ""
}

// The load tests, the engine matrix and demo/demo.sh run the server from its runnable jar.
configurations.consumable("execJar") {
    outgoing.artifact(tasks.bootJar)
}

tasks.test {
    listOf("postgres.image", "silo.image", "keycloak.image", "kafka.image").forEach { systemProperty(it, project.property(it)!!) }
    // IcebergImportBoundaryTest and NoKmsBoundaryTest read every module's main sources.
    inputs.files(rootProject.fileTree(rootDir) { include("*/src/main/java/**/*.java") })
        .withPropertyName("mainSources").withPathSensitivity(PathSensitivity.RELATIVE)
    val root = rootDir
    doFirst {
        // A path of this checkout, set as the task starts so it stays out of the build cache key.
        systemProperty("repo.root", root.absolutePath)
    }
}
