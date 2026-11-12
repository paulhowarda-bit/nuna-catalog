plugins {
    `java-library`
}

description = "Outbox relay, Kafka publishing, change stream, archive."

dependencies {
    api(project(":catalog-core"))
    api(project(":catalog-store-api"))
    // Jackson 3, Boot's default JSON stack; Iceberg's Jackson 2 stays inside catalog-iceberg (P5).
    api("tools.jackson.core:jackson-databind")
    api("org.apache.kafka:kafka-clients")
    // The archive's Object Lock bucket; the synchronous client only, as in catalog-objectstore.
    api("software.amazon.awssdk:s3") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }

    // The run's S3 store: -Ps3=silo (default), ceph or aws.
    testImplementation(project(":catalog-tests-s3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("org.testcontainers:testcontainers-toxiproxy")
    testImplementation("org.testcontainers:testcontainers")
}

tasks.test {
    listOf("kafka.image", "toxiproxy.image").forEach { systemProperty(it, project.property(it)!!) }
}
