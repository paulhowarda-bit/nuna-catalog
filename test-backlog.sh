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

# Widening a claim (backlog 125, 129): the holder adds areas; another session cannot; an overlap still refuses.
printf '# 2. Two\n\nStatus: open\nAdded: 2026-11-22 by test\nTouches: a/\n' > backlog/2.md
printf '# 3. Three\n\nStatus: open\nAdded: 2026-11-22 by test\nTouches: c/\n' > backlog/3.md
"$here/backlog.sh" claim 2 >/dev/null || exit 2
git checkout -q -b other && CLAUDE_CODE_HOST_SESSION_ID=other "$here/backlog.sh" claim 3 >/dev/null || exit 2
git checkout -q "$branch" || exit 2
touches() { git cat-file -p "refs/backlog/claims/$1" | sed -n 's/^touches: //p'; }
if out=$("$here/backlog.sh" claim 2 b/ 2>&1) && [[ $out == widened* ]] && [[ " $(touches 2) " == *" a/ "* ]] &&
    [[ " $(touches 2) " == *" b/ "* ]]; then
    ok "the holder widens its claim: $(touches 2)"
else
    bad "widening item 2: $out; touches: $(touches 2)"
fi
if out=$("$here/backlog.sh" claim 2 c/ 2>&1); then
    bad "widening into another claim's area went on: $out"
elif [[ " $(touches 2) " == *" c/ "* ]]; then
    bad "a refused widening changed the claim: $(touches 2)"
else
    ok "widening into another claim's area refuses"
fi
if out=$(CLAUDE_CODE_HOST_SESSION_ID=third "$here/backlog.sh" claim 3 d/ 2>&1); then
    bad "another session widened item 3: $out"
elif [ "$(touches 3)" != c/ ]; then
    bad "another session's refused claim changed item 3: $(touches 3)"
else
    ok "another session cannot widen a claim it does not hold"
fi

# One session may hold two items whose areas overlap (it works them one at a time); another session may not.
printf '# 4. Four

Status: open
Added: 2026-11-23 by test
Touches: a/
' > backlog/4.md
if out=$("$here/backlog.sh" claim 4 2>&1) && [[ $out == claimed* ]]; then
    ok "the holder of item 2 claims item 4 in the same area"
else
    bad "the holder of item 2 could not claim item 4: $out"
fi
printf '# 5. Five

Status: open
Added: 2026-11-23 by test
Touches: a/
' > backlog/5.md
git checkout -q -b third || exit 2
if out=$(CLAUDE_CODE_HOST_SESSION_ID=third "$here/backlog.sh" claim 5 2>&1); then
    bad "another session claimed item 5 in the holder's area: $out"
else
    ok "another session cannot claim into the holder's area"
fi
git checkout -q "$branch" || exit 2

[ $fails -eq 0 ] && echo "all checks pass" || echo "$fails check(s) failed"
[ $fails -eq 0 ]
