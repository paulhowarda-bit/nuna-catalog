#!/usr/bin/env bash
# The backlog and its claims. backlog/README.md describes the process; this script does the parts that must be
# atomic or that look across every worktree.
#
#   ./backlog.sh list                   open items on main, and who holds each
#   ./backlog.sh add "<title>" [<by>]   take the next number and write backlog/<n>.md in this worktree
#   ./backlog.sh claim <n>              claim an open item for this session; fails if anyone holds it
#   ./backlog.sh done <n>               mark the item done in this worktree (you must hold it)
#   ./backlog.sh release <n> [--force]  give up the claim (--force: someone else's)
#   ./backlog.sh claims                 every claim and number in flight, and whether its worktree and branch remain
#   ./backlog.sh sweep                  release the claims whose item is finished or whose work is gone
#
# Claims and numbers are git refs, refs/backlog/claims/<n> and refs/backlog/ids/<n>. Every worktree of this clone
# sees the same refs, and nothing pushes them. Git creates a ref that must not exist yet atomically, so two agents
# can never hold the same item or take the same number. The holder is the Claude session
# (CLAUDE_CODE_HOST_SESSION_ID), so the session that claimed in its worktree can release from the main checkout.

set -u
die() { echo "backlog.sh: $*" >&2; exit 1; }
usage=$(sed -n '2,11s/^# \{0,1\}//p' "$0")

top=$(git rev-parse --show-toplevel) || exit 2
cd "$top" || exit 2
session=${CLAUDE_CODE_HOST_SESSION_ID:-${CLAUDE_CODE_SESSION_ID:-unknown}}
branch=$(git rev-parse --abbrev-ref HEAD)
now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
today=$(date +%F)

number() { [[ ${1:-} =~ ^[0-9]+$ ]] || die "give an item number"; }
main_items() { git ls-tree --name-only main backlog/ | sed -n 's|^backlog/\([0-9][0-9]*\)\.md$|\1|p' | sort -n; }
main_status() { git show "main:backlog/$1.md" 2>/dev/null | sed -n 's/^Status: *//p' | head -1; }
title() { git show "main:backlog/$1.md" 2>/dev/null | head -1 | sed 's/^# *[0-9]*\. *//'; }
field() { git cat-file -p "$1" 2>/dev/null | sed -n "s/^$2: //p" | head -1; }
refs() { git for-each-ref --format='%(refname:lstrip=3)' "refs/backlog/$1/" | sort -n; }

# Main's copy decides; an item not on main yet counts if this worktree has added it.
status_of() {
    local st
    st=$(main_status "$1")
    [ -n "$st" ] || st=$([ -f "backlog/$1.md" ] && sed -n 's/^Status: *//p' "backlog/$1.md" | head -1)
    echo "$st"
}

# This session holds the claim, or (when no session is known) this branch does. Main never counts as a holder:
# every session in the main checkout is on it.
holds() {
    local ref=refs/backlog/claims/$1
    git rev-parse -q --verify "$ref" >/dev/null || return 1
    [ "$session" != unknown ] && [ "$(field "$ref" session)" = "$session" ] && return 0
    [ "$branch" != main ] && [ "$(field "$ref" branch)" = "$branch" ]
}

age() {
    local s
    s=$(date -u -d "$1" +%s 2>/dev/null) || { echo "?"; return; }
    s=$(( ($(date -u +%s) - s) / 60 ))
    if [ $s -lt 120 ]; then echo "${s} min"; elif [ $s -lt 2880 ]; then echo "$((s / 60)) h"; else echo "$((s / 1440)) days"; fi
}

is_worktree() { [ -n "$1" ] && [ -d "$1" ] && git worktree list --porcelain | grep -qxF "worktree $1"; }
has_branch() { [ -n "$1" ] && git show-ref --verify --quiet "refs/heads/$1"; }

# A claim's state, then a reason: finished (the item is done or dropped on main), active (its worktree is there),
# abandoned (worktree gone, and the branch with it or holding nothing main lacks) or stranded (worktree gone, the
# branch holds work main lacks).
diagnose() {
    local ref=refs/backlog/claims/$1 st wt br ahead
    st=$(main_status "$1"); wt=$(field "$ref" worktree); br=$(field "$ref" branch)
    case $st in done|dropped) echo "finished|item is $st on main"; return ;; esac
    if is_worktree "$wt"; then echo "active|worktree $wt"; return; fi
    if ! has_branch "$br"; then echo "abandoned|worktree and branch $br are gone"; return; fi
    ahead=$(git cherry main "$br" | grep -c '^+')
    if [ "$ahead" -eq 0 ]; then
        echo "abandoned|worktree gone; branch $br holds nothing main lacks (delete it: git branch -D $br)"
    else
        echo "stranded|worktree gone; branch $br holds $ahead commit(s) main lacks: read them, git cherry -v main $br"
    fi
}

cmd_list() {
    local n ref
    for n in $(main_items); do
        [ "$(main_status "$n")" = open ] || continue
        ref=refs/backlog/claims/$n
        if git rev-parse -q --verify "$ref" >/dev/null; then
            printf '%4s  %s\n      claimed %s ago by %s on %s\n' "$n" "$(title "$n")" \
                "$(age "$(field "$ref" claimed)")" "$(field "$ref" session)" "$(field "$ref" branch)"
        else
            printf '%4s  %s\n' "$n" "$(title "$n")"
        fi
    done
}

