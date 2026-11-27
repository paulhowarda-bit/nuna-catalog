import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension

plugins {
    `java-library`
}

// Iceberg 1.12, Parquet, Hadoop and Kafka ask for different compression libraries; Iceberg's own choices win, as in
// singlestore-change-feed and catalog-audit-tables, and Parquet 1.17's zstd-jni over the older one Kafka's client asks for.
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

description = "The brokerage world (D48): the demo's data generator. A live event source that books every financial" +
    " transaction through the demo kit's posting engine and sends the world's events to Kafka and SingleStore, from" +
    " where the demo's pipelines bring them into Iceberg."

dependencies {
    implementation("tools.jackson.core:jackson-databind")
    // Metadata (namespaces, tables, views, a column, tags) through an in-memory FileIO, and one writer of files: the
    // month-end close (item 153), Parquet through S3 with vended credentials, in one multi-table commit.
    implementation("org.apache.iceberg:iceberg-core")
    implementation("org.apache.iceberg:iceberg-data:$icebergVersion")
    implementation("org.apache.iceberg:iceberg-parquet:$icebergVersion")
    // GenericParquetWriter::create takes Parquet's MessageType.
    implementation("org.apache.parquet:parquet-column:1.17.1")
    implementation("org.apache.iceberg:iceberg-aws:$icebergVersion")
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
    implementation("org.apache.kafka:kafka-clients")
    implementation("org.mariadb.jdbc:mariadb-java-client")
    // The CDC leg's CockroachDB custody, which speaks PostgreSQL's protocol, and its PostgreSQL backoffice (Main cdc).
    implementation("org.postgresql:postgresql")
    // The CDC leg's Oracle treasury (Main cdc, item 152).
    implementation("com.oracle.database.jdbc:ojdbc17")
    runtimeOnly("org.slf4j:slf4j-simple")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    // The reconciliation views' SQL, run against the world's own rows.
    testImplementation("com.h2database:h2")
    // The month-end close's multi-table commit through RESTCatalog, served by Iceberg's own adapter over an in-memory
    // catalog (POST /v1/{prefix}/transactions/commit as the client sends it).
    testImplementation("org.apache.iceberg:iceberg-open-api:$icebergVersion:test-fixtures")
    testImplementation("org.apache.iceberg:iceberg-core:$icebergVersion:tests")
}

// The image's class path (demo/k8s/brokerage.Dockerfile): the module's jar and every library it runs on.
tasks.register<Sync>("imageLib") {
    group = "build"
    description = "Copies the generator's jar and runtime libraries to build/image/lib for the demo's image."
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("image/lib"))
}
