#!/usr/bin/env python3
"""Offline lint for a demo: what must be fixed before anyone sees it.

Checks offline integrity (external or relative references, network calls, unresolved markers), motion (reduced
motion, @supports guards, layout animation), CSS details, typography, copy (placeholders, generic labels, the
simulated-data disclosure), accessibility (lang, title, viewport, alt, button names, focus, token contrast) and
size. Severity: error (fix before showing anyone), warn (should fix), info (consider).

Exit codes: 0 no errors, 1 at least one error, 2 usage error.
"""

from __future__ import annotations

__version__ = "1"

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from ddlib import checks, paths, versioning  # noqa: E402


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="check.py", description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("file", help="a packaged demo (name_vN.html), or a marker source with --source")
    ap.add_argument("--source", action="store_true", help="the file is a marker source: skip the unresolved-marker check")
    ap.add_argument("--brand-dir", type=Path, help="brand folder (default: ~/.atelier/brand)")
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    ap.add_argument("--version", action="version", version=f"%(prog)s {__version__}")
    args = ap.parse_args(argv)
    if args.brand_dir:
        paths.BRAND_DIR = args.brand_dir
    path = Path(args.file)
    if not path.is_file():
        ap.error(f"{path} not found")

    findings = checks.run(versioning.read_text(path), inlined=not args.source)
    summary = checks.summarize(findings)
    if args.json:
        print(json.dumps({"file": str(path), "summary": summary,
                          "findings": [{"id": f.id, "severity": f.severity, "message": f.message, "fix": f.fix}
                                       for f in findings]}, indent=2))
    else:
        print(checks.report_text(findings))
        print(f"{summary['error']} error(s), {summary['warn']} warning(s), {summary['info']} note(s)")
    return 1 if summary["error"] else 0


if __name__ == "__main__":
    sys.exit(main())
