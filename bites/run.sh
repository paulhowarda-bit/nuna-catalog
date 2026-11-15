#!/usr/bin/env bash
# Runs recorded bites. For each patch: apply it, run the test it names, expect that test to fail,
# revert the patch, and check every tracked file hashes as it did before. Prints a table.
#
#   bites/run.sh                          every bite, in every folder under bites/
#   bites/run.sh bites/M4                 every bite of a milestone
#   bites/run.sh bites/M4/some-check.patch one bite
#
# A bite is a patch made with `git diff` that breaks one check. Its first lines, before
# "diff --git", name the module and the test expected to fail, as Class, Class#method or
# Class#method+method (a nested class is Outer$Inner):
#
#   Module: catalog-store-sql
#   Test: PostgresStoreSuiteTest#staleVersionAtTheSameLocationConflicts
#
# Runs the module's test task with -Pbites, so any test but CAS can be selected, heavy and overnight
# ones included. Gradle logs go to build/bites/. Run it in a worktree.
#
# Each bite gets a verdict (judge, below):
#
#   BITES    a selected test failed on its own: on an assertion, or with the code under test throwing.
#   NO BITE  the selected tests ran and passed.
#   SETUP    the run proves nothing either way: the patch did not apply or compile, no test ran, or
#            every failure came from starting the tests' fixtures (a Spring context, a container,
#            @BeforeAll), never from a test. Read the log, fix what stopped the fixtures, run it again.
#
# The last line counts them: "<n> bites, <f> not biting or not restored (<s> of them SETUP); logs in
# build/bites/". heavy.sh prints it. Exit status: 0 when every bite bites and the tree is restored;
# 1 when any bite is NO BITE or SETUP, or the tree is not restored; 2 if it cannot reach the repo
# root; 3 if a patch cannot be reverted (the tree is broken); 130 when interrupted.
#
# Gradle builds what the module's tests use from the patched tree, and only that: a bite against
# catalog-tests-load runs the server jar built with the patch in it.

main() {
    set -u
    root=$(cd "$(dirname "$0")/.." && pwd)
    cd "$root" || exit 2
    logs="$root/build/bites"
    mkdir -p "$logs"

    patches=()
    for arg in "$@"; do
        if [ -d "$arg" ]; then
            patches+=("$arg"/*.patch)
        else
            patches+=("$arg")
        fi
    done
    if [ $# -eq 0 ]; then
        patches=(bites/*/*.patch)
    fi

    trap 'revert_current; exit 130' INT TERM
    failures=0
    setups=0
    table=""
    for patch in "${patches[@]}"; do
        id=$(basename "$patch" .patch)
        module=$(header "$patch" Module)
        selector=$(header "$patch" Test)
        before=$(tree_hash)
        if [ -z "$module" ] || [ -z "$selector" ]; then
            verdict="SETUP: no Module/Test header"
        elif ! git apply --check "$patch" 2>"$logs/$id.log"; then
            verdict="SETUP: patch does not apply"
        else
            git apply "$patch"
            current="$patch"
            tests_of "$selector"
            "$root/gradlew" --console=plain -Pbites ":$module:test" "${tests[@]}" >"$logs/$id.log" 2>&1
            verdict=$(judge "$logs/$id.log" "$module")
            revert_current
        fi
        if [ "$(tree_hash)" = "$before" ]; then
            restored="restored"
        else
            restored="HASH MISMATCH"
        fi
        case "$verdict" in BITES*) [ "$restored" = "restored" ] ;; *) false ;; esac || failures=$((failures + 1))
        case "$verdict" in SETUP*) setups=$((setups + 1)) ;; esac
        table+="$(printf '%-40s %-60s %-36s %s' "$id" "$selector" "$verdict" "$restored")"$'\n'
        if [ "$restored" != "restored" ]; then
            break
        fi
    done

    printf '%-40s %-60s %-36s %s\n' "bite" "test" "verdict" "tree"
    printf '%s' "$table"
    echo "${#patches[@]} bites, $failures not biting or not restored ($setups of them SETUP); logs in build/bites/"
    [ "$failures" -eq 0 ]
}

# The value of a "Name: value" line before the first "diff --git".
header() {
    sed -n "/^diff --git/q; s/^$2: *//p" "$1" | head -n 1 | tr -d '\r'
}

