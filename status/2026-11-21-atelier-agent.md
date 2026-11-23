# 2026-11-21, Atelier, a UX expert agent (backlog 119)

Paul asked for Atelier, the team's UX expert and graphic designer, from `demo_designer_v2.zip`. Made with agent-forge
`create` as a project-scope home, `.claude/agent-homes/atelier/`, from the toolkit's knowledge base, vendored
libraries and fonts, example sources and deterministic modules; Claude Code replaces its Bedrock pipeline.

Checked: `bash .claude/agent-homes/atelier/bin/check.sh`, 17 passed (inline: three examples rebuilt to the shipped
files' sha256, run twice identical, repackaging idempotent, unknown marker and modified vendored file refused, dry
run, catalog; check: golden findings for a page breaking one rule per area, examples clean, source mode).
`forge.py render-style atelier --check` up to date; `forge.py whoami` with `AGENT_FORGE_AGENT=atelier` finds the home;
the session context renders at 2,951 of 9,000 characters.

Not covered: Atelier has not run a real brief yet, and the SessionStart hook has not fired in a new session (Claude
Code reads hooks and styles at startup).
