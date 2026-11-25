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
    // Named, so a profile's CA bundle can be given to it (CaBundle); the SDK otherwise picks it at runtime.
    implementation("software.amazon.awssdk:apache5-client")

    // The run's S3 store: -Ps3=silo (default), ceph or aws.
    testImplementation(project(":catalog-tests-s3"))
    // A worker role of the test's own, with no policy of its own, on AWS (DeletePolicyAwsTest, backlog 256).
    testImplementation("software.amazon.awssdk:iam") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.testcontainers:testcontainers")
}
