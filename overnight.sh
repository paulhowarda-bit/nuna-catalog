#!/usr/bin/env bash
# The overnight run: the heavy build, the Ceph leg, the nightly, then as many bites as fit before the deadline, on
# main's tip, in a worktree of its own. Windows Task Scheduler starts it at midnight (overnight-task.ps1 registers
# the task); a Claude task reads runs/<date>/summary.md at 7:00, records the run in status/ and
# adds a backlog item for each failure (Paul, 2026-11-14).
#
#   ./overnight.sh           run if a file other than Markdown changed on main since the last run
#   ./overnight.sh --force   run whatever changed
#   ./overnight.sh --dry     check the plumbing: the builds run with -m and the nightly lists its tests, no bite
#                            runs, and nothing is recorded as tested
#
# Everything lives in $NUNA_OVERNIGHT_HOME (default: .overnight in the main checkout, which .gitignore covers):
#   worktree/        a worktree of this repo, detached at main's tip each run and locked; the bites patch its files
#   runs/<date>/     summary.md, one log per leg, the logs of bites that did not bite, and for each leg that did
#                    not pass, <leg>-results/: its test result files and what docker info said just after
#   last-tested      the commit the last run tested (a dry run records nothing)
#   bites-next       the bite the next run starts from: the bites rotate, so nights that cannot fit all 212
#                    still cover every one in turn
#   running          present while a run is going: Docker belongs to the run until it is gone
#
# The legs, each logged, each run whatever the one before did:
#   1. ./gradlew build -Pheavy, with --continue, so one failing module does not hide the others (backlog 122)
#   2. the Ceph leg: :catalog-objectstore:test :catalog-maintenance:test -Pheavy -Ps3=ceph, with --continue too
#   3. ./nightly.sh: the overnight tests and the CAS test
#   4. bites/run.sh, one bite at a time, whatever tests failed in the legs before (backlog 164). A bite whose own
#      test class failed tonight, in any leg, is not run: its verdict would mean nothing; nor is one whose module's
#      tests hung. The bites are left out only when leg 1 failed with no failing test (it did not compile, or a
#      check failed): every bite would be SETUP.
# With -Pheavy, -Pnightly and -Pbites each test task has a timeout (build.gradle.kts), so a hung test fails its
# module and the leg, or the bite, goes on.
#   5. the agents land (backlog 165). After each leg, every module with a failing or hung test that has no agent yet
#      gets one: a headless Claude session (`claude -p`, named overnight-fix-<date>-<module>) in its own worktree and
#      branch from main, which claims or adds the backlog item, finds the cause and commits a fix while the run goes
#      on, running no build. Once the run has ended and let go of its lock, each agent's session is resumed in turn
#      to merge main, run the fast build and land. At most $NUNA_OVERNIGHT_AGENTS agents a night (default 3), up to
#      $NUNA_OVERNIGHT_AGENT_BUDGET dollars a session (default 10); the run waits for them until
#      $NUNA_OVERNIGHT_AGENTS_UNTIL (default 07:45, inside the task's 8 hours) and leaves any still going. What each did
#      is in runs/<date>/agents.md. No agent starts when the claude CLI is not signed in (claude auth login). The
#      next run removes an agent's worktree and branch once its work is on main.
# NUNA_OVERNIGHT_LEGS picks legs by name (default "heavy ceph nightly bites"); with heavy left out, the bites run.
# Nothing starts after $NUNA_OVERNIGHT_DEADLINE (default 06:30), and a leg still running then is stopped, so
# the day starts with Docker free. Each leg, and the bites, start once the containers of the leg before are gone,
# and Testcontainers is given 60 s, not 10, to reach Docker (backlog 32).
#
# If the nuna-demo stack is running, the run takes it down first (demo/demo.sh down) and rebuilds it at the end
# (demo/demo.sh up), no CDC leg. down keeps the data on the demo's volumes, so up carries on from it (backlog
# 237); the CDC leg, which keeps none, goes. A dry run only says what it would do.

