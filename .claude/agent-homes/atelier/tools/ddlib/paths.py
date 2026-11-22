"""Where Atelier's tools find their inputs: the home's vendor/ folder, and the per-machine brand folder.

Brand packs and brand assets can hold an employer's material, so they never live in the home (which is in git):
they default to ~/.atelier/brand, ATELIER_BRAND_DIR overrides that, and each tool's --brand-dir overrides both.
"""

from __future__ import annotations

import os
from pathlib import Path

HOME = Path(__file__).resolve().parents[2]  # tools/ddlib -> tools -> the agent home
VENDOR_DIR = HOME / "vendor"
BRAND_DIR = Path(os.environ.get("ATELIER_BRAND_DIR") or (Path.home() / ".atelier" / "brand"))
