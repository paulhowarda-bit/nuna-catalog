# atelier decisions
- 2026-11-21: Created as an agent-forge home in nuna-catalog, at Paul's request ("create atelier our graphics designer agent", "she is a UX expert"). Why: agent homes live in the project repo, not user scope (Paul, 2026-11-18).
- 2026-11-21: Claude Code is her brain; the toolkit's Bedrock client, prompt templates, pipeline, doctor and installers were left out, and its prompts' rules went into PROMPT.md. Why: an in-session agent needs no gateway, and the method already lives in knowledge/14.
- 2026-11-21: Only the example sources are kept; the packaged files (2.9 MB) are rebuilt by tools/inline.py, and their sha256 values are the golden test. Why: smaller history, and the test proves the rebuild exact.
- 2026-11-21: Demos go where the requester says, otherwise ~/atelier-out/<name>/. Why: work products are not the agent's knowledge, and a worktree's ignored files vanish with it.
