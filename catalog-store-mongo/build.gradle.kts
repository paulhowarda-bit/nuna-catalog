plugins {
    `java-library`
}

description = "MongoDB backend."

// Boot 4.1.2 manages 5.8.1; 5.14.0 is current (research_notes/Build plan facts/java_platform.md F3).
extra["mongodb.version"] = "5.14.0"
val mongoImage = "mongo:8.0.34"

dependencies {
    api(project(":catalog-store-api"))
    api("org.mongodb:mongodb-driver-sync")

    testImplementation(project(path = ":catalog-store-api", configuration = "testJar"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.testcontainers:testcontainers-toxiproxy")
}

tasks.test {
    systemProperty("mongo.image", mongoImage)
    systemProperty("toxiproxy.image", project.property("toxiproxy.image")!!)
}
