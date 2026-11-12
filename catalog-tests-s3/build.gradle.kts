plugins {
    `java-library`
}

description = "Test support: the one S3 store every test uses, chosen by -Ps3=silo|ceph|aws."

dependencies {
    // The synchronous clients only, as in catalog-objectstore; their HTTP client is apache5-client.
    api("software.amazon.awssdk:s3") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation("software.amazon.awssdk:sts") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation("software.amazon.awssdk:iam") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    // Sign-in through an `aws login` / SSO CLI profile when s3=aws.
    runtimeOnly("software.amazon.awssdk:sso") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    runtimeOnly("software.amazon.awssdk:ssooidc") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation("org.testcontainers:testcontainers")
}
