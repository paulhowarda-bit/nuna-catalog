plugins {
    `java-library`
}

description = "Oracle backend, including Exadata/RAC."

// The driver's version (oracle-database.version) and the test image are pinned in gradle.properties.

dependencies {
    api(project(":catalog-store-api"))
    api("com.oracle.database.jdbc:ojdbc17")
    api("org.flywaydb:flyway-database-oracle")

    testImplementation(project(path = ":catalog-store-api", configuration = "testJar"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    // A pool in the tests: a frozen log writer also blocks new logons, so stores must borrow
    // connections that already exist.
    testImplementation("com.oracle.database.jdbc:ucp17")
    testImplementation("org.testcontainers:testcontainers-oracle-free")
    testImplementation("org.testcontainers:testcontainers-toxiproxy")
}

tasks.test {
    listOf("oracle.image", "toxiproxy.image").forEach { systemProperty(it, project.property(it)!!) }
}
