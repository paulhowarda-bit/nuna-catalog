#!/usr/bin/env bash
# Runs recorded bites. For each patch: apply it, run the test it names, expect that test to fail,
# revert the patch, and check every tracked file hashes as it did before. Prints a table.
#
#   bites/run.sh bites/M4                 every bite of a milestone
#   bites/run.sh bites/M4/some-check.patch one bite
#
# A bite is a patch made with `git diff` that breaks one check. Its first lines, before
# "diff --git", name the Maven module and the surefire -Dtest selector expected to fail:
#
#   Module: catalog-store-sql
#   Test: PostgresStoreSuiteTest#staleVersionAtTheSameLocationConflicts
#
# Runs with -Pheavy, so tests tagged heavy can be selected. Needs JAVA_HOME. Maven logs go to
# target/bites/. Exits 0 only if every bite bites and the tree is restored. Run it in a worktree.
#
# Runs to verify, not test: catalog-tests-load copies the server's exec jar, and below package the
# reactor has none, so the copy silently takes ~/.m2's jar, built without the patch.

main() {
    set -u
    root=$(cd "$(dirname "$0")/.." && pwd)
    cd "$root" || exit 2
    logs="$root/target/bites"
    mkdir -p "$logs"

    patches=()
    for arg in "$@"; do
        if [ -d "$arg" ]; then
            patches+=("$arg"/*.patch)
        else
            patches+=("$arg")
        fi
    done
    if [ ${#patches[@]} -eq 0 ]; then
        echo "usage: bites/run.sh <milestone dir | patch>..." >&2
        exit 2
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
            "$root/mvnw" -B -Pheavy -pl "$module" -am verify -Dtest="$selector" \
                -Dsurefire.failIfNoSpecifiedTests=false >"$logs/$id.log" 2>&1
            verdict=$(judge "$logs/$id.log")
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
    echo "${#patches[@]} bites, $failures not biting or not restored; logs in target/bites/"
    [ "$failures" -eq 0 ]
}

# The value of a "Name: value" line before the first "diff --git".
header() {
    sed -n "/^diff --git/q; s/^$2: *//p" "$1" | head -n 1 | tr -d '\r'
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

# BITES when the selected tests ran and one failed; NO BITE when they ran and passed.
judge() {
    if grep -q "COMPILATION ERROR" "$1"; then
        echo "SETUP: does not compile"
        return
    fi
    if ! grep -q "BUILD SUCCESS\|BUILD FAILURE" "$1"; then
        echo "SETUP: no Maven verdict"
        return
    fi
    # Module summaries ("Tests run: 3, Failures: 1, Errors: 0, Skipped: 0" with no "in <class>").
    totals=$(grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$" "$1" |
        sed -E 's/.*Tests run: ([0-9]+), Failures: ([0-9]+), Errors: ([0-9]+).*/\1 \2 \3/' |
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
