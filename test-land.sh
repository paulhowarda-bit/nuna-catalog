#!/usr/bin/env bash
# Checks ./land.sh (backlog 176) in a scratch repository with a main checkout and a branch in a worktree, so this
# clone's main is never touched. land.sh must land a branch that holds main, and one whose main has moved since only
# in Markdown (the merged tree keeps both sides); it must refuse, leaving main and the main checkout as they were, when
# main has moved in another file, and when main's Markdown change conflicts with the branch's. Exits 0 when every check
# passes.
#
#   ./test-land.sh

set -u
here=$(cd "$(dirname "$0")" && pwd)
root=$(mktemp -d)
trap 'rm -rf "$root"' EXIT
fails=0
ok() { echo "ok    $*"; }
bad() { echo "FAIL  $*"; fails=$((fails + 1)); }

export CLAUDE_CODE_HOST_SESSION_ID=test-land GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@example.invalid \
    GIT_COMMITTER_NAME=test GIT_COMMITTER_EMAIL=test@example.invalid

main=$root/main
mkdir -p "$main/backlog" && cd "$main" || exit 2
git init -q -b main && git config core.autocrlf false || exit 2
cp "$here/land.sh" "$here/backlog.sh" .
printf '# 1. An item\n\nStatus: open\nAdded: 2026-11-23 by test\nTouches: none\n\nWhat is wrong.\n' > backlog/1.md
printf 'notes\n' > notes.md
printf 'code 1\n' > code.txt
git add -A && git commit -q -m start || exit 2

n=0
# A branch in its own worktree, cut from main, with one commit changing $1 to $2.
branch() {
    n=$((n + 1)); br=b$n; wt=$root/$br
    git worktree add -q -b "$br" "$wt" main || exit 2
    printf '%s\n' "$2" > "$wt/$1" && git -C "$wt" commit -q -am "$br changes $1" || exit 2
}
# A commit on main changing $1 to $2, as another landing would.
main_moves() { printf '%s\n' "$2" > "$main/$1" && git -C "$main" commit -q -am "main changes $1" || exit 2; }
# Whether land.sh refused branch $br and left main at $1 with a clean main checkout.
refused() {
    [ "$status" -ne 0 ] && [ "$(git -C "$main" rev-parse main)" = "$1" ] && [ -z "$(git -C "$main" status --porcelain)" ]
}

branch code.txt 'code 2'
out=$(./land.sh "$br" "Land $br" 2>&1); status=$?
if [ "$status" -eq 0 ] && [ "$(git show main:code.txt)" = 'code 2' ]; then ok "a branch that holds main lands"
else bad "a branch that holds main lands: $out"; fi

branch code.txt 'code 3'
main_moves notes.md 'notes, edited on main'
out=$(./land.sh "$br" "Land $br" 2>&1); status=$?
if [ "$status" -eq 0 ] && [ "$(git show main:code.txt)" = 'code 3' ] &&
    [ "$(git show main:notes.md)" = 'notes, edited on main' ] && [[ $out == *"only in Markdown"* ]]; then
    ok "a branch whose main moved only in Markdown lands, with both changes"
else bad "a branch whose main moved only in Markdown lands: $out"; fi

branch notes.md 'notes, edited on the branch'
main_moves code.txt 'code 4'
before=$(git rev-parse main)
out=$(./land.sh "$br" "Land $br" 2>&1); status=$?
if refused "$before" && [[ $out == *"files other than Markdown (code.txt"* ]]; then
    ok "a branch whose main moved in code is refused, main as it was"
else bad "a branch whose main moved in code is refused: $out"; fi

branch backlog/1.md "$(printf '# 1. An item\n\nStatus: open\nAdded: 2026-11-23 by test\nTouches: none\n\nThe branch says this.')"
main_moves backlog/1.md "$(printf '# 1. An item\n\nStatus: open\nAdded: 2026-11-23 by test\nTouches: none\n\nMain says that.')"
before=$(git rev-parse main)
out=$(./land.sh "$br" "Land $br" 2>&1); status=$?
if refused "$before" && [[ $out == *"merge into main stopped"* ]]; then
    ok "a Markdown move that conflicts with the branch is refused, main as it was"
else bad "a Markdown move that conflicts with the branch is refused: $out"; fi

[ "$fails" -eq 0 ] && echo "all land.sh checks pass" || echo "$fails land.sh check(s) failed"
[ "$fails" -eq 0 ]
