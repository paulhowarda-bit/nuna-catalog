plugins {
    `java-library`
}

description = "Domain types, commit loop, idempotency, rules, authorization decisions."

dependencies {
    api(project(":catalog-iceberg"))
    api(project(":catalog-store-api"))

    // The commit engine is tested on PostgreSQL through the storage interface (build-plan M3).
    testImplementation(project(":catalog-store-sql"))
    testImplementation("org.postgresql:postgresql")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
}

tasks.test {
    systemProperty("postgres.image", project.property("postgres.image")!!)
}
