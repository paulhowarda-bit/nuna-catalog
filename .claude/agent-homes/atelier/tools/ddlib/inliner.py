"""Resolve offline markers into a single self-contained HTML file, and reverse it for the LLM.

Markers the model writes (anywhere in the document):
    <!-- @vendor anime -->            -> inlined <script> (+ <style> if the library has CSS)
    <!-- @font space-grotesk -->      -> inlined @font-face with a base64 woff2 data URI
    @asset:logo                       -> data URI of brand/assets/<logo.*> (usable in src="" or url())

Inlined blocks are wrapped in <!-- @vendor-begin ... --> / <!-- @vendor-end ... --> comments so a
finished file can be turned back into its small marker form before being sent to the model.
"""

from __future__ import annotations

import base64
import hashlib
import json
import mimetypes
import re
from dataclasses import dataclass, field
from pathlib import Path

from . import paths, versioning

VENDOR_RE = re.compile(r"<!--\s*@vendor\s+([a-z0-9\-]+)\s*-->", re.I)
FONT_RE = re.compile(r"<!--\s*@font\s+([a-z0-9\-]+)\s*-->", re.I)
ASSET_RE = re.compile(r"@asset:([a-zA-Z0-9_\-\.]+)")
BLOCK_RE = re.compile(
    r"<!--\s*@(vendor|font)-begin\s+([a-z0-9\-]+)[^>]*-->.*?<!--\s*@\1-end\s+\2\s*-->", re.S | re.I
)
CSP = ("<!-- @csp-begin -->\n<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; "
       "script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; "
       "media-src data: blob:; connect-src 'none'; form-action 'none'; base-uri 'none'\">\n<!-- @csp-end -->")
CSP_RE = re.compile(r"<!--\s*@csp-begin\s*-->.*?<!--\s*@csp-end\s*-->\n?", re.S)
BLOB_RE = re.compile(r"data:[a-z]+/[a-z0-9.+\-]+;base64,[A-Za-z0-9+/=]{2000,}")


class InlineError(ValueError):
    pass


def load_manifest() -> dict:
    path = versioning.latest(paths.VENDOR_DIR, "manifest", ".json")
    if not path:
        raise InlineError("vendor/manifest_vN.json is missing - the package is incomplete.")
    return json.loads(path.read_text(encoding="utf-8"))


def brand_assets() -> dict[str, Path]:
    """Latest brand asset folder: brand/assets_vN/*"""
    if not paths.BRAND_DIR.exists():
        return {}
    dirs = sorted(
        (int(p.name.split("_v")[-1]), p) for p in paths.BRAND_DIR.glob("assets_v*") if p.is_dir()
        and p.name.split("_v")[-1].isdigit()
    )
    if not dirs:
        return {}
    files = [p for p in sorted(dirs[-1][1].iterdir()) if p.is_file()]
    stems = [p.stem for p in files]
    # key by stem when unique (logo), else by full name (logo.svg / logo.png) so assets never collide
    return {(p.stem if stems.count(p.stem) == 1 else p.name): p for p in files}


def _data_uri(path: Path) -> str:
    mt = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    if path.suffix == ".woff2":
        mt = "font/woff2"
    if path.suffix == ".svg":
        mt = "image/svg+xml"
    return f"data:{mt};base64,{base64.b64encode(path.read_bytes()).decode('ascii')}"


def _js_safe(code: str) -> str:
    return re.sub(r"</(script)", r"<\\/\1", code, flags=re.I)


def _add_csp(html: str) -> str:
    """Belt and braces: even if a model (or injected brand text) adds a tracker, the browser blocks every
    network request from the demo. Inline scripts/styles and data: URIs keep working."""
    m = re.search(r"<meta[^>]+charset[^>]*>", html, re.I) or re.search(r"<head[^>]*>", html, re.I)
    if not m:
        return html
    return html[: m.end()] + "\n" + CSP + html[m.end():]


@dataclass
class InlineReport:
    vendors: list[str] = field(default_factory=list)
    fonts: list[str] = field(default_factory=list)
    assets: list[str] = field(default_factory=list)
    unknown: list[str] = field(default_factory=list)
    bytes_out: int = 0


