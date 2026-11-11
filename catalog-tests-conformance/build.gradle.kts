plugins {
    `java-library`
    id("io.spring.dependency-management")
}

description = "Runs the Iceberg REST Compatibility Kit against the server, with a no-regression ratchet."

// The RCK 1.12.0 is built on JUnit 5.14.4 (platform 1.14.4); Boot 4.1.2 manages JUnit 6.
extra["junit-jupiter.version"] = "5.14.4"
val icebergVersion = property("iceberg.version")
val hadoopVersion = "3.4.3"
// The reference RESTCatalogServer runs on Jetty 12.1 (ee10) over SQLite; Boot manages neither ee10
// nor the compression modules, so the Jetty version Boot manages is named here.
val fixtureJettyVersion = dependencyManagement.importedProperties["jetty.version"]

dependencies {
    testImplementation(project(":catalog-server"))
    testImplementation(project(path = ":catalog-security", configuration = "testJar"))
    testImplementation("org.apache.iceberg:iceberg-open-api:$icebergVersion:tests")
    testImplementation("org.apache.iceberg:iceberg-open-api:$icebergVersion:test-fixtures")
    testImplementation("org.apache.iceberg:iceberg-core:$icebergVersion:tests")
    testImplementation("org.apache.iceberg:iceberg-api:$icebergVersion:tests")
    // The RCK client writes manifests and data files itself, to Silo, through S3FileIO.
    testImplementation("org.apache.iceberg:iceberg-aws:$icebergVersion")
    testImplementation("software.amazon.awssdk:s3")
    testImplementation("software.amazon.awssdk:apache-client")
    // iceberg-aws declares no SDK dependencies; its client factory loads these classes.
    testImplementation("software.amazon.awssdk:kms")
    testImplementation("software.amazon.awssdk:sts")
    testImplementation("software.amazon.awssdk:glue")
    testImplementation("software.amazon.awssdk:dynamodb")
    testImplementation("org.eclipse.jetty.ee10:jetty-ee10-servlet:$fixtureJettyVersion")
    testImplementation("org.eclipse.jetty.compression:jetty-compression-server:$fixtureJettyVersion")
    testImplementation("org.eclipse.jetty.compression:jetty-compression-gzip:$fixtureJettyVersion")
    testImplementation("org.xerial:sqlite-jdbc")
    // The RCK client calls new org.apache.hadoop.conf.Configuration().
    testImplementation("org.apache.hadoop:hadoop-common:$hadoopVersion") {
        // A second SLF4J backend would fight Spring Boot's Logback.
        exclude(group = "org.slf4j", module = "slf4j-reload4j")
        exclude(group = "ch.qos.reload4j", module = "reload4j")
    }
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")
}

tasks.test {
    // Only our harness runs here; it launches the RCK classes itself.
    include("fidice/**/*Test.class")
    listOf("postgres.image", "silo.image", "kafka.image").forEach { systemProperty(it, project.property(it)!!) }

    val reasons = file("rck-reasons.txt")
    val baseline = file("rck-baseline.txt")
    val results = layout.buildDirectory.file("rck-results.txt")
    inputs.files(reasons, baseline).withPropertyName("ratchet").withPathSensitivity(PathSensitivity.NAME_ONLY)
    outputs.file(results).withPropertyName("rckResults")
    outputs.file(layout.buildDirectory.file("fixture-diff.txt")).withPropertyName("fixtureDiff")
    // -Prck.record=true rewrites rck-baseline.txt from this run.
    providers.gradleProperty("rck.record").orNull?.let { systemProperty("rck.record", it) }
    doFirst {
        // Paths of this checkout, set as the task starts so they stay out of the build cache key.
        systemProperty("rck.reasons", reasons.absolutePath)
        systemProperty("rck.baseline", baseline.absolutePath)
        systemProperty("rck.results", results.get().asFile.absolutePath)
    }
}
