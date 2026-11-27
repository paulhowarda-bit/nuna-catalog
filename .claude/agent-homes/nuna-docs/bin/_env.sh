# shellcheck shell=bash
# Sourced by check.sh and lock.sh: resolves the home and the agent's venv Python.
# Set PYTHON to force an interpreter. Otherwise the first of python3, python and py that
# actually runs is used: on Windows, python3 is often the Microsoft Store stub, which
# command -v finds but which only prints "Python was not found".
AGENT_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$AGENT_HOME" || exit 1
PY="${PYTHON:-}"
if [ -z "$PY" ]; then
  for c in python3 python py; do
    if "$c" -c 'import sys' >/dev/null 2>&1; then PY="$c"; break; fi
  done
  [ -n "$PY" ] || { echo "no working Python found; set PYTHON" >&2; exit 1; }
fi
[ -d .venv ] || "$PY" -m venv .venv
if [ -x .venv/bin/python ]; then VPY="$AGENT_HOME/.venv/bin/python"; else VPY="$AGENT_HOME/.venv/Scripts/python.exe"; fi
export VPY
