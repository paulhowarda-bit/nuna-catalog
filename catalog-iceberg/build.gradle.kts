plugins {
    `java-library`
}

description = "The only module that imports iceberg-core."

dependencies {
    api("org.apache.iceberg:iceberg-core")
    // iceberg-core declares Jackson 2 at runtime scope; request parsing needs it to compile.
    // Version from the jackson-2-bom pin in gradle.properties.
    api("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
}

tasks.test {
    // GoldenTest reads the vendored spec.
    inputs.file(rootProject.file("spec/rest-catalog-open-api.yaml")).withPropertyName("spec")
        .withPathSensitivity(PathSensitivity.NONE)
    // -Pgolden.record=true rewrites the goldens in src/test/resources.
    providers.gradleProperty("golden.record").orNull?.let { systemProperty("golden.record", it) }
}
