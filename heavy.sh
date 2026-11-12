#!/usr/bin/env bash
# The heavy pass in one command: `./gradlew build -Pheavy`, then every recorded bite
# (`bites/run.sh` with no arguments). Stops if the build fails. Exits 0 only if both pass.
# Run it in a worktree: the bites apply patches to tracked files and revert them.

cd "$(dirname "$0")" || exit 2
start=$SECONDS
mkdir -p build

if ! ./gradlew build -Pheavy; then
    echo "heavy.sh: build -Pheavy FAILED; bites not run ($(( (SECONDS - start) / 60 )) min)"
    exit 1
fi

bites/run.sh | tee build/heavy-bites.log
bites=${PIPESTATUS[0]}

echo
echo "heavy.sh summary"
echo "  build -Pheavy: passed"
echo "  bites:         $(tail -n 1 build/heavy-bites.log) ($([ "$bites" -eq 0 ] && echo passed || echo FAILED))"
echo "  time:          $(( (SECONDS - start) / 60 )) min $(( (SECONDS - start) % 60 )) s"
[ "$bites" -eq 0 ]