# The whole run is one function, so bash has read all of it before it starts: landing a change to this file
# while a run is going cannot change what that run does.
main() {
    set -u
    repo=$(cd "$(dirname "$0")" && pwd)
    # In the main checkout, wherever this copy of the script is.
    main_checkout=$(dirname "$(git -C "$repo" rev-parse --path-format=absolute --git-common-dir)")
    home=${NUNA_OVERNIGHT_HOME:-$main_checkout/.overnight}
    deadline_at=${NUNA_OVERNIGHT_DEADLINE:-06:30}
    context=${NUNA_DEMO_CONTEXT:-docker-desktop}
    demo_ns=${NUNA_DEMO_NAMESPACE:-nuna-demo}
    legs=${NUNA_OVERNIGHT_LEGS:-heavy ceph nightly bites}
    demo_sh=${NUNA_DEMO_SCRIPT:-$main_checkout/demo/demo.sh}
    agents_max=${NUNA_OVERNIGHT_AGENTS:-3}
    agent_budget=${NUNA_OVERNIGHT_AGENT_BUDGET:-10}
    agents_until_at=${NUNA_OVERNIGHT_AGENTS_UNTIL:-07:45}
    claude_cli=${NUNA_OVERNIGHT_CLAUDE:-claude}
    # How long a test JVM's Testcontainers keeps asking Docker before it gives up, for good: every later test in
    # that JVM fails at once. Its default, 10 s, ran out in the Ceph leg of 2026-11-15 (backlog 32).
    export TESTCONTAINERS_CLIENT_PING_TIMEOUT=${TESTCONTAINERS_CLIENT_PING_TIMEOUT:-60}

    force=false
    dry=false
    for arg in "$@"; do
        case $arg in
            --force) force=true ;;
            --dry) dry=true; force=true ;;
            *) sed -n '2,/^$/s/^# \{0,1\}//p' "$0"; exit 2 ;;
        esac
    done

    mkdir -p "$home/runs"
    tree=$home/worktree
    day=$(date +%F)
    runs=$home/runs/$day
    $dry && runs=$runs-dry
    mkdir -p "$runs"
    summary=$runs/summary.md
    lock=$home/running
    started=$(date +%s)
    deadline=$(date -d "$deadline_at" +%s)
    [ "$deadline" -gt "$started" ] || deadline=$(date -d "tomorrow $deadline_at" +%s)
    agents_until=$(date -d "$agents_until_at" +%s)
    [ "$agents_until" -gt "$started" ] || agents_until=$(date -d "tomorrow $agents_until_at" +%s)

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
    demo() { (cd "$main_checkout" && NUNA_DEMO_CONTEXT="$context" NUNA_DEMO_NAMESPACE="$demo_ns" \
        timeout 1800 "$demo_sh" "$@") >>"$runs/demo.log" 2>&1; }
    rebuild_demo() {
        $demo_down || return 0
        demo_down=false
        say "rebuilding nuna-demo: demo/demo.sh up"
        if demo up; then
            demo_note+="; rebuilt with demo/demo.sh up at $(date +%H:%M)"
        else
            demo_note+="; demo/demo.sh up FAILED at $(date +%H:%M), see demo.log"
        fi
        say "nuna-demo: $demo_note"
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

    # --- Docker, and the worktree at main's tip -----------------------------------------------------------------
    for try in $(seq 1 20); do
        docker info >/dev/null 2>&1 && kubectl --context "$context" get nodes >/dev/null 2>&1 && break
        [ "$try" -eq 20 ] && { say "Docker or its Kubernetes is not running"; echo "# Overnight run $day: FAILED, Docker or its Kubernetes is not running" >"$summary"; exit 1; }
        sleep 30
    done

    # Test reports nest deep enough to pass Windows' 260-character limit; without long paths git clean cannot remove
    # them. Set for every git command of the run, the bites' included, not in the config every worktree shares.
    export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.longpaths GIT_CONFIG_VALUE_0=true
    # A worktree like every session's (Paul, 2026-11-23, backlog 166), but detached, so it holds no branch, and locked,
    # so git worktree prune or remove leave it alone. Forced twice: a locked worktree whose folder is gone stays
    # registered, and a plain add refuses its path.
    if [ ! -e "$tree/.git" ]; then
        git -C "$repo" worktree add --quiet --force --force --detach "$tree" "$target" >>"$runs/overnight.log" 2>&1 ||
            { say "could not make the worktree $tree"; exit 1; }
    fi
    git -C "$repo" worktree lock --reason "the overnight run's (overnight.sh)" "$tree" 2>/dev/null
    git -C "$tree" checkout --quiet --force --detach "$target" && git -C "$tree" clean -q -fdx ||
        { say "could not reset $tree to main"; exit 1; }
    say "testing $(git -C "$tree" log -1 --format='%h %s' "$target")"

    # --- the worktrees of earlier nights' agents whose work is on main ------------------------------------------
    # Nothing else removes them: the run that started an agent has ended before the agent's landing could be judged.
    pruned=""
    kept=""
    if ! $dry; then
        for wt in "$main_checkout"/.claude/worktrees/overnight-fix-*; do
            [ -d "$wt" ] || continue
            name=$(basename "$wt")
            if [ -z "$(git -C "$wt" status --porcelain 2>/dev/null)" ] &&
                ! git -C "$main_checkout" cherry main "worktree-$name" 2>/dev/null | grep -q '^+' &&
                git -C "$main_checkout" worktree remove "$wt" 2>/dev/null; then
                git -C "$main_checkout" branch -q -D "worktree-$name" 2>/dev/null
                pruned+=" $name"
            else
                kept+=" $name"
            fi
        done
    fi

    # --- the demo out of the way --------------------------------------------------------------------------------
    # Down now, rebuilt at the end.
    running=$(kubectl --context "$context" -n "$demo_ns" get deployment,statefulset --no-headers \
        -o custom-columns=NAME:.metadata.name,REPLICAS:.spec.replicas 2>/dev/null |
        awk '$2 > 0 { printf "%s%s", (n++ ? ", " : ""), $1 }')
    if [ -n "$running" ] && $dry; then
        demo_note="running ($running); a real run would take it down and rebuild it after"
    elif [ -n "$running" ]; then
        say "nuna-demo is running ($running); taking it down"
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
    failed_classes=$runs/.failed-classes
    rm -f "$failed_classes"

    # The failing tests in the result files written since marker $1, under folder $2 (default: the whole worktree):
    # "Class#method: message".
    failures_since() {
        find "${2:-$tree}" -path '*/build/test-results/*' -name 'TEST-*.xml' -newer "$1" -print0 2>/dev/null |
            xargs -0 -r awk '
                /<testcase / { match($0, /name="[^"]*"/); name = substr($0, RSTART + 6, RLENGTH - 7)
                               match($0, /classname="[^"]*"/); cls = substr($0, RSTART + 11, RLENGTH - 12) }
                /<(failure|error) / { msg = ""; if (match($0, /message="[^"]*"/)) msg = substr($0, RSTART + 9, RLENGTH - 10)
                                      gsub(/&lt;/, "<", msg); gsub(/&gt;/, ">", msg); gsub(/&quot;/, "\"", msg)
                                      gsub(/&#10;/, " ", msg); gsub(/&amp;/, "\\&", msg)
                                      print cls "#" name ": " substr(msg, 1, 200) }'
    }

    # The test classes with a failing test in the result files written since marker $1: "module class", the class
    # fully qualified (a nested one as Outer$Inner).
    failed_classes_since() {
        find "$tree" -path '*/build/test-results/*' -name 'TEST-*.xml' -newer "$1" -print0 2>/dev/null |
            xargs -0 -r awk -v tree="$tree/" '
                /<testsuite / && (/ failures="[1-9]/ || / errors="[1-9]/) {
                    module = substr(FILENAME, length(tree) + 1); sub(/\/.*/, "", module)
                    match($0, /name="[^"]*"/); print module " " substr($0, RSTART + 6, RLENGTH - 7) }'
    }

    # Whether a test of class $2 (as a bite names it: Class or Outer$Inner) in module $1 failed tonight, in any leg.
    # A failure in a nested class counts for its outer class, which the bite's test run includes; "module *" is a
    # module whose test task hung.
    failed_tonight() {
        [ -f "$failed_classes" ] && awk -v m="$1" -v c="$2" '
            $1 == m { n = $2; sub(/.*\./, "", n); if (n == "*" || n == c || index(n, c "$") == 1) found = 1 }
            END { exit !found }' "$failed_classes"
    }

    # Keeps the result files written since marker $1 in folder $2, at their paths in the worktree, with docker info.
    # The bites run the same tests later and overwrite them in the worktree, and each holds its test's output: the
    # Ceph leg of 2026-11-15 found no Docker, and the files that said why were gone by morning (backlog 32).
    keep_results() {
        mkdir -p "$2"
        (cd "$tree" && find . -path '*/build/test-results/*' -name 'TEST-*.xml' -newer "$1" -print0 |
            xargs -0 -r cp --parents -t "$2")
        timeout 60 docker info >"$2/docker-info.txt" 2>&1
    }

    # Waits, up to 3 minutes, until no container a test started is left (Ryuk removes them about 10 s after their
    # JVM exits) and Docker answers. The Ceph leg of 2026-11-15 started 9 s after the heavy build ended, while Docker
    # was still removing that build's containers, and its maintenance tests found no Docker; run on its own, the
    # same leg found it (backlog 32).
    settle() {
        local until=$(( $(date +%s) + 180 ))
        while [ "$(date +%s)" -lt "$until" ]; do
            if [ -z "$(timeout 30 docker ps -q --filter label=org.testcontainers=true 2>/dev/null)" ] &&
                timeout 30 docker info >/dev/null 2>&1; then
                return 0
            fi
            sleep 5
        done
        say "Docker still held test containers, or did not answer, after 3 min; starting anyway"
    }

    wanted() { [[ " $legs " == *" $1 "* ]]; }

    # --- the agents (backlog 165) -------------------------------------------------------------------------------
    agents_dir=$runs/agents
    agent_mods=()   # the modules given an agent tonight, in the order they were
    capped=""       # the modules left without one by the cap
    agents_note=""  # why no agent could start, when none could
    agents_ok=""    # whether agents can start: found out when the first is wanted

    # Field $2 of the JSON that `claude -p --output-format json` wrote to file $1, or nothing.
    json_field() {
        node -e 'try { const v = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"))[process.argv[2]];
                       if (v !== undefined) console.log(String(v)) } catch (e) {}' "$1" "$2" 2>/dev/null
    }

    # The brief of the agent for module $1, whose tests failed or hung in leg $2 (key), $3 (title), log $4, marker $5.
    brief() {
        local name=overnight-fix-$day-$1 fails
        fails=$(failures_since "$5" "$tree/$1" | sed 's/^/- /')
        grep -q "Requesting stop of task ':$1:test'" "$runs/$4" 2>/dev/null &&
            fails+=$'\n'"- :$1:test was stopped at its timeout: a test hung, and the test that hung left no result file"
        cat <<EOF
The overnight run of $day is testing main at $(git -C "$tree" log -1 --format='%h %s' "$target"), and tests in module $1 failed in its leg "$3". Paul, 2026-11-23: "when a test fails, an agent needs to be spawned to fix it without stopping the rest of the testing." You are that agent. The run goes on while you work.

What failed in $1:
$(printf '%s\n' "$fails" | sed '/^$/d')

The leg's log is $runs/$4. The test result files, each with its test's output, are kept in $runs/$2-results/$1/. Read them there and change nothing under $home.

You work in your own worktree, $main_checkout/.claude/worktrees/$name, on branch worktree-$name, cut from main. Follow its CLAUDE.md. For now:

1. Run ./backlog.sh list. If an open item already covers these failures, take it: if another agent holds it, stop there and say so. Otherwise claim it. If none covers them, add one (./backlog.sh add "<title>" "overnight run $day"), written as backlog/README.md asks, and claim it.
2. Find the cause from the result files, the log and the code, and fix it here. Commit on your branch. If you cannot find it, write what you found in the item and commit that.
3. Until you are told that the run has ended, run no Gradle command and no test, start no container and do not use Docker: the run holds Docker and the machine's memory, and a build beside it makes its tests fail. Do not land yet.

End with what you found and what you changed. Once the run has ended you will be resumed in this session, to run the fast build and land.
EOF
    }

    land_prompt() {
        cat <<EOF
The overnight run has ended, so you may build now. If you stopped because another agent holds the item, or you changed nothing, say so and stop. Otherwise merge main into your branch and run the fast build, ./gradlew build. When it passes (or the branch changes only Markdown), land as CLAUDE.md says: ./backlog.sh done <n> with what was done if the fix is in, a status entry, ./land.sh, then ./backlog.sh release <n>. If you found no fix, land the item still open with what you found written in it, and release it. If the build fails and you cannot fix it, leave the branch unlanded and release the item. Do not run the tests that failed: they need containers, which wait for the overnight run, and the next one checks your fix. Leave your worktree and branch where they are: the next overnight run removes them once their work is on main. End with one line: landed or not, the item, and what the next run should show.
EOF
    }

    # Starts an agent for module $1 (arguments as brief's), unless it has one or the cap is reached.
    start_agent() {
        local mod=$1 name wt
        case " ${agent_mods[*]} " in *" $mod "*) return 0 ;; esac
        if [ -z "$agents_ok" ]; then
            agents_ok=false
            if $dry; then
                agents_note="dry run: no agent starts"
            elif [ "$agents_max" -le 0 ]; then
                agents_note="none: NUNA_OVERNIGHT_AGENTS is $agents_max"
            elif ! "$claude_cli" auth status 2>/dev/null | grep -q '"loggedIn": *true'; then
                agents_note="none started: the claude CLI is not signed in (Paul runs \`claude auth login\` once)"
            else
                agents_ok=true
                mkdir -p "$agents_dir"
            fi
            [ -n "$agents_note" ] && say "agents: $agents_note"
        fi
        $agents_ok || return 0
        if [ "${#agent_mods[@]}" -ge "$agents_max" ]; then
            case " $capped " in *" $mod "*) ;; *) capped+=" $mod" ;; esac
            return 0
        fi
        agent_mods+=("$mod")
        name=overnight-fix-$day-$mod
        wt=$main_checkout/.claude/worktrees/$name
        if ! git -C "$main_checkout" worktree add -q -b "worktree-$name" "$wt" main >"$agents_dir/$mod.err" 2>&1; then
            echo "its worktree could not be made: $(tail -1 "$agents_dir/$mod.err")" >"$agents_dir/$mod.failed"
            say "agent for $mod: $(cat "$agents_dir/$mod.failed")"
            return 0
        fi
        shift
        brief "$mod" "$@" >"$agents_dir/$mod.brief.md"
        (cd "$wt" && "$claude_cli" -p --permission-mode auto --max-budget-usd "$agent_budget" -n "$name" \
            --output-format json <"$agents_dir/$mod.brief.md" >"$agents_dir/$mod.json" 2>>"$agents_dir/$mod.err") &
        echo $! >"$agents_dir/$mod.pid"
        say "agent $name started"
        write_agents_md
    }

    # Writes runs/<date>/agents.md: what each agent has done so far.
    write_agents_md() {
        local mod name cost phase f
        {
            echo "# Agents of the overnight run $day"
            echo
            echo "One for each module with a failing or hung test (backlog 165), at most $agents_max, up to \$$agent_budget a session; written $(date +%H:%M)."
            [ -n "$capped" ] && echo "No agent, the cap being reached:$capped."
            for mod in "${agent_mods[@]}"; do
                name=overnight-fix-$day-$mod
                echo
                echo "## $mod"
                echo
                echo "- Session $name, in .claude/worktrees/$name on branch worktree-$name; its brief is agents/$mod.brief.md"
                if [ -f "$agents_dir/$mod.failed" ]; then
                    echo "- Not started: $(cat "$agents_dir/$mod.failed")"
                    continue
                fi
                for phase in fix land; do
                    f=$agents_dir/$mod.json
                    [ "$phase" = land ] && f=$agents_dir/$mod.land.json
                    [ -e "$f" ] || continue
                    if [ ! -s "$f" ]; then
                        echo "- ${phase^}: still going"
                        continue
                    fi
                    cost=$(json_field "$f" total_cost_usd)
                    case $(json_field "$f" is_error) in
                        false) echo "- ${phase^}: ended (\$$cost)" ;;
                        true) echo "- ${phase^}: ENDED WITH AN ERROR, $(json_field "$f" subtype) (\$$cost; agents/$mod.err)" ;;
                        *) echo "- ${phase^}: ENDED WITHOUT A RESULT (agents/$mod.err, agents/$(basename "$f"))" ;;
                    esac
                    echo
                    json_field "$f" result | sed 's/^/    > /'
                    echo
                done
            done
        } >"$runs/agents.md"
    }

    # Once the run has let go of its lock: waits for each agent's fix, then resumes its session to land, one at a
    # time, until $agents_until.
    land_agents() {
        local mod pid sid name
        [ "${#agent_mods[@]}" -gt 0 ] || return 0
        for mod in "${agent_mods[@]}"; do
            [ -f "$agents_dir/$mod.pid" ] || continue
            pid=$(cat "$agents_dir/$mod.pid")
            while kill -0 "$pid" 2>/dev/null && [ "$(date +%s)" -lt "$agents_until" ]; do sleep 15; done
            kill -0 "$pid" 2>/dev/null && { say "agent for $mod: still fixing at $agents_until_at; left going"; break; }
            sid=$(json_field "$agents_dir/$mod.json" session_id)
            if [ -z "$sid" ] || [ "$(json_field "$agents_dir/$mod.json" is_error)" != false ]; then
                say "agent for $mod: its fix ended with an error; not resumed"
                write_agents_md
                continue
            fi
            name=overnight-fix-$day-$mod
            say "agent for $mod: landing"
            land_prompt | (cd "$main_checkout/.claude/worktrees/$name" && "$claude_cli" -p --resume "$sid" \
                --permission-mode auto --max-budget-usd "$agent_budget" --output-format json \
                >"$agents_dir/$mod.land.json" 2>>"$agents_dir/$mod.err") &
            pid=$!
            write_agents_md
            while kill -0 "$pid" 2>/dev/null && [ "$(date +%s)" -lt "$agents_until" ]; do sleep 15; done
            kill -0 "$pid" 2>/dev/null && { say "agent for $mod: still landing at $agents_until_at; left going"; break; }
            say "agent for $mod: $(json_field "$agents_dir/$mod.land.json" result | tail -1)"
            write_agents_md
        done
        write_agents_md
    }

    # leg <key> <title> <log> <command...>: runs the command in the worktree until the deadline and records a row.
    # Returns 0 if it passed, 1 if it failed or did not run, 2 if NUNA_OVERNIGHT_LEGS left it out.
    leg() {
        local key=$1 title=$2 log=$3 marker status result t0
        shift 3
        if ! wanted "$key"; then
            rows+=("| $title | left out (NUNA_OVERNIGHT_LEGS) | | |")
            return 2
        fi
        if [ "$(left)" -le 60 ]; then
            rows+=("| $title | not run: past the deadline, $deadline_at | | |")
            return 1
        fi
        settle
        marker=$runs/.$log.started
        touch "$marker"
        t0=$(date +%s)
        say "$title: $*"
        (cd "$tree" && timeout --kill-after=120 "$(left)" "$@") >"$runs/$log" 2>&1
        status=$?
        case $status in
            0) result=passed ;;
            124|137) result="STOPPED at the deadline, $deadline_at" ;;
            *) result=FAILED ;;
        esac
        say "$title: $result"
        local logs=$log
        if [ "$status" -ne 0 ]; then
            keep_results "$marker" "$runs/$key-results"
            logs+=", $key-results/"
        fi
        rows+=("| $title | $result | $(minutes $(( $(date +%s) - t0 ))) | $logs |")
        local fails hung
        fails=$(failures_since "$marker")
        # A test task stopped at its timeout (build.gradle.kts) leaves no result file for the test that hung.
        hung=$(grep -o "Requesting stop of task '[^']*'" "$runs/$log" 2>/dev/null | sed "s/.*task '\(.*\)'/\1: stopped at its timeout, a test hung (no result file names it)/" | sort -u)
        if [ -n "$fails$hung" ]; then
            failed_tests+=$'\n'"### $title"$'\n\n'"$(printf '%s\n' "$fails" "$hung" | sed '/^$/d' | sort -u | sed 's/^/- /')"$'\n'
            local found mod
            # A module whose test task hung counts as failing in every class: which test hung is not known, and its
            # bites would hang too.
            found=$(failed_classes_since "$marker"
                grep -o "Requesting stop of task ':[^']*:test'" "$runs/$log" 2>/dev/null |
                    sed "s/.*task ':\(.*\):test'/\1 */")
            printf '%s\n' "$found" | sed '/^$/d' >>"$failed_classes"
            for mod in $(printf '%s\n' "$found" | cut -d' ' -f1 | sort -u); do
                start_agent "$mod" "$key" "$title" "$log" "$marker"
            done
        fi
        [ "$status" -eq 0 ]
    }

    # Whether the leg logged in $1 failed without a failing or hung test: it did not compile, or a check failed.
    broken() {
        [ -f "$runs/.$1.started" ] && [ -z "$(failures_since "$runs/.$1.started")" ] &&
            ! grep -q "Requesting stop of task" "$runs/$1"
    }

    dry_args=()
    nightly_dry=()
    $dry && dry_args=(-m) && nightly_dry=(--test-dry-run)
    leg heavy "Heavy build" heavy.log ./gradlew --console=plain --continue build -Pheavy "${dry_args[@]}"
    heavy_status=$?
    heavy_broken=false
    [ "$heavy_status" -eq 1 ] && broken heavy.log && heavy_broken=true
    leg ceph "Ceph leg" ceph.log ./gradlew --console=plain --continue :catalog-objectstore:test :catalog-maintenance:test -Pheavy -Ps3=ceph "${dry_args[@]}"
    leg nightly "Nightly" nightly.log ./nightly.sh "${nightly_dry[@]}"

    # --- the bites, rotating ------------------------------------------------------------------------------------
    mapfile -t all < <(cd "$tree" && ls bites/*/*.patch | sort)
    next=$(cat "$home/bites-next" 2>/dev/null)
    start=0
    for i in "${!all[@]}"; do
        [ "${all[$i]}" = "$next" ] && start=$i && break
    done
    bite_rows=""
    unjudged_rows=""
    ran=0; bit=0; not_bit=0; unjudged=0
    t0=$(date +%s)
    if ! wanted bites; then
        rows+=("| Bites | left out (NUNA_OVERNIGHT_LEGS) | | |")
    elif $heavy_broken; then
        rows+=("| Bites | not run: the heavy build failed with no failing test (it did not compile, or a check failed) | | |")
    elif $dry; then
        rows+=("| Bites | dry run: would start at ${all[$start]} of ${#all[@]} | | |")
    else
        mkdir -p "$runs/bites"
        settle
        say "bites: from ${all[$start]}, ${#all[@]} in all"
        for (( k = 0; k < ${#all[@]}; k++ )); do
            patch=${all[$(( (start + k) % ${#all[@]} ))]}
            [ "$(left)" -le 300 ] && break
            id=$(basename "$patch" .patch)
            module=$(sed -n '/^diff --git/q; s/^Module: *//p' "$tree/$patch" | head -1 | tr -d '\r ')
            class=$(sed -n '/^diff --git/q; s/^Test: *//p' "$tree/$patch" | head -1 | tr -d '\r ' | sed 's/#.*//')
            if failed_tonight "$module" "$class"; then
                unjudged=$((unjudged + 1))
                unjudged_rows+="- $patch: $module $class"$'\n'
                echo "${all[$(( (start + k + 1) % ${#all[@]} ))]}" >"$home/bites-next"
                continue
            fi
            out=$(cd "$tree" && timeout --kill-after=120 "$(left)" bites/run.sh "$patch" 2>&1)
            echo "$out" >>"$runs/bites.log"
            row=$(printf '%s\n' "$out" | grep -m1 "^$id ")
            ran=$((ran + 1))
            if [[ $row == *" BITES ("* && $row == *" restored" ]]; then
                bit=$((bit + 1))
            else
                not_bit=$((not_bit + 1))
                bite_rows+="- $patch: $(printf '%s' "${row:-no verdict (stopped?)}" | sed "s/^$id *//; s/  */ /g")"$'\n'
                cp "$tree/build/bites/$id.log" "$runs/bites/" 2>/dev/null
            fi
            echo "${all[$(( (start + k + 1) % ${#all[@]} ))]}" >"$home/bites-next"
            if [[ $row == *"HASH MISMATCH"* ]] || [ -z "$row" ]; then
                git -C "$tree" checkout --quiet --force "$target" && git -C "$tree" clean -q -fdx -e build/bites
            fi
        done
        result="$ran of ${#all[@]} ran: $bit bite"
        [ "$not_bit" -gt 0 ] && result+=", $not_bit did NOT"
        [ "$unjudged" -gt 0 ] && result+="; $unjudged not run, their tests having failed or hung tonight"
        [ $(( ran + unjudged )) -lt "${#all[@]}" ] && result+="; the deadline stopped the rest, which the next run starts on"
        rows+=("| Bites | $result | $(minutes $(( $(date +%s) - t0 ))) | bites.log |")
        say "bites: $result"
    fi

    rebuild_demo

    # --- the summary --------------------------------------------------------------------------------------------
    ended=$(date +%s)
    {
        echo "# Overnight run $day$($dry && echo ' (dry run: nothing was tested)')"
        echo
        echo "- Commit: $(git -C "$tree" log -1 --format='%h %s' "$target")"
        [ -n "$last" ] && echo "- Last run tested: $(git -C "$repo" log -1 --format='%h' "$last" 2>/dev/null || echo "$last")"
        echo "- From $(clock "$started") to $(clock "$ended"), $(minutes $(( ended - started ))); deadline $deadline_at"
        echo "- nuna-demo: $demo_note"
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
        if [ -n "$unjudged_rows" ]; then
            echo
            echo "## Bites not run: their tests failed or hung tonight"
            echo
            printf '%s' "$unjudged_rows"
        fi
        if [ "${#agent_mods[@]}" -gt 0 ] || [ -n "$agents_note$pruned$kept" ]; then
            echo
            echo "## Agents"
            echo
            [ "${#agent_mods[@]}" -gt 0 ] &&
                echo "- Started for ${agent_mods[*]}; they land after the run, and agents.md says what each did"
            [ -n "$capped" ] && echo "- None for$capped: the cap, $agents_max, was reached"
            [ -n "$agents_note" ] && echo "- $agents_note"
            [ -n "$pruned" ] && echo "- Earlier nights' agents whose work is on main, worktree and branch removed:$pruned"
            [ -n "$kept" ] && echo "- Earlier nights' agents with work not on main, left in .claude/worktrees:$kept"
        fi
        if [ -f "$tree/build/nightly-summary.txt" ] && [ "$tree/build/nightly-summary.txt" -nt "$runs/.nightly.log.started" ]; then
            echo
            echo "## Nightly counts"
            echo
            echo '```'
            sed '/^$/d' "$tree/build/nightly-summary.txt"
            echo '```'
        fi
    } >"$summary"
    say "summary in $summary"
    $dry || echo "$target" >"$home/last-tested"

    # Docker and the machine are free: the agents may build and land.
    rm -f "$lock"
    land_agents
    exit 0
}

main "$@"
