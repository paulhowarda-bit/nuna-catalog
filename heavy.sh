#!/usr/bin/env bash
# The heavy pass in one command: `./gradlew build -Pheavy`, then the store tests and the maintenance worker's
# again on Ceph (`-Ps3=ceph`), then every recorded bite (`bites/run.sh` with no arguments). Stops if a build
# fails. Exits 0 only if all pass: every bite BITES, none is NO BITE or SETUP (bites/run.sh's header says which
# is which), and its summary line below counts the SETUP ones.
# The Ceph leg is there because Silo, the default store, enforces no bucket policy: the delete policy (D21, D30)
# is applied and tested only on Ceph (DeletePolicyCephTest), and the worker's tests run on it too.
# Run it in a worktree: the bites apply patches to tracked files and revert them.

cd "$(dirname "$0")" || exit 2

# The build lock (CLAUDE.md, Builds): while .overnight/running exists in the main checkout, Docker belongs to the run
# that wrote it and other sessions start no build. Like overnight.sh, this writes its pid there when no live run
# holds it, and removes it at the end if it is still its own. A live run's lock it leaves alone. The same block is in
# nightly.sh, which overnight.sh runs in its worktree, <home>/worktree, beside its lock, <home>/running.
main_checkout=$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")
home=${NUNA_OVERNIGHT_HOME:-$main_checkout/.overnight}
lock=$home/running
holder=$(sed -n 's/^pid: //p' "$lock" 2>/dev/null)
if [ -n "$holder" ] && kill -0 "$holder" 2>/dev/null; then
    echo "${0##*/}: pid $holder holds $lock; leaving it to that run"
else
    mkdir -p "$home"
    printf 'pid: %s\nstarted: %s\nby: %s in %s\n' "$$" "$(date '+%F %T')" "${0##*/}" "$PWD" >"$lock"
    echo "${0##*/}: holding $lock until it ends"
    unlock() { [ "$(sed -n 's/^pid: //p' "$lock" 2>/dev/null)" != "$$" ] || rm -f "$lock"; }
    trap unlock EXIT
    trap 'exit 130' INT TERM
fi

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
