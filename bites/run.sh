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
# ones included. Gradle logs go to build/bites/. Exits 0 only if every bite bites and the tree is
# restored. Run it in a worktree.
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
        table+="$(printf '%-40s %-60s %-28s %s' "$id" "$selector" "$verdict" "$restored")"$'\n'
        if [ "$restored" != "restored" ]; then
            break
        fi
    done

    printf '%-40s %-60s %-28s %s\n' "bite" "test" "verdict" "tree"
    printf '%s' "$table"
    echo "${#patches[@]} bites, $failures not biting or not restored; logs in build/bites/"
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

# One hash over the content of every tracked file.
tree_hash() {
    git ls-files -z | xargs -0 sha256sum | sha256sum | cut -d ' ' -f 1
}

revert_current() {
    if [ -n "${current:-}" ]; then
        git apply -R "$current" || { echo "could not revert $current; the tree is broken" >&2; exit 3; }
        current=""
    fi
}

# BITES when the selected tests ran and one failed; NO BITE when they ran and passed. The counts
# come from the result files of module $2's test task, which Gradle replaces whenever the task
# runs or its result is reused; they are read only if this build reached the task.
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
    totals=$(cat "$root/$2"/build/test-results/test/TEST-*.xml 2>/dev/null | grep "<testsuite " |
        sed -E 's/.* tests="([0-9]+)" skipped="[0-9]+" failures="([0-9]+)" errors="([0-9]+)".*/\1 \2 \3/' |
        awk '{ run += $1; bad += $2 + $3 } END { print run + 0, bad + 0 }')
    run=${totals% *}
    bad=${totals#* }
    if [ "$run" -eq 0 ]; then
        echo "SETUP: no test ran"
    elif [ "$bad" -gt 0 ]; then
        echo "BITES ($bad of $run failed)"
    else
        echo "NO BITE ($run passed)"
    fi
}

main "$@"
exit
