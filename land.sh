#!/usr/bin/env bash
# Land a branch on main without main ever meeting a conflict. Usage: ./land.sh <branch> "<message>"
#
# The branch must already hold main: merge main into it, settle anything that conflicts there (in the branch, by the
# agent that did the work), and run the build. This merges it into main only if main has not moved since, so the
# merge is clean by construction. Two landings take turns through the ref refs/backlog/landing, so neither can move
# main under the other between that check and the merge.

set -u
die() { echo "land.sh: $*" >&2; exit 1; }
br=${1:-} msg=${2:-}
[ -n "$br" ] && [ -n "$msg" ] || die 'usage: ./land.sh <branch> "<message>"'
cd "$(dirname "$0")" || exit 2
[ "$br" != main ] || die "give the branch to land, not main"
git show-ref --verify --quiet "refs/heads/$br" || die "there is no branch $br"
checkout=$(git worktree list --porcelain | awk '/^worktree /{w=substr($0,10)} $0=="branch refs/heads/main"{print w; exit}')
[ -n "$checkout" ] || die "no checkout has main checked out"

# Take the landing turn. A turn older than 10 minutes belongs to a landing that died: landing takes seconds.
ref=refs/backlog/landing
turn=$(printf 'branch: %s\nsince: %s\n' "$br" "$(date -u +%s)" | git hash-object -w --stdin)
until git update-ref "$ref" "$turn" "" 2>/dev/null; do
    old=$(git rev-parse -q --verify "$ref") || continue
    since=$(git cat-file -p "$old" | sed -n 's/^since: //p')
    if [ $(( $(date -u +%s) - ${since:-0} )) -gt 600 ]; then
        git update-ref -d "$ref" "$old" 2>/dev/null
    else
        die "$(git cat-file -p "$old" | sed -n 's/^branch: //p') is landing now; try again in a minute"
    fi
done
trap 'git update-ref -d "$ref" "$turn"' EXIT

[ -z "$(git -C "$checkout" status --porcelain)" ] || die "the main checkout has changes, so another session is working there: report $br as ready instead"
git merge-base --is-ancestor main "$br" ||
    die "main has moved since $br last merged it: merge main into $br again, run the build, then land"
if ! git -C "$checkout" merge --no-ff -q -m "$msg" "$br"; then
    git -C "$checkout" merge --abort 2>/dev/null
    die "the merge into main stopped (above), and main is as it was"
fi
[ -z "$(git cherry main "$br" | grep '^+')" ] || die "landed, but git cherry -v main $br still lists commits main lacks: look"
echo "landed $br on main at $(git rev-parse --short main)"
