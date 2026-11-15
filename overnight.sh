#!/usr/bin/env bash
# The overnight run: the heavy build, the Ceph leg, the nightly, then as many bites as fit before the deadline, on
# main's tip, in a clone of its own. Windows Task Scheduler starts it at midnight (overnight-task.ps1 registers
# the task); a Claude task reads runs/<date>/summary.md at 7:00, records the run in status/ and
# adds a backlog item for each failure (Paul, 2026-11-14).
#
#   ./overnight.sh           run if a file other than Markdown changed on main since the last run
#   ./overnight.sh --force   run whatever changed
#   ./overnight.sh --dry     check the plumbing: the builds run with -m and the nightly lists its tests, no bite
#                            runs, and nothing is recorded as tested
#
# Everything lives in $KEEL_OVERNIGHT_HOME (default: keel-overnight next to the main checkout):
#   clone/           a clone of this repo, reset to main's tip at each run; the bites patch its files
#   runs/<date>/     summary.md, one log per leg, and the logs of bites that did not bite
#   last-tested      the commit the last run tested (a dry run records nothing)
#   bites-next       the bite the next run starts from: the bites rotate, so nights that cannot fit all 212
#                    still cover every one in turn
#   running          present while a run is going: Docker belongs to the run until it is gone
#
# The legs, each logged, each run whatever the one before did:
#   1. ./gradlew build -Pheavy
#   2. the Ceph leg: :catalog-objectstore:test :catalog-maintenance:test -Pheavy -Ps3=ceph
#   3. ./nightly.sh: the overnight tests and the CAS test
#   4. bites/run.sh, one bite at a time, only if leg 1 passed (a bite run against a failing build proves
#      nothing)
# KEEL_OVERNIGHT_LEGS picks legs by name (default "heavy ceph nightly bites"); with heavy left out, the bites run.
# Nothing starts after $KEEL_OVERNIGHT_DEADLINE (default 06:30), and a leg still running then is stopped, so
# the day starts with Docker free.
#
# If the keel-demo stack is running, the run takes it down first (demo/demo.sh down) and rebuilds it at the end
# (demo/demo.sh up): fresh sample data, no CDC leg. Its deployments keep no volumes, so pausing them could not
# keep their data anyway (Paul, 2026-11-15). A dry run only says what it would do.

