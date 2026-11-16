#!/usr/bin/env bash
# Checks ./backlog.sh done (backlog 50) in a scratch repository, so this clone's claims are never touched. On a branch
# whose name holds characters a sed replacement reads (/ and &; git refuses \ in a branch name), done must write
# "Status: done" and the Done: line and change nothing else. When the edit does not take (awk stubbed to copy the item
# unchanged, or to fail), it must say so, exit non-zero and leave the item as it was. Exits 0 when every check passes.
#
#   ./test-backlog.sh

set -u
here=$(cd "$(dirname "$0")" && pwd)
root=$(mktemp -d)
trap 'rm -rf "$root"' EXIT
fails=0
ok() { echo "ok    $*"; }
bad() { echo "FAIL  $*"; fails=$((fails + 1)); }

export CLAUDE_CODE_HOST_SESSION_ID=test-backlog GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@example.invalid \
    GIT_COMMITTER_NAME=test GIT_COMMITTER_EMAIL=test@example.invalid
branch='claude/x&y$1.w#v%u'
item_with() { printf '# 1. An item\n\n%s\nAdded: 2026-11-15 by test\nTouches: none\n\nWhat is wrong.' "$1"; }
item=$(item_with 'Status: open')
done_item=$(item_with "Status: done"$'\n'"Done: $(date +%F) on $branch")

mkdir -p "$root/repo/backlog" "$root/bin" && cd "$root/repo" || exit 2
git init -q -b main && git config core.autocrlf false || exit 2
printf '%s\n' "$item" > backlog/1.md
git add backlog/1.md && git commit -q -m item && git checkout -q -b "$branch" || exit 2
"$here/backlog.sh" claim 1 >/dev/null || exit 2

if out=$("$here/backlog.sh" done 1 2>&1) && [ "$(cat backlog/1.md)" = "$done_item" ]; then
    ok "done on $branch writes Status: done and the Done: line"
else
    bad "done on $branch: $out"$'\n'"$(cat backlog/1.md)"
fi

for stub in 'exec cat "$2"' 'exit 1'; do
    printf '#!/bin/sh\n%s\n' "$stub" > "$root/bin/awk"
    chmod +x "$root/bin/awk"
    printf '%s\n' "$item" > backlog/1.md
    if out=$(PATH="$root/bin:$PATH" "$here/backlog.sh" done 1 2>&1); then
        bad "done went on with awk as '$stub': $out"
    elif [ "$(cat backlog/1.md)" != "$item" ] || [ -e backlog/1.md.new ]; then
        bad "done changed the item with awk as '$stub'"
    else
        ok "done refuses with awk as '$stub': $out"
    fi
done

[ $fails -eq 0 ] && echo "all checks pass" || echo "$fails check(s) failed"
[ $fails -eq 0 ]
