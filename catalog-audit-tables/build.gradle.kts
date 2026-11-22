import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension

plugins {
    `java-library`
    id("org.springframework.boot")
}

description = "The queryable audit tables' writer (D23): a separate service with its own principal, an Iceberg client" +
    " of the catalog that writes every record of the audit topic to the table events in the audit catalog."

// Iceberg 1.12, Parquet, Hadoop and Kafka ask for different compression libraries; Iceberg's own choices win, as in
// singlestore-change-feed, and Parquet 1.17's zstd-jni over the older one Kafka's client asks for.
configure<DependencyManagementExtension> {
    dependencies {
        dependency("io.airlift:aircompressor:2.0.3")
        dependency("org.xerial.snappy:snappy-java:1.1.10.7")
        dependency("com.github.luben:zstd-jni:1.5.7-3")
    }
}

val icebergVersion = property("iceberg.version")
// iceberg-parquet needs Hadoop's Configuration; the shaded client jars carry it without Hadoop's tree.
val hadoopVersion = "3.4.3"

dependencies {
    // The event codec and the row mapping (AuditRows); its Kafka client reads the topic.
    implementation(project(":catalog-audit"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.apache.iceberg:iceberg-core")
    implementation("org.apache.iceberg:iceberg-data:$icebergVersion")
    implementation("org.apache.iceberg:iceberg-parquet:$icebergVersion")
    // The feed's reader (EventsReader) names Parquet's file schema when it builds a row reader; iceberg-parquet does
    // not export it. The version iceberg-parquet 1.12 brings.
    implementation("org.apache.parquet:parquet-column:1.17.1")
    implementation("org.apache.iceberg:iceberg-aws:$icebergVersion")
    // The synchronous S3 client on Apache HttpClient (Iceberg's default http-client.type).
    implementation("software.amazon.awssdk:s3") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation("software.amazon.awssdk:apache-client")
    // iceberg-aws declares no SDK dependencies; its client factory loads these classes.
    listOf("sts", "kms", "glue", "dynamodb").forEach {
        runtimeOnly("software.amazon.awssdk:$it") {
            exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        }
    }
    implementation("org.apache.hadoop:hadoop-client-api:$hadoopVersion")
    runtimeOnly("org.apache.hadoop:hadoop-client-runtime:$hadoopVersion")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    // The heavy test (AuditTablesTest) runs the writer against a real catalog server on PostgreSQL, Kafka and the
    // run's S3 store (-Ps3).
    testImplementation(project(":catalog-server"))
    testImplementation(project(":catalog-tests-s3"))
    testImplementation(project(path = ":catalog-security", configuration = "testJar"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")
}

// The runnable jar gets a classifier, as the server's and the worker's do; the image packs it
// (deploy/image/audit-tables.Dockerfile).
tasks.bootJar {
    archiveClassifier = "exec"
}
tasks.jar {
    archiveClassifier = ""
}

// The engine matrix builds the writer's image from the runnable jar (EngineMatrixTest#auditTables).
configurations.consumable("execJar") {
    outgoing.artifact(tasks.bootJar)
}

tasks.test {
    listOf("postgres.image", "kafka.image").forEach { systemProperty(it, project.property(it)!!) }
}
