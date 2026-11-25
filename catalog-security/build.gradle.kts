plugins {
    `java-library`
}

description = "JWT validation, principals, trusted engines."

dependencies {
    api(project(":catalog-core"))
    // Spring Security's JWT decoder and validators (D17a). Its version comes from the Security BOM
    // that Boot 4.1.2 imports; it brings nimbus-jose-jwt, which Boot does not manage itself.
    api("org.springframework.security:spring-security-oauth2-jose")
    // Trino's own SQL parser (Apache-2.0): a row filter or column mask is parsed as Trino parses it when it applies
    // the rule, when the rule is set (D20, backlog 244). Its version is in gradle.properties. It brings Guava 33.6.0,
    // ANTLR's runtime 4.13.2 and Trino's grammar; Guava's error_prone_annotations (2.47.0, annotations only) is left
    // out, so the 2.49.0 Caffeine brings through Iceberg is the one version on every class path.
    implementation("io.trino:trino-parser:${property("trino-parser.version")}") {
        exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    }

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
