#!/usr/bin/env python3
"""agent-forge runtime (stdlib only).

  forge.py hook session-start          (called through hook.sh by Claude Code's SessionStart hook)
  forge.py whoami                      (which agent is active here)
  forge.py mine <agent> [--min-count N] [--since YYYY-MM-DD] [--json]
  forge.py render-style <agent> [--check | --dry-run]
  forge.py install-hooks [--dry-run]

Hooks never break a session: any error is swallowed and the hook exits 0.
"""
import argparse
import datetime as dt
import json
import os
import re
import shlex
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path

__version__ = "2"

CONTEXT_BUDGET = 9000  # Claude Code moves SessionStart output over 10,000 chars to a file
HOOK_MARK = ".claude/agent-forge/hook.sh"
MAX_INPUT_CHARS = 500

# Secrets must never reach a mining report. Prefix-keeping patterns first, then whole-token ones.
_KEEP_PREFIX = [
    re.compile(r"(?i)(authorization:\s*(?:bearer|basic)\s+)\S+"),
    re.compile(r"(?i)((?:token|password|passwd|secret|api[_-]?key|credential)s?\s*[=:]\s*)\S+"),
    re.compile(r"(?i)(--(?:token|password|secret|api-key|credential)[= ])\S+"),
]
_WHOLE = [
    re.compile(r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b"),           # AWS access key ids
    re.compile(r"\bhv[sbr]\.[A-Za-z0-9_-]{20,}"),            # Vault tokens
]
_OPAQUE = re.compile(r"[A-Za-z0-9+_=-]{32,}")


def _opaque(m):
    """Long strings mixing upper case, lower case and digits look like keys; names don't."""
    s = m.group(0)
    looks_random = any(c.isupper() for c in s) and any(c.islower() for c in s) and any(c.isdigit() for c in s)
    return "<redacted>" if looks_random else s


def redact(text):
    for pat in _KEEP_PREFIX:
        text = pat.sub(lambda m: m.group(1) + "<redacted>", text)
    for pat in _WHOLE:
        text = pat.sub("<redacted>", text)
    return _OPAQUE.sub(_opaque, text)


# ---------- locating the active agent ----------

def project_dir():
    return Path(os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd())


def home_for(name, proj):
    for base in (proj / ".claude" / "agent-homes", Path.home() / ".claude" / "agent-homes"):
        home = base / name
        if (home / "PROMPT.md").is_file():
            return home
    return None


def _load_json(path):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def _read(path):
    try:
        return Path(path).read_text(encoding="utf-8")
    except OSError:
        return ""


def active_agent(proj):
    """start.sh sets AGENT_FORGE_AGENT. Otherwise the output style decides (local > project > user)."""
    name = os.environ.get("AGENT_FORGE_AGENT", "").strip()
    if not name:
        for settings in (proj / ".claude" / "settings.local.json",
                         proj / ".claude" / "settings.json",
                         Path.home() / ".claude" / "settings.json"):
            style = _load_json(settings).get("outputStyle")
            if style:
                name = style
                break
    home = home_for(name, proj) if name else None
    return (name, home) if home else (None, None)


# ---------- hooks ----------

def session_context(name, home):
    version = _read(home / "VERSION").strip() or "?"
    inbox = [l for l in _read(home / "notes" / "inbox.md").splitlines() if l.startswith("- ")]
    parts = [
        f"agent-forge: this session runs as agent '{name}', version {version}. "
        f"Agent home: {home}. Agent paths in the agent's rules are relative to it.",
        f"Unsynced inbox items: {len(inbox)}.",
    ]
    for title, rel in (("Rules learned so far (notes/ways-of-working.md)", "notes/ways-of-working.md"),
                       ("Tool registry (tools/REGISTRY.md)", "tools/REGISTRY.md"),
                       ("Learnings (notes/learnings.md)", "notes/learnings.md")):
        parts.append(f"## {title}\n{_read(home / rel).strip() or '(empty)'}")
    text = "\n\n".join(parts)
    if len(text) > CONTEXT_BUDGET:
        text = text[:CONTEXT_BUDGET].rsplit("\n", 1)[0]
        text += f"\n\n[truncated at {CONTEXT_BUDGET} characters; read the full files under {home / 'notes'}]"
    return text


def summarize_input(tool, tool_input):
    if not isinstance(tool_input, dict):
        return ""
    if tool == "Bash":
        return str(tool_input.get("command", ""))
    for key in ("file_path", "path", "pattern", "url", "query"):
        if key in tool_input:
            return f"{key}={tool_input[key]}"
    return ",".join(sorted(tool_input))


def session_index(name, home):
    """The machine's list of this agent's sessions. It lives in the git common directory, so
    every worktree of a clone shares it, it outlives a worktree's removal, and git never
    tracks it. A home outside git keeps it in the home's logs/."""
    try:
        out = subprocess.run(["git", "rev-parse", "--git-common-dir"], cwd=home,
                             capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return home / "logs" / "sessions.jsonl"
    return (home / out).resolve() / "agent-forge" / name / "sessions.jsonl"


def record_session(name, home, event, now=None):
    """Note where Claude Code keeps this session's transcript; mine reads the tool calls there."""
    transcript = str(event.get("transcript_path") or "")
    if not transcript:
        return
    now = now or dt.datetime.now(dt.timezone.utc)
    record = {"ts": now.strftime("%Y-%m-%dT%H:%M:%SZ"), "session": str(event.get("session_id", "")),
              "source": str(event.get("source", "")), "transcript": transcript}
    index = session_index(name, home)
    index.parent.mkdir(parents=True, exist_ok=True)
    with open(index, "a", encoding="utf-8", newline="\n") as fh:
        fh.write(json.dumps(record, sort_keys=True) + "\n")


def run_hook(kind, stdin):
    try:
        event = json.loads(stdin.read() or "{}")
    except ValueError:
        event = {}
    name, home = active_agent(project_dir())
    if not home:
        return 0  # not an agent session: do nothing
    if kind == "session-start":
        record_session(name, home, event)
        print(json.dumps({"hookSpecificOutput": {"hookEventName": "SessionStart",
                                                 "additionalContext": session_context(name, home)}}))
    return 0


# ---------- mining repeated work ----------

_NUM = re.compile(r"[-+]?\d+(?:\.\d+)?")
_EXT = re.compile(r"\.[A-Za-z0-9]{1,6}$")
_OPS = {"&&", "||", "|", ";", ">", ">>", "<"}


def bash_signature(command):
    """Shape of a command with paths, numbers and long free text abstracted away."""
    try:
        tokens = shlex.split(command, posix=True)
    except ValueError:
        tokens = command.split()
    sig = []
    program_next = True  # the program itself (even ./gradlew) is identity, not a path argument
    for tok in tokens:
        if tok in _OPS:
            sig.append(tok)
            program_next = True
            continue
        if program_next:
            sig.append(tok)
            program_next = False
        elif tok.startswith("-"):
            sig.append(tok.split("=", 1)[0])
        elif _NUM.fullmatch(tok):
            sig.append("<N>")
        elif "/" in tok or "\\" in tok or _EXT.search(tok):
            sig.append("<PATH>")
        elif len(tok) > 24 or " " in tok:
            sig.append("<TEXT>")
        else:
            sig.append(tok)
    return " ".join(sig)


def event_signature(rec):
    tool, inp = rec.get("tool", ""), rec.get("input", "")
    if tool == "Bash":
        return "Bash: " + bash_signature(inp)
    if tool in ("Edit", "Write", "Read") and inp.startswith("file_path="):
        ext = _EXT.search(inp)
        return f"{tool}: *{ext.group(0) if ext else ''}"
    return tool


def registry_tools(home):
    names = []
    for line in _read(home / "tools" / "REGISTRY.md").splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) > 1 and cells[0] and cells[0] not in ("Tool",) and not set(cells[0]) <= set("-: "):
            names.append(cells[0])
    return sorted(set(names))


def transcripts(name, home):
    """Each recorded session's transcript once, oldest first, and how many are gone."""
    seen, found, missing = set(), [], 0
    for line in _read(session_index(name, home)).splitlines():
        try:
            rec = json.loads(line)
        except ValueError:
            continue
        path = rec.get("transcript", "")
        if not path or path in seen:
            continue  # SessionStart also fires on resume, /clear and compaction
        seen.add(path)
        if Path(path).is_file():
            found.append((rec.get("session", ""), Path(path)))
        else:
            missing += 1
    return found, missing


def tool_calls_in(session, transcript):
    """The main conversation's tool calls in a Claude Code transcript, with failures marked."""
    calls, failed = {}, set()
    for line in _read(transcript).splitlines():
        try:
            entry = json.loads(line)
        except ValueError:
            continue
        # api-request-blob entries repeat earlier messages; subagent turns are not the agent's own
        if entry.get("type") not in ("assistant", "user") or entry.get("isSidechain"):
            continue
        content = (entry.get("message") or {}).get("content")
        for block in content if isinstance(content, list) else []:
            if not isinstance(block, dict):
                continue
            if block.get("type") == "tool_use" and block.get("id") not in calls:
                tool = str(block.get("name", ""))
                calls[block.get("id")] = {
                    "ts": str(entry.get("timestamp", "")), "session": session, "tool": tool,
                    "input": redact(summarize_input(tool, block.get("input")))[:MAX_INPUT_CHARS]}
            elif block.get("type") == "tool_result" and block.get("is_error"):
                failed.add(block.get("tool_use_id"))
    for use_id, rec in calls.items():
        rec["status"] = "fail" if use_id in failed else "ok"
    return list(calls.values())


def mine(name, home, min_count=2, since=None):
    sessions = defaultdict(list)
    tool_calls = Counter()
    failures = Counter()
    found, missing = transcripts(name, home)
    records = [rec for session, path in found for rec in tool_calls_in(session, path)]
    for rec in records:
        if since and rec.get("ts", "") < since:
            continue
        sig = event_signature(rec)
        for m in re.finditer(r"tools/([A-Za-z0-9_]+)\.py", rec.get("input", "")):
            tool_calls[m.group(1)] += 1
            if rec.get("status") == "fail":
                failures[m.group(1)] += 1
        if "tools/" in rec.get("input", ""):
            continue  # already a tool: count usage, not a candidate
        sessions[rec.get("session", "")].append(sig)

    def rank(counter, where):
        rows = [{"pattern": k, "count": c, "sessions": len(where[k])}
                for k, c in counter.items() if c >= min_count]
        return sorted(rows, key=lambda r: (-r["sessions"], -r["count"], r["pattern"]))

    singles, single_where = Counter(), defaultdict(set)
    seqs, seq_where = Counter(), defaultdict(set)
    for sid, sigs in sessions.items():
        for s in sigs:
            if s.startswith("Bash: "):
                singles[s] += 1
                single_where[s].add(sid)
        for n in (2, 3, 4):
            for i in range(len(sigs) - n + 1):
                gram = " -> ".join(sigs[i:i + n])
                seqs[gram] += 1
                seq_where[gram].add(sid)
    registered = registry_tools(home)
    return {
        "transcripts": {"read": len(found), "missing": missing},
        "repeated_commands": rank(singles, single_where),
        "repeated_sequences": rank(seqs, seq_where),
        "tool_usage": {t: tool_calls.get(t, 0) for t in sorted(set(registered) | set(tool_calls))},
        "unused_tools": [t for t in registered if tool_calls.get(t, 0) == 0],
        "failing_tools": dict(sorted(failures.items())),
    }


def mine_report(result):
    t = result["transcripts"]
    out = ["# Usage mining report", "",
           f"Session transcripts read: {t['read']}; missing (deleted or on another machine): {t['missing']}",
           "", "## Repeated commands (tool candidates)"]
    out += [f"- {r['count']}x in {r['sessions']} session(s): `{r['pattern']}`"
            for r in result["repeated_commands"]] or ["- none"]
    out += ["", "## Repeated sequences (workflow candidates)"]
    out += [f"- {r['count']}x in {r['sessions']} session(s): `{r['pattern']}`"
            for r in result["repeated_sequences"]] or ["- none"]
    out += ["", "## Tool usage"]
    out += [f"- {t}: {c} call(s)" for t, c in result["tool_usage"].items()] or ["- no tools yet"]
    out += ["", f"Unused tools: {', '.join(result['unused_tools']) or 'none'}",
            f"Failing tools: {', '.join(f'{k} ({v})' for k, v in result['failing_tools'].items()) or 'none'}"]
    return "\n".join(out) + "\n"


# ---------- output style ----------

def render_style(name, home):
    version = _read(home / "VERSION").strip() or "?"
    return (f"---\nname: {name}\ndescription: agent-forge agent {name} (v{version})\n"
            f"keep-coding-instructions: true\n---\n{_read(home / 'PROMPT.md')}")


# ---------- hook installation ----------

def install_hooks(proj, dry_run=False):
    """Add the SessionStart hook to the tracked .claude/settings.json, so every clone and
    worktree has it. hook.sh finds each machine's Python, so one command fits all. Git holds
    the previous file."""
    path = proj / ".claude" / "settings.json"
    raw = _read(path)
    try:
        data = json.loads(raw) if raw.strip() else {}
    except ValueError:
        print(f"refusing: {path} is not valid JSON", file=sys.stderr)
        return 1
    groups = data.setdefault("hooks", {}).setdefault("SessionStart", [])
    if any(HOOK_MARK in h.get("command", "") for g in groups for h in g.get("hooks", [])):
        print("hook already installed; nothing to do")
        return 0
    groups.append({"hooks": [{"type": "command",
                              "command": f'bash "$CLAUDE_PROJECT_DIR/{HOOK_MARK}" session-start'}]})
    new = json.dumps(data, indent=2) + "\n"
    if dry_run:
        print(new, end="")
        return 0
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(new, encoding="utf-8", newline="\n")
    print(f"installed the SessionStart hook in {path}; commit it with the home")
    return 0


# ---------- CLI ----------

def main(argv=None):
    p = argparse.ArgumentParser(prog="forge.py", description="agent-forge runtime")
    p.add_argument("--version", action="version", version=__version__)
    sub = p.add_subparsers(dest="cmd", required=True)
    h = sub.add_parser("hook")
    h.add_argument("kind", choices=["session-start"])
    sub.add_parser("whoami")
    m = sub.add_parser("mine")
    m.add_argument("agent")
    m.add_argument("--min-count", type=int, default=2)
    m.add_argument("--since")
    m.add_argument("--json", action="store_true")
    r = sub.add_parser("render-style")
    r.add_argument("agent")
    g = r.add_mutually_exclusive_group()
    g.add_argument("--check", action="store_true", help="exit 1 if the style file is out of date")
    g.add_argument("--dry-run", action="store_true")
    i = sub.add_parser("install-hooks")
    i.add_argument("--dry-run", action="store_true")
    a = p.parse_args(argv)

    if a.cmd == "hook":
        try:
            return run_hook(a.kind, sys.stdin)
        except Exception:  # a hook must never break the session
            return 0
    proj = project_dir()
    if a.cmd == "whoami":
        name, home = active_agent(proj)
        print(json.dumps({"agent": name, "home": str(home) if home else None}))
        return 0
    if a.cmd == "install-hooks":
        return install_hooks(proj, a.dry_run)
    home = home_for(a.agent, proj)
    if not home:
        print(f"no agent home found for '{a.agent}'", file=sys.stderr)
        return 2
    if a.cmd == "mine":
        result = mine(a.agent, home, a.min_count, a.since)
        print(json.dumps(result, indent=2, sort_keys=True) if a.json else mine_report(result), end="")
        return 0
    if a.cmd == "render-style":
        text = render_style(a.agent, home)
        out = proj / ".claude" / "output-styles" / f"{a.agent}.md"
        if a.dry_run:
            print(text, end="")
            return 0
        if a.check:
            current = _read(out)
            print("up to date" if current == text else f"out of date: {out}")
            return 0 if current == text else 1
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(text, encoding="utf-8")
        print(f"wrote {out}")
        return 0
    return 2


if __name__ == "__main__":
    sys.exit(main())
