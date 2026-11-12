plugins {
    `java-library`
}

description = "The narrow storage interface and the shared invariant suite."

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
}

// The shared suite (src/test) is handed to each backend as a jar; each backend extends it.
val testJar = tasks.register<Jar>("testJar") {
    archiveClassifier = "tests"
    from(sourceSets.test.get().output)
}
configurations.consumable("testJar") {
    outgoing.artifact(testJar)
}
