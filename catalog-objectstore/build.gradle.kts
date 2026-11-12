plugins {
    `java-library`
}

description = "Storage profiles, metadata.json I/O, credential vending."

dependencies {
    api(project(":catalog-core"))
    // The synchronous client only; its default HTTP client is apache5-client.
    api("software.amazon.awssdk:s3") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    // AssumeRole for credential vending (D21); the same HTTP client as s3.
    api("software.amazon.awssdk:sts") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }

    // The run's S3 store: -Ps3=silo (default), ceph or aws.
    testImplementation(project(":catalog-tests-s3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.testcontainers:testcontainers")
}
