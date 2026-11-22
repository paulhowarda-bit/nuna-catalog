#!/usr/bin/env python3
"""Package a demo source into one self-contained, offline HTML file.

Resolves the offline markers (<!-- @vendor NAME -->, <!-- @font NAME -->, @asset:NAME), checks every vendored file
against vendor/manifest_v1.json, adds a Content-Security-Policy that blocks all network traffic, and writes
<stem>_vN.html with the next free N, so nothing is ever overwritten. The source may be a marker file
(name.src.html) or an earlier packaged version, which is collapsed back to markers first.

Exit codes: 0 written (or would be, with --dry-run), 1 failed (unknown marker, missing or modified vendored file,
lint errors with --strict), 2 usage error.
"""

from __future__ import annotations

__version__ = "1"

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from ddlib import checks, inliner, paths, versioning  # noqa: E402


def target_for(src: Path, out_dir: Path | None) -> Path:
    stem = versioning.split_version(src)[0]
    if stem.endswith(".src"):
        stem = stem[: -len(".src")]
    return versioning.next_path(out_dir or src.parent, stem, ".html")


def catalog_json() -> dict:
    man = inliner.load_manifest()
    return {
        "vendors": [{k: lib[k] for k in ("name", "version", "license", "global", "use_when") if k in lib}
                    for lib in man["libraries"]],
        "fonts": [{k: f[k] for k in ("name", "family", "style", "weight", "category", "personality") if k in f}
                  for f in man["fonts"]],
        "assets": sorted(inliner.brand_assets()),
    }


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="inline.py", description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("source", nargs="?", help="marker-form HTML (name.src.html) or a packaged name_vN.html")
    ap.add_argument("--out-dir", type=Path, help="folder for name_vN.html (default: the source's folder)")
    ap.add_argument("--brand-dir", type=Path, help="brand folder for @asset:NAME (default: ~/.atelier/brand)")
    ap.add_argument("--catalog", action="store_true", help="list the libraries, fonts and assets markers can name")
    ap.add_argument("--dry-run", action="store_true", help="report what would be written; write nothing")
    ap.add_argument("--strict", action="store_true", help="fail, writing nothing, if the lint finds errors")
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    ap.add_argument("--version", action="version", version=f"%(prog)s {__version__}")
    args = ap.parse_args(argv)
    if args.brand_dir:
        paths.BRAND_DIR = args.brand_dir

    if args.catalog:
        print(json.dumps(catalog_json(), indent=2, sort_keys=True) if args.json else inliner.catalog_text())
        return 0
    if not args.source:
        ap.error("a source file is required (or --catalog)")
    src = Path(args.source)
    if not src.is_file():
        ap.error(f"{src} not found")

    report: dict = {"source": str(src)}
    try:
        html, rep = inliner.inline(versioning.read_text(src))
    except inliner.InlineError as e:
        report.update(ok=False, error=str(e))
        print(json.dumps(report, indent=2) if args.json else f"FAIL {e}")
        return 1
    findings = checks.run(html)
    target = target_for(src, args.out_dir)
    report.update(target=str(target), vendors=rep.vendors, fonts=rep.fonts, assets=rep.assets,
                  unknown=rep.unknown, bytes=rep.bytes_out, lint=checks.summarize(findings),
                  findings=[{"id": f.id, "severity": f.severity, "message": f.message, "fix": f.fix}
                            for f in findings])
    failed = bool(rep.unknown) or (args.strict and report["lint"]["error"] > 0)
    report["ok"] = not failed
    report["written"] = not failed and not args.dry_run
    if report["written"]:
        versioning.write_new(target, html)

    if args.json:
        print(json.dumps(report, indent=2))
    else:
        if rep.unknown:
            print(f"FAIL unknown marker(s): {', '.join(rep.unknown)}; `inline.py --catalog` lists the names")
        verb = "wrote" if report["written"] else "would write" if not failed else "did not write"
        print(f"{verb} {target} ({rep.bytes_out / 1e6:.2f} MB; vendors: {', '.join(rep.vendors) or 'none'}; "
              f"fonts: {', '.join(rep.fonts) or 'none'})")
        print(checks.report_text(findings))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
