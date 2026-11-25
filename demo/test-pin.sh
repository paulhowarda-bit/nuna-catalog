#!/usr/bin/env bash
# Checks demo.sh's pinned checkout (backlog 274) in a scratch repository, so this clone and the demo are never
# touched: no Docker, kubectl or Gradle runs. The scratch repo's demo/demo.sh is this one's up to its command switch,
# so the preamble under test is the real one, followed by a stub `up` that reads a file of the repo, waits while a
# landing moves the main checkout, and reads it again. It must run from $NUNA_DEMO_HOME/checkout at the commit the
# main checkout had when it started, both times, and finish the script it started with; refuse a second command
# while the first runs; take over a lock whose demo.sh is gone; move to the new commit on the next run; and run in
# place from any other worktree and for a command that reads no repo files. Exits 0 when every check passes.
#
#   demo/test-pin.sh

set -u
here=$(cd "$(dirname "$0")" && pwd)
root=$(mktemp -d)
trap 'git -C "$root/main" worktree unlock "$root/home/checkout" 2>/dev/null; rm -rf "$root"' EXIT
fails=0
ok() { echo "ok    $*"; }
bad() { echo "FAIL  $*"; fails=$((fails + 1)); }

export GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@example.invalid GIT_COMMITTER_NAME=test \
    GIT_COMMITTER_EMAIL=test@example.invalid NUNA_DEMO_HOME=$root/home
unset NUNA_DEMO_COMMIT
go=$root/go

main=$root/main
mkdir -p "$main/demo" && cd "$main" || exit 2
git init -q -b main && git config core.autocrlf false || exit 2
# demo.sh up to its command switch, then the stub; $1 is the version its last line names.
script() {
    sed -n '/^case "${1:-}" in$/q;p' "$here/demo.sh"
    cat <<EOF
case "\${1:-}" in
  up)
    echo "repo \$REPO"
    echo "commit \$(commit_here)"
    echo "marker \$(cat "\$REPO/marker")"
    while [[ ! -f "$go" ]]; do sleep 0.1; done
    echo "marker \$(cat "\$REPO/marker")"
    ;;
  status) echo "repo \$REPO" ;;
esac
echo "script $1"
EOF
}
script v1 > demo/demo.sh && printf 'v1\n' > marker && git add -A && git commit -q -m one || exit 2
one=$(git rev-parse --short=12 HEAD)
grep -q 'NUNA_DEMO_COMMIT=' demo/demo.sh || { echo "the preamble is not in demo.sh's copy"; exit 2; }

# 1. A landing during up changes neither what it reads nor the script it runs.
out=$root/up1.out
bash demo/demo.sh up > "$out" 2>&1 &
pid=$!
for _ in $(seq 100); do grep -q '^marker' "$out" && break; sleep 0.1; done
script v2 > demo/demo.sh && printf 'v2\n' > marker && git commit -q -am two || exit 2
two=$(git rev-parse --short=12 HEAD)
# 2. While it runs, a second command is refused.
if bash demo/demo.sh gen > "$root/second.out" 2>&1; then bad "a second command ran beside up"
elif grep -q 'another demo.sh (pid [0-9]*) is running' "$root/second.out"; then ok "a second command is refused while up runs"
else bad "a second command failed otherwise: $(cat "$root/second.out")"; fi
touch "$go"
wait "$pid"; status=$?
expected="repo $root/home/checkout
commit $one one
marker v1
marker v1
script v1"
if [[ $status -eq 0 && "$(cat "$out")" == "$expected" ]]; then
    ok "up ran from the pinned checkout at $one throughout a landing, and finished its own script"
else
    bad "up during a landing (exit $status):"; sed 's/^/      /' "$out"
fi
[[ ! -e "$root/home/checkout.pid" ]] && ok "the lock goes when up ends" || bad "the lock outlived up"
git worktree list --porcelain | grep -A4 '/home/checkout$' | grep -q '^locked' \
    && ok "the pinned checkout is a locked worktree" || bad "the pinned checkout is not a locked worktree"

# 3. A lock whose demo.sh is gone is taken over; the next up runs main's new tip.
echo 999999 > "$root/home/checkout.pid"
if out2=$(bash demo/demo.sh up 2>&1) && grep -qxF "commit $two two" <<<"$out2" && grep -qx 'marker v2' <<<"$out2" \
    && grep -qx 'script v2' <<<"$out2"; then
    ok "the next up takes over a dead lock and runs from $two"
else
    bad "the next up:"; sed 's/^/      /' <<<"$out2"
fi

# 4. From another worktree demo.sh runs in place, with its uncommitted changes, and takes no lock.
git worktree add -q -b other "$root/other" main || exit 2
printf 'mine\n' > "$root/other/marker"
# A live holder, which would refuse a pinned command; in place never looks at it.
sleep 30 & holder=$!; echo "$holder" > "$root/home/checkout.pid"
out3=$(bash "$root/other/demo/demo.sh" up 2>&1)
kill "$holder" 2>/dev/null; rm -f "$root/home/checkout.pid"
if grep -qxF "repo $root/other" <<<"$out3" && grep -qx 'marker mine' <<<"$out3" \
    && grep -q "with uncommitted changes in $root/other" <<<"$out3"; then
    ok "from another worktree up runs in place and names its uncommitted changes"
else
    bad "up from another worktree:"; sed 's/^/      /' <<<"$out3"
fi

# 5. A command that reads no repo files runs in place in the main checkout.
out4=$(bash demo/demo.sh status 2>&1)
[[ "$out4" == "repo $main"$'\n'"script v2" ]] && ok "status runs in place" || { bad "status:"; sed 's/^/      /' <<<"$out4"; }

echo
[[ $fails -eq 0 ]] && echo "all passed" || echo "$fails failed"
exit $((fails > 0))
