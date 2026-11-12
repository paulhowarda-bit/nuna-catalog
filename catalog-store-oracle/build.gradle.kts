plugins {
    `java-library`
}

description = "Oracle backend, including Exadata/RAC."

// 23.26.4.0.0 is the first ojdbc17 certified for JDK 25; Boot 4.1.2 manages 23.26.3.0.0
// (research_notes/Build plan facts/java_platform.md).
extra["oracle-database.version"] = "23.26.4.0.0"
// Oracle AI Database Free 23.26.3, slim (fewer components) and faststart (pre-created database).
val oracleImage = "gvenzl/oracle-free:23.26.3-slim-faststart"

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
    systemProperty("oracle.image", oracleImage)
    systemProperty("toxiproxy.image", project.property("toxiproxy.image")!!)
}