# Sets tests to Gradle's --tests arguments for a selector: Class, Class#method or Class#m1+m2.
tests_of() {
    local class=${1%%#*} method
    tests=()
    if [ "$class" = "$1" ]; then
        tests=(--tests "$class")
        return
    fi
    local methods
    IFS=+ read -ra methods <<<"${1#*#}"
    for method in "${methods[@]}"; do
        tests+=(--tests "$class.$method")
    done
}

# One hash over the content of every tracked file. On Windows a file can be briefly unreadable
# ("Device or resource busy" while OneDrive or a scanner holds it); a read that fails is retried,
# so only a real content change counts as a mismatch.
tree_hash() {
    local sums try
    for try in 1 2 3 4 5; do
        if sums=$(git ls-files -z | xargs -0 sha256sum 2>/dev/null); then
            printf '%s\n' "$sums" | sha256sum | cut -d ' ' -f 1
            return
        fi
        sleep 2
    done
    echo "unreadable tree"
}

revert_current() {
    if [ -n "${current:-}" ]; then
        git apply -R "$current" || { echo "could not revert $current; the tree is broken" >&2; exit 3; }
        current=""
    fi
}

# The verdict on one bite, from its Gradle log $1 and the result files of module $2's test task:
# BITES when a selected test failed on its own, NO BITE when they all ran and passed, SETUP when
# the run proves neither (see tally). Gradle replaces the result files whenever the task runs or
# its result is reused; they are read only if this build reached the task.
judge() {
    if grep -q "Compilation failed" "$1"; then
        echo "SETUP: does not compile"
        return
    fi
    if ! grep -q "^BUILD SUCCESSFUL\|^BUILD FAILED" "$1"; then
        echo "SETUP: no Gradle verdict"
        return
    fi
    if ! grep -q "^> Task :$2:test\( \|$\)" "$1"; then
        echo "SETUP: the test task did not run"
        return
    fi
    local run setup bad
    read -r run setup bad <<<"$(tally "$root/$2/build/test-results/test")"
    if [ "$run" -eq 0 ]; then
        echo "SETUP: no test ran"
    elif [ "$bad" -gt 0 ]; then
        echo "BITES ($bad of $run failed)"
    elif [ "$setup" -gt 0 ]; then
        echo "SETUP: $setup of $run failed in setup"
    else
        echo "NO BITE ($run passed)"
    fi
}

# Prints "<tests> <failed in setup> <failed in a test>" for the result files (TEST-*.xml) in $1,
# one <testcase> each. A failure is setup's, not the test's, when the result file shows that
# starting the fixtures failed, in one of the three ways Gradle and JUnit 5 record it:
#   - the testcase is Gradle's stand-in for the class, initializationError (@BeforeAll, a class
#     extension) or executionError (@AfterAll): no test ran, or the failure came after them;
#   - Spring's test context did not load: "Failed to load ApplicationContext", and for each later
#     test of that context "ApplicationContext failure threshold (1) exceeded";
#   - Testcontainers is on the failure's stack ("at org.testcontainers."): a container, or Ryuk,
#     did not start, or Docker could not be reached, for a fixture each test makes (a field or
#     @BeforeEach). A bite breaks the catalog's code, never Testcontainers.
# Every other failure is the test's own: an assertion, or the code under test throwing, in the
# test or in a fixture made per test that calls it. A break that stops the fixtures from starting
# (a Spring context that no longer loads) is SETUP too: it shows only that the fixtures failed.
tally() {
    cat "$1"/TEST-*.xml 2>/dev/null | awk '
        /^[ \t]*<testcase / {
            run++
            failed = 0
            text = ""
            match($0, / name="[^"]*"/)
            name = substr($0, RSTART + 7, RLENGTH - 8)
            incase = $0 !~ /\/>[ \t\r]*$/
            next
        }
        incase && /^[ \t]*<(failure|error)[ \/>]/ {
            failed = 1
            collecting = 1
        }
        collecting {
            text = text $0 "\n"
        }
        collecting && /<\/failure>|<\/error>|^[ \t]*<(failure|error) [^>]*\/>/ {
            collecting = 0
        }
        incase && /^[ \t]*<\/testcase>/ {
            incase = 0
            if (!failed) {
                next
            }
            if (name == "initializationError" || name == "executionError" ||
                text ~ /Failed to load ApplicationContext|ApplicationContext failure threshold|at org\.testcontainers\./) {
                setup++
            } else {
                bad++
            }
        }
        END { print run + 0, setup + 0, bad + 0 }'
}

# Sourced (bites/test-run.sh does, to test judge), it only defines the functions.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    main "$@"
    exit
fi
