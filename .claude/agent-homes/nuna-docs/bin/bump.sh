#!/usr/bin/env bash
# Start a write batch: refuse unless git holds the current version (no uncommitted changes
# in the home; .venv/ is git-ignored), then bump VERSION. Prints the new version.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
dirty="$(git status --porcelain -- .)"
if [ -n "$dirty" ]; then
  echo "refusing: commit the home first, so git holds v$(cat VERSION):" >&2
  echo "$dirty" >&2
  exit 1
fi
cur="$(cat VERSION)"
echo $((cur + 1)) > VERSION
echo $((cur + 1))
