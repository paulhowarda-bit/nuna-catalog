import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension

plugins {
    `java-library`
}

// Iceberg 1.12, Parquet and Hadoop ask for different compression libraries; Iceberg's own choices win.
configure<DependencyManagementExtension> {
    dependencies {
        dependency("io.airlift:aircompressor:2.0.3")
        dependency("org.xerial.snappy:snappy-java:1.1.10.7")
    }
}

description = "SingleStore to Iceberg: a connector pipeline that reads a table's change feed (OBSERVE) and commits it to an Iceberg table through the catalog."

// SingleStore 10.9 runs connector pipelines on Kafka Connect 3.9.1 and supplies it; Boot manages Kafka 4.
val connectVersion = "3.9.1"
val icebergVersion = property("iceberg.version")
// iceberg-parquet needs Hadoop's Configuration; the shaded client jars carry it without Hadoop's tree.
val hadoopVersion = "3.4.3"

// The loader is the only code SingleStore's class path sees. It loads everything else from lib/ inside
// the connector jar, so the feed's libraries never meet the copies SingleStore 10.9.0 keeps on that class
// path (AWS SDK 2.25.3, Jackson 2.22.3, Debezium, Kafka Connect and more).
val loader = sourceSets.create("loader")

dependencies {
    compileOnly("org.apache.kafka:connect-api:$connectVersion")
    "loaderCompileOnly"("org.apache.kafka:connect-api:$connectVersion")

    implementation("org.apache.iceberg:iceberg-core")
    implementation("org.apache.iceberg:iceberg-data:$icebergVersion")
    implementation("org.apache.iceberg:iceberg-parquet:$icebergVersion")
    implementation("org.apache.iceberg:iceberg-aws:$icebergVersion")
    implementation("software.amazon.awssdk:s3") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    // iceberg-aws declares no SDK dependencies; its client factory loads these classes.
    listOf("sts", "kms", "glue", "dynamodb").forEach {
        runtimeOnly("software.amazon.awssdk:$it") {
            exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        }
    }
    implementation("org.apache.hadoop:hadoop-client-api:$hadoopVersion")
    runtimeOnly("org.apache.hadoop:hadoop-client-runtime:$hadoopVersion")
    implementation("org.mariadb.jdbc:mariadb-java-client")

    implementation("org.slf4j:slf4j-api")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation(loader.output)
    testImplementation("org.apache.kafka:connect-api:$connectVersion")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.testcontainers:testcontainers")
    testRuntimeOnly("org.slf4j:slf4j-simple")
}

// What goes into SingleStore's lib-extractor-connect: the loader at the top, the feed and its libraries under lib/.
val connectorJar = tasks.register<Jar>("connectorJar") {
    group = "build"
    description = "The connector jar SingleStore loads: the loader, with the change feed and its libraries under lib/."
    archiveFileName = "fidice-changefeed-connector.jar"
    from(loader.output)
    into("lib") {
        from(tasks.jar)
        from(configurations.runtimeClasspath)
    }
}
tasks.named("assemble") {
    dependsOn(connectorJar)
}