cmd_add() {
    local t=${1:-} by=${2:-$session} n blob tries=0
    [ -n "$t" ] || die 'add: give a title, ./backlog.sh add "<title>" [<by>]'
    mkdir -p backlog
    n=$( { main_items; ls backlog | sed -n 's|^\([0-9][0-9]*\)\.md$|\1|p'; refs ids; echo 0; } | sort -n | tail -1)
    blob=$(printf 'title: %s\nsession: %s\nbranch: %s\nworktree: %s\nadded: %s\n' "$t" "$session" "$branch" "$top" "$now" \
        | git hash-object -w --stdin)
    n=$((n + 1))
    until git update-ref "refs/backlog/ids/$n" "$blob" "" 2>/dev/null; do
        tries=$((tries + 1)); [ $tries -lt 100 ] || die "could not take a number near $n"
        n=$((n + 1))
    done
    printf '# %s. %s\n\nStatus: open\nAdded: %s by %s\n\n' "$n" "$t" "$today" "$by" > "backlog/$n.md"
    echo "backlog/$n.md: write what is wrong or missing, the evidence, and what done looks like; then commit and land it"
}

cmd_claim() {
    local n=$1 st blob ref=refs/backlog/claims/$1
    st=$(status_of "$n")
    [ -n "$st" ] || die "there is no item $n on main or in this worktree"
    [ "$st" = open ] || die "item $n is $st"
    blob=$(printf 'item: %s\nsession: %s\nbranch: %s\nworktree: %s\nclaimed: %s\n' "$n" "$session" "$branch" "$top" "$now" \
        | git hash-object -w --stdin)
    if git update-ref "$ref" "$blob" "" 2>/dev/null; then
        echo "claimed item $n: $session on $branch"
    else
        echo "backlog.sh: item $n is already claimed:" >&2
        git cat-file -p "$ref" | sed 's/^/  /' >&2
        exit 1
    fi
}

cmd_done() {
    local n=$1 f=backlog/$1.md
    holds "$n" || die "you do not hold item $n: claim it first"
    [ -f "$f" ] || die "$f is not in this worktree: merge main first"
    grep -q '^Status: open$' "$f" || die "$f is not open"
    sed -i "s/^Status: open\$/Status: done\nDone: $today on $branch/" "$f"
    echo "$f marked done: say what was done beneath it, commit and land, then ./backlog.sh release $n"
}

cmd_release() {
    local n=$1 oid st
    oid=$(git rev-parse -q --verify "refs/backlog/claims/$n") || die "item $n is not claimed"
    [ "${2:-}" = --force ] || holds "$n" || die "item $n is held by $(field "$oid" session) on $(field "$oid" branch); --force releases it"
    git update-ref -d "refs/backlog/claims/$n" "$oid" 2>/dev/null || die "item $n's claim changed meanwhile: look again"
    st=$(main_status "$n")
    echo "released item $n; on main it is ${st:-not there yet}"
}

# Numbers taken whose item is not on main yet: pending while the branch that took one remains, lost once it is gone.
numbers() {
    local n ref br
    for n in $(refs ids); do
        ref=refs/backlog/ids/$n
        [ -n "$(main_status "$n")" ] && { [ "${1:-}" = sweep ] && git update-ref -d "$ref"; continue; }
        br=$(field "$ref" branch)
        if has_branch "$br" || is_worktree "$(field "$ref" worktree)"; then
            printf '%4s  pending  "%s", taken %s ago on %s\n' "$n" "$(field "$ref" title)" "$(age "$(field "$ref" added)")" "$br"
        else
            printf '%4s  lost     "%s", taken on %s, which is gone without landing it\n' "$n" "$(field "$ref" title)" "$br"
            [ "${1:-}" = sweep ] && git update-ref -d "$ref" && echo "      number freed"
        fi
    done
}

cmd_claims() {
    local n ref d mode=${1:-}
    for n in $(refs claims); do
        ref=refs/backlog/claims/$n
        d=$(diagnose "$n")
        printf '%4s  %-9s %s\n      %s ago by %s on %s\n' "$n" "${d%%|*}" "${d#*|}" \
            "$(age "$(field "$ref" claimed)")" "$(field "$ref" session)" "$(field "$ref" branch)"
        case $mode/${d%%|*} in
            sweep/finished|sweep/abandoned) git update-ref -d "$ref" "$(git rev-parse "$ref")" && echo "      released" ;;
        esac
    done
    numbers "$mode"
}

cmd=${1:-list}
shift 2>/dev/null
case $cmd in
    list) cmd_list; exit 0 ;;
    add) cmd_add "$@" ;;
    claim) number "${1:-}"; cmd_claim "$1" ;;
    done) number "${1:-}"; cmd_done "$1" ;;
    release) number "${1:-}"; cmd_release "$@" ;;
    claims) cmd_claims; exit 0 ;;
    sweep) cmd_claims sweep; exit 0 ;;
    *) echo "$usage"; exit 2 ;;
esac