def inline(html: str, verify: bool = True) -> tuple[str, InlineReport]:
    man = load_manifest()
    libs = {l["name"]: l for l in man["libraries"]}
    fonts = {f["name"]: f for f in man["fonts"]}
    rep = InlineReport()
    html = deinline(html)  # idempotent: start from marker form
    if "three" in [m.lower() for m in VENDOR_RE.findall(html)] and \
            "three-webgpu" in [m.lower() for m in VENDOR_RE.findall(html)]:
        rep.unknown.append("three+three-webgpu (load only one)")

    seen: set[str] = set()

    def vendor_sub(m: re.Match) -> str:
        name = m.group(1).lower()
        if name in seen:
            return ""  # dedupe repeated markers
        seen.add(name)
        lib = libs.get(name)
        if not lib:
            rep.unknown.append(f"vendor:{name}")
            return m.group(0)
        out = [f"<!-- @vendor-begin {name} {lib['version']} {lib['license']} -->"]
        for rel in lib.get("css", []):
            p = paths.VENDOR_DIR / rel
            _verify(p, lib["sha256"][rel], verify)
            out.append(f"<style>/* {name} {lib['version']} | {lib['license']} */\n{p.read_text('utf-8')}\n</style>")
        for rel in lib["js"]:
            p = paths.VENDOR_DIR / rel
            _verify(p, lib["sha256"][rel], verify)
            out.append(f"<script>/*! {name} {lib['version']} | {lib['license']} | inlined for offline use */\n"
                       f"{_js_safe(p.read_text('utf-8'))}\n</script>")
        out.append(f"<!-- @vendor-end {name} -->")
        rep.vendors.append(name)
        return "\n".join(out)

    def font_sub(m: re.Match) -> str:
        name = m.group(1).lower()
        if f"font:{name}" in seen:
            return ""
        seen.add(f"font:{name}")
        f = fonts.get(name)
        if not f:
            rep.unknown.append(f"font:{name}")
            return m.group(0)
        p = paths.VENDOR_DIR / "fonts" / f["file"]
        _verify(p, f["sha256"], verify)
        rep.fonts.append(name)
        return (f"<!-- @font-begin {name} OFL-1.1 -->\n<style>@font-face{{font-family:'{f['family']}';"
                f"src:url({_data_uri(p)}) format('woff2');font-weight:{f['weight']};font-style:{f['style']};"
                f"font-display:swap;}}</style>\n<!-- @font-end {name} -->")

    html = VENDOR_RE.sub(vendor_sub, html)
    html = FONT_RE.sub(font_sub, html)

    assets = brand_assets()

    def asset_sub(m: re.Match) -> str:
        key = m.group(1)
        p = assets.get(key)
        if not p:
            rep.unknown.append(f"asset:{key}")
            return m.group(0)
        rep.assets.append(key)
        return _data_uri(p)

    html = ASSET_RE.sub(asset_sub, html)
    html = _add_csp(html)
    rep.bytes_out = len(html.encode("utf-8"))
    return html, rep


def _verify(path: Path, expected: str, verify: bool) -> None:
    if not path.exists():
        raise InlineError(f"Vendored file missing: {path}")
    if verify and hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        raise InlineError(f"Checksum mismatch for {path} - the vendored file was modified or corrupted.")


def deinline(html: str) -> str:
    """Collapse inlined vendor/font blocks back to markers and known asset data URIs back to @asset:."""

    def back(m: re.Match) -> str:
        return f"<!-- @{m.group(1).lower()} {m.group(2).lower()} -->"

    html = BLOCK_RE.sub(back, html)
    html = CSP_RE.sub("", html)
    for name, p in brand_assets().items():
        uri = _data_uri(p)
        if uri in html:
            html = html.replace(uri, f"@asset:{name}")
    return html


class BlobTable:
    """Swap large unknown base64 blobs for short tokens while text goes through the LLM, then restore."""

    def __init__(self) -> None:
        self.table: dict[str, str] = {}

    def hide(self, html: str) -> str:
        def sub(m: re.Match) -> str:
            key = f"@blob:{hashlib.sha1(m.group(0).encode()).hexdigest()[:10]}"
            self.table[key] = m.group(0)
            return key

        return BLOB_RE.sub(sub, html)

    def restore(self, html: str) -> str:
        for k, v in self.table.items():
            html = html.replace(k, v)
        return html


def for_llm(html: str) -> tuple[str, BlobTable]:
    bt = BlobTable()
    return bt.hide(deinline(html)), bt


def catalog_text() -> str:
    """Human/LLM-readable list of what can be inlined."""
    man = load_manifest()
    lines = ["## Offline library catalog (only these exist; reference them with markers)", ""]
    for l in man["libraries"]:
        size = sum((paths.VENDOR_DIR / r).stat().st_size for r in l["js"] + l["css"]) // 1024
        lines.append(f"- `<!-- @vendor {l['name']} -->` - {l['name']} {l['version']} ({l['license']}, ~{size} KB). "
                     f"Global: `{l['global']}`. {l['use_when']}")
    lines += ["", "## Offline font catalog (reference with `<!-- @font NAME -->`, then use the family name)", ""]
    for f in man["fonts"]:
        lines.append(f"- `<!-- @font {f['name']} -->` -> font-family: '{f['family']}' ({f['style']}, weights "
                     f"{f['weight']}) - {f['category']}; {f['personality']}")
    assets = brand_assets()
    if assets:
        lines += ["", "## Brand assets (use `@asset:NAME` inside src=\"\" or url())", ""]
        lines += [f"- `@asset:{k}` ({p.suffix[1:]}, {p.stat().st_size // 1024} KB)" for k, p in assets.items()]
    return "\n".join(lines)
