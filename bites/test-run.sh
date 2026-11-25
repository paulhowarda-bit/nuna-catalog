#!/usr/bin/env bash
# Checks run.sh's verdicts (judge and tally) without Gradle or Docker. Each case in bites/test-run/
# holds result files as Gradle 9 and JUnit 5 wrote them on 2026-11-14 (stack traces cut to three
# frames an exception, system-out dropped), and gets the verdict judge must give them. The last
# cases need no result files, only a Gradle log. Exits 0 when every case gets its verdict. Run it
# after changing judge or tally.
#
#   bites/test-run.sh
#
# Where the result files came from:
#   assertion           M4/default-check-never-applies, run as run.sh runs it: a failed assertion
#   context             D13/default-warehouse-ignored with Testcontainers' Ryuk image unreachable
#                       (TESTCONTAINERS_RYUK_CONTAINER_IMAGE=localhost:1/nuna-no-such-ryuk:0):
#                       "Failed to load ApplicationContext", then the failure threshold
#   context-from-code   M8/store-skips-postgresql-steps: the patch itself stops the context loading
#                       (journal_extend does not exist), which proves no more than the case above
#   container-per-test  M8/reconcile-takes-unproven-file with Ryuk unreachable: each test's Harness
#                       starts PostgreSQL in a field, so each test fails with Testcontainers on the stack
#   passed              a class of the fast build
#   jvm-died            passed's, standing for what a test JVM that died left behind (backlog 226)
#   the others          a scratch class of probes, since deleted: @BeforeAll failing, alone and before
#                       a @Nested class (initializationError), @AfterAll failing (executionError), a
#                       field initializer and @BeforeEach throwing, a static initializer throwing, and
#                       an assertion, a NumberFormatException and a pass in test methods

set -u
here=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=run.sh
source "$here/run.sh"
root=$(mktemp -d)
trap 'rm -rf "$root"' EXIT
wrong=0

# check <case> <expected verdict> [<Gradle log line>...]: judges the result files in test-run/<case>/
# as module <case>'s. Without log lines, the log is that of a build that ran the test task and failed.
check() {
    local case=$1 expected=$2 verdict
    shift 2
    mkdir -p "$root/$case/build/test-results/test"
    if [ -d "$here/test-run/$case" ]; then
        cp "$here/test-run/$case"/TEST-*.xml "$root/$case/build/test-results/test/"
    fi
    if [ $# -eq 0 ]; then
        set -- "> Task :$case:test FAILED" "BUILD FAILED in 1m 2s"
    fi
    printf '%s\n' "$@" >"$root/$case.log"
    verdict=$(judge "$root/$case.log" "$case")
    if [ "$verdict" = "$expected" ]; then
        printf 'ok     %-20s %s\n' "$case" "$verdict"
    else
        printf 'WRONG  %-20s %s, expected %s\n' "$case" "$verdict" "$expected"
        wrong=$((wrong + 1))
    fi
}

# A test's own failure bites, wherever in the test it happens.
check assertion "BITES (1 of 1 failed)"
check code-throws "BITES (2 of 3 failed)"
check per-test-fixture "BITES (2 of 2 failed)"
check static-init "BITES (2 of 2 failed)"
# Fixtures that did not start prove nothing.
check before-all "SETUP: 2 of 2 failed in setup"
check after-all "SETUP: 1 of 2 failed in setup"
check context "SETUP: 2 of 2 failed in setup"
check context-from-code "SETUP: 1 of 1 failed in setup"
check container-per-test "SETUP: 2 of 2 failed in setup"
# One test failing on its own is enough.
check setup-and-assertion "BITES (1 of 2 failed)"
check passed "NO BITE (3 passed)" "> Task :passed:test" "BUILD SUCCESSFUL in 9s"
# The log alone decides these.
check no-results "SETUP: no test ran"
check not-compiled "SETUP: does not compile" "> Task :not-compiled:compileJava FAILED" \
    "Compilation failed; see the compiler output below." "BUILD FAILED in 4s"
check no-verdict "SETUP: no Gradle verdict" "> Task :no-verdict:test"
check task-not-run "SETUP: the test task did not run" "> Task :other:compileTestJava FAILED" "BUILD FAILED in 3s"
# A test JVM that died, whatever result files it left (backlog 226: one that passed).
check jvm-died "SETUP: the test JVM died" "> Task :jvm-died:test" "" "Gradle Test Executor 310 FAILED" \
    "    org.gradle.api.internal.tasks.testing.TestSuiteExecutionException at SuiteTestDefinitionProcessor.java:65" \
    "        Caused by: java.lang.OutOfMemoryError at LifecycleRoutes.java:429" "" \
    "> Task :jvm-died:test FAILED" "BUILD FAILED in 47m 49s"

echo "$wrong wrong"
[ "$wrong" -eq 0 ]
