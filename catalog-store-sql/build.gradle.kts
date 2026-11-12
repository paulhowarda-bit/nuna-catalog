plugins {
    `java-library`
}

description = "PostgreSQL and CockroachDB backend."

dependencies {
    api(project(":catalog-store-api"))
    api("org.flywaydb:flyway-database-postgresql")

    testImplementation(project(path = ":catalog-store-api", configuration = "testJar"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.postgresql:postgresql")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-toxiproxy")
}

// The cluster harness (src/test) is handed to catalog-store-sql-raft as a jar.
val testJar = tasks.register<Jar>("testJar") {
    archiveClassifier = "tests"
    from(sourceSets.test.get().output)
}
configurations.consumable("testJar") {
    outgoing.artifact(testJar)
}

tasks.test {
    listOf("postgres.image", "toxiproxy.image", "cockroachdb.image").forEach { systemProperty(it, project.property(it)!!) }
}
