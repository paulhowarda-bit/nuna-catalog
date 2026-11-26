plugins {
    `java-library`
}

description = "Test support: Testcontainers' Docker client made safe for Podman's API, which closes a connection " +
    "idle for 10 s (FreshPipeStrategy). The root build puts it on every module's test runtime path."

dependencies {
    implementation("org.testcontainers:testcontainers")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
}
