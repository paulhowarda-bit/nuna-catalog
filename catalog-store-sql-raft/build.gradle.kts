plugins {
    `java-library`
}

description = "The SQL store's CockroachDB suite (heavy). A test-only module that nothing depends on. " +
    "The cluster harness stays in catalog-store-sql's test jar."

dependencies {
    testImplementation(project(":catalog-store-sql"))
    testImplementation(project(path = ":catalog-store-sql", configuration = "testJar"))
    testImplementation(project(path = ":catalog-store-api", configuration = "testJar"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.postgresql:postgresql")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-toxiproxy")
}

tasks.test {
    listOf("postgres.image", "toxiproxy.image", "cockroachdb.image").forEach { systemProperty(it, project.property(it)!!) }
}
