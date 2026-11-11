plugins {
    `java-library`
}

description = "JWT validation, principals, trusted engines."

dependencies {
    api(project(":catalog-core"))
    // Spring Security's JWT decoder and validators (D17a). Its version comes from the Security BOM
    // that Boot 4.1.2 imports; it brings nimbus-jose-jwt, which Boot does not manage itself.
    api("org.springframework.security:spring-security-oauth2-jose")

    // Authorization is tested on PostgreSQL through the storage interface, as the commit engine is.
    testImplementation(project(":catalog-store-sql"))
    testImplementation("org.postgresql:postgresql")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
}

// The stand-in identity provider (TestIdp) is shared with the server and conformance tests.
val testJar = tasks.register<Jar>("testJar") {
    archiveClassifier = "tests"
    from(sourceSets.test.get().output)
}
configurations.consumable("testJar") {
    outgoing.artifact(testJar)
}

tasks.test {
    systemProperty("postgres.image", project.property("postgres.image")!!)
}
