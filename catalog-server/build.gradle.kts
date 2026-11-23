import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension

plugins {
    `java-library`
    id("org.springframework.boot")
}

description = "The Spring Boot server: REST routes, config, error mapping, self-checks."

// The audit table reader brings Iceberg's Parquet and Hadoop beside the server's Kafka client; their compression
// libraries are pinned as the audit table writer pins them (catalog-audit-tables/build.gradle.kts).
configure<DependencyManagementExtension> {
    dependencies {
        dependency("io.airlift:aircompressor:2.0.3")
        dependency("org.xerial.snappy:snappy-java:1.1.10.7")
        dependency("com.github.luben:zstd-jni:1.5.7-3")
    }
}

dependencies {
    api(project(":catalog-core"))
    api(project(":catalog-iceberg"))
    // One backend per deployment, chosen by nuna.database.backend (StoreWiring).
    api(project(":catalog-store-sql"))
    api(project(":catalog-store-oracle"))
    api(project(":catalog-store-mongo"))
    api(project(":catalog-objectstore"))
    api(project(":catalog-audit"))
    api(project(":catalog-security"))
    // The console's audit feed reads the audit table events through the writer's own reader (EventsReader; D23,
    // amended 2026-11-22: "Yes, link them"). No server code imports Iceberg. The writer's KMS client stays out: the
    // server never calls KMS (D26; NoKmsBoundaryTest). The reader is handed its S3 client and a refusing asynchronous
    // one, so Iceberg never builds a client from its AWS client factory, whose class needs the KMS SDK (backlog 148;
    // EventsReaderOnTheServerTest reads a table on this classpath).
    implementation(project(":catalog-audit-tables")) {
        exclude(group = "software.amazon.awssdk", module = "kms")
    }
    api("org.springframework.boot:spring-boot-starter-webmvc")
    api("org.springframework.boot:spring-boot-starter-jdbc")
    // Metrics, traces and logs over OTLP when an endpoint is set (docs/observability/README.md); the root build
    // swaps the exporter's OkHttp sender for the JDK's.
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:" +
        property("opentelemetry-logback-appender.version"))
    runtimeOnly("io.opentelemetry:opentelemetry-exporter-sender-jdk")
    runtimeOnly("org.postgresql:postgresql")

    // The run's S3 store: -Ps3=silo (default), ceph or aws.
    testImplementation(project(":catalog-tests-s3"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.opentelemetry.proto:opentelemetry-proto:${property("opentelemetry-proto.version")}")
    // Micrometer's OpenTelemetry bridge, on the runtime classpath through the starter; ObservedTest builds one.
    testImplementation("io.micrometer:micrometer-tracing-bridge-otel")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-oracle-free")
    // The link OracleBackendTest and MongodbBackendTest cut, the store tests' proxy (backlog 70).
    testImplementation("org.testcontainers:testcontainers-toxiproxy")
    testImplementation(project(path = ":catalog-security", configuration = "testJar"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:testcontainers-kafka")
    // The console's end-to-end tests drive Chrome in a container (P8); the console itself has no third-party code.
    testImplementation("org.testcontainers:testcontainers-selenium")
    testImplementation("org.seleniumhq.selenium:selenium-remote-driver")
    testImplementation("org.seleniumhq.selenium:selenium-chrome-driver")
    testImplementation("org.seleniumhq.selenium:selenium-support")
}

// The runnable jar gets a classifier so other modules can still depend on the plain jar.
tasks.bootJar {
    archiveClassifier = "exec"
}
tasks.jar {
    archiveClassifier = ""
}

// The OTLP receiver (telemetry.OtlpReceiver) is shared with the worker's tests.
val testJar = tasks.register<Jar>("testJar") {
    archiveClassifier = "tests"
    from(sourceSets.test.get().output)
}
configurations.consumable("testJar") {
    outgoing.artifact(testJar)
}

// The load tests, the engine matrix and demo/demo.sh run the server from its runnable jar.
configurations.consumable("execJar") {
    outgoing.artifact(tasks.bootJar)
}

tasks.test {
    listOf("postgres.image", "keycloak.image", "kafka.image", "oracle.image", "mongo.image", "chrome.image",
        "toxiproxy.image")
        .forEach { systemProperty(it, project.property(it)!!) }
    // IcebergImportBoundaryTest and NoKmsBoundaryTest read every module's main sources.
    inputs.files(rootProject.fileTree(rootDir) { include("*/src/main/java/**/*.java") })
        .withPropertyName("mainSources").withPathSensitivity(PathSensitivity.RELATIVE).ignoreEmptyDirectories()
    val root = rootDir
    doFirst {
        // A path of this checkout, set as the task starts so it stays out of the build cache key.
        systemProperty("repo.root", root.absolutePath)
    }
}
