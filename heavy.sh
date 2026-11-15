#!/usr/bin/env bash
# The heavy pass in one command: `./gradlew build -Pheavy`, then the store tests and the maintenance worker's
# again on Ceph (`-Ps3=ceph`), then every recorded bite (`bites/run.sh` with no arguments). Stops if a build
# fails. Exits 0 only if all pass: every bite BITES, none is NO BITE or SETUP (bites/run.sh's header says which
# is which), and its summary line below counts the SETUP ones.
# The Ceph leg is there because Silo, the default store, enforces no bucket policy: the delete policy (D21, D30)
# is applied and tested only on Ceph (DeletePolicyCephTest), and the worker's tests run on it too.
# Run it in a worktree: the bites apply patches to tracked files and revert them.

cd "$(dirname "$0")" || exit 2
start=$SECONDS
mkdir -p build

if ! ./gradlew build -Pheavy; then
    echo "heavy.sh: build -Pheavy FAILED; Ceph leg and bites not run ($(( (SECONDS - start) / 60 )) min)"
    exit 1
fi

if ! ./gradlew :catalog-objectstore:test :catalog-maintenance:test -Pheavy -Ps3=ceph; then
    echo "heavy.sh: the Ceph leg FAILED; bites not run ($(( (SECONDS - start) / 60 )) min)"
    exit 1
fi

bites/run.sh | tee build/heavy-bites.log
bites=${PIPESTATUS[0]}

echo
echo "heavy.sh summary"
echo "  build -Pheavy: passed"
echo "  Ceph leg:      passed"
echo "  bites:         $(tail -n 1 build/heavy-bites.log) ($([ "$bites" -eq 0 ] && echo passed || echo FAILED))"
echo "  time:          $(( (SECONDS - start) / 60 )) min $(( (SECONDS - start) % 60 )) s"
[ "$bites" -eq 0 ]
