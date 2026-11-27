plugins {
    `java-library`
}

description = "The brokerage world (D48): the demo's data generator. A live event source that books every financial" +
    " transaction through the demo kit's posting engine and sends the world's events to Kafka and SingleStore, from" +
    " where the demo's pipelines bring them into Iceberg."

dependencies {
    implementation("tools.jackson.core:jackson-databind")
    // Metadata only (namespaces, tables, views, a column, tags): the generator writes no files.
    implementation("org.apache.iceberg:iceberg-core")
    implementation("org.apache.kafka:kafka-clients")
    implementation("org.mariadb.jdbc:mariadb-java-client")
    // The CDC leg's CockroachDB custody, which speaks PostgreSQL's protocol (Main custody).
    implementation("org.postgresql:postgresql")
    runtimeOnly("org.slf4j:slf4j-simple")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    // The reconciliation views' SQL, run against the world's own rows.
    testImplementation("com.h2database:h2")
}

// The image's class path (demo/k8s/brokerage.Dockerfile): the module's jar and every library it runs on.
tasks.register<Sync>("imageLib") {
    group = "build"
    description = "Copies the generator's jar and runtime libraries to build/image/lib for the demo's image."
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("image/lib"))
}
