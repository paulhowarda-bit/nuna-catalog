#!/usr/bin/env bash
# Claude Code hook entry point, tracked with the repo so every clone and worktree has it.
# Runs forge.py with the first Python 3.7+ that works, trying python3 first on Linux and macOS
# and python first on Windows, where python3 is often the Microsoft Store stub that only says
# "Python was not found". Set PYTHON to force one. A hook never breaks a session.
forge="$(dirname "${BASH_SOURCE[0]}")/forge.py"
case "${OSTYPE:-}" in
  msys* | cygwin*) order="python py python3" ;;
  *) order="python3 python" ;;
esac
IFS= read -r -d '' input || true
for py in "${PYTHON:-}" $order; do
  [ -n "$py" ] || continue
  # forge.py's hook always exits 0, so a failure here means this interpreter does not work
  if out="$(printf '%s' "$input" | "$py" "$forge" hook "$@" 2>/dev/null)"; then
    [ -z "$out" ] || printf '%s\n' "$out"
    exit 0
  fi
done
exit 0
