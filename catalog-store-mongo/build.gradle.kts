plugins {
    `java-library`
}

description = "MongoDB backend."

// The driver's version (mongodb.version) and the test image are pinned in gradle.properties.

dependencies {
    api(project(":catalog-store-api"))
    api("org.mongodb:mongodb-driver-sync")

    testImplementation(project(path = ":catalog-store-api", configuration = "testJar"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.testcontainers:testcontainers-toxiproxy")
}

tasks.test {
    listOf("mongo.image", "toxiproxy.image").forEach { systemProperty(it, project.property(it)!!) }
}
