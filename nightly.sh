#!/usr/bin/env bash
# The nightly in one command: the tests tagged overnight and the CAS test (-Pnightly), in two steps, then the
# number of tests each module ran, zeros included. Run it in a worktree or a clean checkout.
#
#   ./nightly.sh                              the whole nightly
#   ./nightly.sh -Pengines.cluster=<context>  any Gradle option goes to both steps (at work: the cluster's context)
#   ./nightly.sh --test-dry-run               lists what would run and runs nothing
#
# 1. Every module but the engine matrix: the CAS test at 1,000 rounds and the per-mutation fault sweeps on all
#    four databases, the load, resilience and SLO runs, and the maintenance worker's large runs. Two modules at
#    a time, as in every build.
# 2. The engine matrix on its own. Its Kubernetes stack (SingleStore alone asks for 1.5 to 3 GiB) does not fit in
#    Docker's 8 GB beside a three-node cluster or a load stack, and in one Gradle run it could start beside one.
# Each step runs whatever the other did, with --continue, so one failure does not hide the rest. Exits 0 only
# if both pass.
#
# The counts come only from result files this run wrote: a module that ran nothing shows 0, never an earlier
# run's numbers. Record each run in status/ (status/README.md).

cd "$(dirname "$0")" || exit 2
start=$SECONDS
mkdir -p build
marker=build/nightly.started
touch "$marker"

./gradlew --console=plain --continue -Pnightly -x :catalog-tests-engines:test test "$@"
step1=$?
./gradlew --console=plain --continue -Pnightly :catalog-tests-engines:test "$@"
step2=$?

verdict() { if [ "$1" -eq 0 ]; then echo passed; else echo FAILED; fi; }

# tests, skipped, failures and errors in the result files of module $1 written since this run started.
counts() {
    find "$1/build/test-results/test" -name 'TEST-*.xml' -newer "$marker" -print0 2>/dev/null |
        xargs -0 -r cat | grep "<testsuite " |
        sed -E 's/.* tests="([0-9]+)" skipped="([0-9]+)" failures="([0-9]+)" errors="([0-9]+)".*/\1 \2 \3 \4/' |
        awk '{ t += $1; s += $2; f += $3; e += $4 } END { print t + 0, s + 0, f + 0, e + 0 }'
}

{
    echo
    echo "nightly.sh summary"
    echo "  step 1, every module but the engine matrix: $(verdict $step1)"
    echo "  step 2, the engine matrix:                  $(verdict $step2)"
    printf '  %-28s %6s %8s %9s %7s\n' module tests skipped failures errors
    for build in */build.gradle.kts; do
        module=${build%%/*}
        # shellcheck disable=SC2046
        printf '  %-28s %6s %8s %9s %7s\n' "$module" $(counts "$module")
    done
    echo "  time: $(( (SECONDS - start) / 60 )) min $(( (SECONDS - start) % 60 )) s"
} | tee build/nightly-summary.txt
[ "$step1" -eq 0 ] && [ "$step2" -eq 0 ]