# The whole run is one function, so bash has read all of it before it starts: landing a change to this file
# while a run is going cannot change what that run does.
main() {
    set -u
    repo=$(cd "$(dirname "$0")" && pwd)
    # Next to the main checkout, wherever this copy of the script is.
    main_checkout=$(dirname "$(git -C "$repo" rev-parse --path-format=absolute --git-common-dir)")
    home=${KEEL_OVERNIGHT_HOME:-$(dirname "$main_checkout")/keel-overnight}
    deadline_at=${KEEL_OVERNIGHT_DEADLINE:-06:30}
    context=${KEEL_DEMO_CONTEXT:-docker-desktop}
    demo_ns=${KEEL_DEMO_NAMESPACE:-keel-demo}
    legs=${KEEL_OVERNIGHT_LEGS:-heavy ceph nightly bites}
    demo_sh=${KEEL_DEMO_SCRIPT:-$main_checkout/demo/demo.sh}

    force=false
    dry=false
    for arg in "$@"; do
        case $arg in
            --force) force=true ;;
            --dry) dry=true; force=true ;;
            *) sed -n '2,30s/^# \{0,1\}//p' "$0"; exit 2 ;;
        esac
    done

    mkdir -p "$home/runs"
    clone=$home/clone
    day=$(date +%F)
    runs=$home/runs/$day
    $dry && runs=$runs-dry
    mkdir -p "$runs"
    summary=$runs/summary.md
    lock=$home/running
    started=$(date +%s)
    deadline=$(date -d "$deadline_at" +%s)
    [ "$deadline" -gt "$started" ] || deadline=$(date -d "tomorrow $deadline_at" +%s)

    say() { echo "$(date +%T) $*" | tee -a "$runs/overnight.log"; }
    clock() { date -d "@$1" +%H:%M; }
    minutes() { echo "$(( $1 / 60 )) min"; }
    left() { echo $(( deadline - $(date +%s) )); }

    # --- one run at a time -------------------------------------------------------------------------------------
    if [ -f "$lock" ]; then
        pid=$(sed -n 's/^pid: //p' "$lock")
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            say "another overnight run is going (pid $pid); stopping"
            exit 1
        fi
        say "a run that ended without cleaning up left $lock; taking it over"
    fi
    printf 'pid: %s\nstarted: %s\nruns: %s\n' "$$" "$(date '+%F %T')" "$runs" >"$lock"

    demo_down=false
    demo_note="not running"
    demo() { (cd "$main_checkout" && KEEL_DEMO_CONTEXT="$context" KEEL_DEMO_NAMESPACE="$demo_ns" \
        timeout 1800 "$demo_sh" "$@") >>"$runs/demo.log" 2>&1; }
    rebuild_demo() {
        $demo_down || return 0
        demo_down=false
        say "rebuilding keel-demo: demo/demo.sh up"
        if demo up; then
            demo_note+="; rebuilt with demo/demo.sh up at $(date +%H:%M)"
        else
            demo_note+="; demo/demo.sh up FAILED at $(date +%H:%M), see demo.log"
        fi
        say "keel-demo: $demo_note"
    }
    finish() {
        rebuild_demo
        rm -f "$lock"
    }
    trap finish EXIT
    trap 'exit 130' INT TERM

    # --- is there anything new to test? ------------------------------------------------------------------------
    target=$(git -C "$repo" rev-parse main)
    last=$(cat "$home/last-tested" 2>/dev/null)
    if ! $force && [ -n "$last" ] && git -C "$repo" cat-file -e "$last" 2>/dev/null; then
        changed=$(git -C "$repo" diff --name-only "$last" "$target" -- . ':(exclude)*.md' | wc -l)
        if [ "$changed" -eq 0 ]; then
            {
                echo "# Overnight run $day: skipped"
                echo
                echo "Nothing but Markdown changed on main since the last run, $(git -C "$repo" log -1 --format='%h %s' "$last")."
            } >"$summary"
            say "skipped: no code change since $last"
            exit 0
        fi
    fi

    # --- Docker, and the clone at main's tip --------------------------------------------------------------------
    for try in $(seq 1 20); do
        docker info >/dev/null 2>&1 && kubectl --context "$context" get nodes >/dev/null 2>&1 && break
        [ "$try" -eq 20 ] && { say "Docker or its Kubernetes is not running"; echo "# Overnight run $day: FAILED, Docker or its Kubernetes is not running" >"$summary"; exit 1; }
        sleep 30
    done

    if [ ! -d "$clone/.git" ]; then
        git clone --quiet "$repo" "$clone" || { say "could not clone $repo"; exit 1; }
    fi
    # Test reports nest deep enough to pass Windows' 260-character limit; without this git clean cannot remove them.
    git -C "$clone" config core.longpaths true
    git -C "$clone" fetch --quiet "$repo" main && target=$(git -C "$clone" rev-parse FETCH_HEAD) &&
        git -C "$clone" checkout --quiet --force --detach "$target" && git -C "$clone" clean -q -fdx ||
        { say "could not reset $clone to main"; exit 1; }
    say "testing $(git -C "$clone" log -1 --format='%h %s' "$target")"

    # --- the demo out of the way --------------------------------------------------------------------------------
    # Down now, rebuilt at the end.
    running=$(kubectl --context "$context" -n "$demo_ns" get deployment,statefulset --no-headers \
        -o custom-columns=NAME:.metadata.name,REPLICAS:.spec.replicas 2>/dev/null |
        awk '$2 > 0 { printf "%s%s", (n++ ? ", " : ""), $1 }')
    if [ -n "$running" ] && $dry; then
        demo_note="running ($running); a real run would take it down and rebuild it after"
    elif [ -n "$running" ]; then
        say "keel-demo is running ($running); taking it down"
        demo_down=true
        if demo down; then
            demo_note="was running ($running): taken down for the run"
        else
            demo_note="was running ($running): demo/demo.sh down FAILED, see demo.log"
        fi
    fi

    # --- the legs -----------------------------------------------------------------------------------------------
    rows=()
    failed_tests=""

    # The failing tests in the result files written since marker $1: "Class#method: message".
    failures_since() {
        find "$clone" -path '*/build/test-results/*' -name 'TEST-*.xml' -newer "$1" -print0 2>/dev/null |
            xargs -0 -r awk '
                /<testcase / { match($0, /name="[^"]*"/); name = substr($0, RSTART + 6, RLENGTH - 7)
                               match($0, /classname="[^"]*"/); cls = substr($0, RSTART + 11, RLENGTH - 12) }
                /<(failure|error) / { msg = ""; if (match($0, /message="[^"]*"/)) msg = substr($0, RSTART + 9, RLENGTH - 10)
                                      gsub(/&lt;/, "<", msg); gsub(/&gt;/, ">", msg); gsub(/&quot;/, "\"", msg)
                                      gsub(/&#10;/, " ", msg); gsub(/&amp;/, "\\&", msg)
                                      print cls "#" name ": " substr(msg, 1, 200) }'
    }

    wanted() { [[ " $legs " == *" $1 "* ]]; }

    # leg <key> <title> <log> <command...>: runs the command in the clone until the deadline and records a row.
    # Returns 0 if it passed, 1 if it failed or did not run, 2 if KEEL_OVERNIGHT_LEGS left it out.
    leg() {
        local key=$1 title=$2 log=$3 marker status result t0
        shift 3
        if ! wanted "$key"; then
            rows+=("| $title | left out (KEEL_OVERNIGHT_LEGS) | | |")
            return 2
        fi
        if [ "$(left)" -le 60 ]; then
            rows+=("| $title | not run: past the deadline, $deadline_at | | |")
            return 1
        fi
        marker=$runs/.$log.started
        touch "$marker"
        t0=$(date +%s)
        say "$title: $*"
        (cd "$clone" && timeout --kill-after=120 "$(left)" "$@") >"$runs/$log" 2>&1
        status=$?
        case $status in
            0) result=passed ;;
            124|137) result="STOPPED at the deadline, $deadline_at" ;;
            *) result=FAILED ;;
        esac
        say "$title: $result"
        rows+=("| $title | $result | $(minutes $(( $(date +%s) - t0 ))) | $log |")
        local fails
        fails=$(failures_since "$marker")
        if [ -n "$fails" ]; then
            failed_tests+=$'\n'"### $title"$'\n\n'"$(printf '%s\n' "$fails" | sort -u | sed 's/^/- /')"$'\n'
        fi
        [ "$status" -eq 0 ]
    }

    dry_args=()
    nightly_dry=()
    $dry && dry_args=(-m) && nightly_dry=(--test-dry-run)
    leg heavy "Heavy build" heavy.log ./gradlew --console=plain build -Pheavy "${dry_args[@]}"
    heavy_ok=$?
    leg ceph "Ceph leg" ceph.log ./gradlew --console=plain :catalog-objectstore:test :catalog-maintenance:test -Pheavy -Ps3=ceph "${dry_args[@]}"
    leg nightly "Nightly" nightly.log ./nightly.sh "${nightly_dry[@]}"

    # --- the bites, rotating ------------------------------------------------------------------------------------
    mapfile -t all < <(cd "$clone" && ls bites/*/*.patch | sort)
    next=$(cat "$home/bites-next" 2>/dev/null)
    start=0
    for i in "${!all[@]}"; do
        [ "${all[$i]}" = "$next" ] && start=$i && break
    done
    bite_rows=""
    ran=0; bit=0; not_bit=0
    t0=$(date +%s)
    if ! wanted bites; then
        rows+=("| Bites | left out (KEEL_OVERNIGHT_LEGS) | | |")
    elif [ "$heavy_ok" -eq 1 ]; then
        rows+=("| Bites | not run: the heavy build did not pass | | |")
    elif $dry; then
        rows+=("| Bites | dry run: would start at ${all[$start]} of ${#all[@]} | | |")
    else
        mkdir -p "$runs/bites"
        say "bites: from ${all[$start]}, ${#all[@]} in all"
        for (( k = 0; k < ${#all[@]}; k++ )); do
            patch=${all[$(( (start + k) % ${#all[@]} ))]}
            [ "$(left)" -le 300 ] && break
            id=$(basename "$patch" .patch)
            out=$(cd "$clone" && timeout --kill-after=120 "$(left)" bites/run.sh "$patch" 2>&1)
            echo "$out" >>"$runs/bites.log"
            row=$(printf '%s\n' "$out" | grep -m1 "^$id ")
            ran=$((ran + 1))
            if [[ $row == *" BITES ("* && $row == *" restored" ]]; then
                bit=$((bit + 1))
            else
                not_bit=$((not_bit + 1))
                bite_rows+="- $patch: $(printf '%s' "${row:-no verdict (stopped?)}" | sed "s/^$id *//; s/  */ /g")"$'\n'
                cp "$clone/build/bites/$id.log" "$runs/bites/" 2>/dev/null
            fi
            echo "${all[$(( (start + k + 1) % ${#all[@]} ))]}" >"$home/bites-next"
            if [[ $row == *"HASH MISMATCH"* ]] || [ -z "$row" ]; then
                git -C "$clone" checkout --quiet --force "$target" && git -C "$clone" clean -q -fdx -e build/bites
            fi
        done
        result="$ran of ${#all[@]} ran: $bit bite"
        [ "$not_bit" -gt 0 ] && result+=", $not_bit did NOT"
        [ "$ran" -lt "${#all[@]}" ] && result+="; the deadline stopped the rest, which the next run starts on"
        rows+=("| Bites | $result | $(minutes $(( $(date +%s) - t0 ))) | bites.log |")
        say "bites: $result"
    fi

    rebuild_demo

    # --- the summary --------------------------------------------------------------------------------------------
    ended=$(date +%s)
    {
        echo "# Overnight run $day$($dry && echo ' (dry run: nothing was tested)')"
        echo
        echo "- Commit: $(git -C "$clone" log -1 --format='%h %s' "$target")"
        [ -n "$last" ] && echo "- Last run tested: $(git -C "$repo" log -1 --format='%h' "$last" 2>/dev/null || echo "$last")"
        echo "- From $(clock "$started") to $(clock "$ended"), $(minutes $(( ended - started ))); deadline $deadline_at"
        echo "- keel-demo: $demo_note"
        echo "- Logs: $runs"
        echo
        echo "| Leg | Result | Time | Log |"
        echo "|---|---|---|---|"
        printf '%s\n' "${rows[@]}"
        if [ -n "$failed_tests" ]; then
            echo
            echo "## Failed tests"
            printf '%s' "$failed_tests"
        fi
        if [ -n "$bite_rows" ]; then
            echo
            echo "## Bites that did not bite (their logs are in bites/)"
            echo
            printf '%s' "$bite_rows"
        fi
        if [ -f "$clone/build/nightly-summary.txt" ] && [ "$clone/build/nightly-summary.txt" -nt "$runs/.nightly.log.started" ]; then
            echo
            echo "## Nightly counts"
            echo
            echo '```'
            sed '/^$/d' "$clone/build/nightly-summary.txt"
            echo '```'
        fi
    } >"$summary"
    say "summary in $summary"
    $dry || echo "$target" >"$home/last-tested"
    exit 0
}

main "$@"
