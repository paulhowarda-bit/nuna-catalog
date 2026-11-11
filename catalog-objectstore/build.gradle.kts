plugins {
    `java-library`
}

description = "Storage profiles, metadata.json I/O, credential vending."

// Two S3 implementations, neither of them AWS's: Ceph RGW (F7) and Silo, the maintained MinIO fork
// (F5, 5.14), whose image gradle.properties pins.
val cephImage = "ghcr.io/clyso/ceph-test:v20.2.5"

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

    testImplementation("software.amazon.awssdk:iam") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    testImplementation("software.amazon.awssdk:sso") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    testImplementation("software.amazon.awssdk:ssooidc") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.testcontainers:testcontainers")
}

tasks.test {
    systemProperty("ceph.image", cephImage)
    systemProperty("silo.image", project.property("silo.image")!!)
    // The AWS CLI profile AwsVendingTest signs in through (-Pfidice.aws.profile=...); empty skips
    // it. Short-lived SSO credentials only: the test never reads, prints or stores them.
    systemProperty("fidice.aws.profile", providers.gradleProperty("fidice.aws.profile").getOrElse(""))
}
