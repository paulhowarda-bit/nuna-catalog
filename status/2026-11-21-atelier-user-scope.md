# 2026-11-21, Atelier moves to user scope (backlog 120)

Paul asked for Atelier to be a user-level agent, hours after item 119 landed her here. Her home is now
`~/.claude/agent-homes/atelier` (its own git repository; v1 is this repo's copy, v2 the move), with the agent-forge
runtime and SessionStart hook in `~/.claude/agent-forge/` and `~/.claude/settings.json`, the wrapper
`~/.claude/agents/atelier.md` and the output style `~/.claude/output-styles/atelier.md`. This branch removes the
project copy and, since she was this repo's only agent-forge agent, the project's runtime and hook, so nuna sessions
neither prefer the stale copy nor load her context twice.

Checked: her 17 tests pass in the user-level home; `forge.py render-style atelier --check` is up to date there.
Not covered: none of the user-level pieces is in any repository but her home's own; another machine needs them set up.
