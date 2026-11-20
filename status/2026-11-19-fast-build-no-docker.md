# 2026-11-19, the fast build needs no Docker (D44)

Paul turned Docker Desktop off for the night and asked for the container tests to move to the heavy build. Every test
class that starts a container is now tagged `heavy` at class level, 55 of them across `catalog-audit`, `catalog-core`,
`catalog-maintenance`, `catalog-objectstore`, `catalog-security`, `catalog-server`, `catalog-store-sql` and
`catalog-tests-conformance`, so `./gradlew build` runs only the tests that need nothing but Java. Gradle's tag filters
are unchanged: the fast build already left out `heavy`, and `-Pheavy`, `-Pbites` and the overnight run already ran it.
`CLAUDE.md` (Builds) and the comments in `build.gradle.kts` say so; ledger row D44 records Paul's words.

The classes were found, not guessed: `./gradlew test --continue --no-build-cache` with Docker off failed 55 classes,
and every failure was a missing Docker ("Could not find a valid Docker environment", or a Spring context that failed
for that reason). `CatalogMetricsRoutesTest` takes `@org.junit.jupiter.api.Tag` in full, since it imports
Micrometer's `Tag`.

Checked with Docker off: `./gradlew build --no-build-cache` passed in 54 s, 843 tests in 75 suites with no failure or
skip, and "All 288 bites apply".

Not covered: the moved tests did not run tonight (no Docker). They run in the next heavy build, which needs Docker on;
a landing that breaks one is now found the next morning instead of before it lands. Bites whose test is now `heavy`
still run in `bites/run.sh`, which passes `-Pbites`.
