// Everything resolves from Maven Central alone, plugins included: work has only a Maven-format
// mirror, and no access to Gradle's plugin portal. Only the Gradle distribution itself comes from
// somewhere else (gradle/wrapper/gradle-wrapper.properties).
pluginManagement {
    repositories {
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "nuna-catalog"

include(
    "catalog-tests-s3",
    "catalog-core",
    "catalog-iceberg",
    "catalog-store-api",
    "catalog-store-sql",
    "catalog-store-sql-raft",
    "catalog-store-oracle",
    "catalog-store-mongo",
    "catalog-objectstore",
    "catalog-security",
    "catalog-audit",
    "catalog-server",
    "catalog-tests-load",
    "catalog-tests-conformance",
    "catalog-tests-engines",
    "singlestore-change-feed",
    "catalog-maintenance",
    "catalog-audit-tables",
)
