#!/usr/bin/env bash
# Resolve tools/requirements.in and write exact pins to tools/requirements.txt.
# The venv is rebuilt first, so a package dropped from requirements.in drops out of the pins.
set -euo pipefail
rm -rf "$(dirname "${BASH_SOURCE[0]}")/../.venv"
# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/_env.sh"
"$VPY" -m pip install --quiet -r tools/requirements.in
"$VPY" -m pip freeze | LC_ALL=C sort > tools/requirements.txt
echo "locked $(wc -l < tools/requirements.txt) packages"
