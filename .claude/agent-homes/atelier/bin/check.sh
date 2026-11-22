#!/usr/bin/env bash
# Install pinned tool deps into the agent venv, then run all tool tests.
set -euo pipefail
# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/_env.sh"
if [ -s tools/requirements.txt ]; then "$VPY" -m pip install --quiet -r tools/requirements.txt; fi
export PYTHONHASHSEED=0 PYTHONUTF8=1 TZ=UTC
rc=0
"$VPY" -m pytest -q -p no:cacheprovider tools/tests || rc=$?
if [ "$rc" -eq 5 ]; then echo "no tests yet"; rc=0; fi
exit "$rc"
