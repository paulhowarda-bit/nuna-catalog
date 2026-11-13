plugins {
    `java-library`
    id("org.springframework.boot")
}

description = "The maintenance worker (D30): a separate service, an Iceberg client of the catalog with its own identity" +
    " and its own storage identity. It expires snapshots, rewrites manifests, finds orphan files, launches compaction" +
    " as Kubernetes Jobs, purges dropped objects and deletes the files the catalog lists as due."

val icebergVersion = property("iceberg.version")

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.apache.iceberg:iceberg-core")
    implementation("org.apache.iceberg:iceberg-aws:$icebergVersion")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    // The synchronous S3 client on Apache HttpClient (Iceberg's default http-client.type).
    implementation("software.amazon.awssdk:s3") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation("software.amazon.awssdk:apache-client")
    // iceberg-aws declares no SDK dependencies; its client factory loads these classes.
    listOf("sts", "kms", "glue", "dynamodb").forEach {
        runtimeOnly("software.amazon.awssdk:$it") {
            exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        }
    }

    // The worker is tested against a real catalog server on PostgreSQL and the run's S3 store (-Ps3).
    testImplementation(project(":catalog-server"))
    testImplementation(project(":catalog-tests-s3"))
    testImplementation(project(path = ":catalog-security", configuration = "testJar"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")
}

// The runnable jar gets a classifier, as the server's does; the image packs it (deploy/image/maintenance.Dockerfile).
tasks.bootJar {
    archiveClassifier = "exec"
}
tasks.jar {
    archiveClassifier = ""
}

tasks.test {
    listOf("postgres.image", "kafka.image").forEach { systemProperty(it, project.property(it)!!) }
}
