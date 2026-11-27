#!/usr/bin/env bash
# Start a Claude Code session as this agent. Extra args become the first task.
# Extra flags via CLAUDE_ARGS, e.g. CLAUDE_ARGS="--model opus".
set -euo pipefail
home="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
name="$(basename "$home")"
case "$home" in
  "$HOME"/.claude/agent-homes/*) ;;   # user scope: stay in current dir
  *) cd "$home/../../.." ;;            # project scope: run from project root
esac
export AGENT_FORGE_AGENT="$name"      # tells the agent-forge hook which agent is active
boot="Start as agent $name. Run your boot protocol, then wait for my task."
if [ "$#" -gt 0 ]; then boot="$boot First task: $*"; fi
# shellcheck disable=SC2086
exec claude ${CLAUDE_ARGS:-} --append-system-prompt-file "$home/PROMPT.md" -n "$name-$(date +%Y%m%d-%H%M)" "$boot"
