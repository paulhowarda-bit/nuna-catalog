"""Static lint for generated demos. Deterministic, offline, fast. Feeds the critic and gates output.

Severity: error (must fix before showing anyone) | warn (should fix) | info (consider).
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from html.parser import HTMLParser

from . import colors
from .inliner import deinline

BANNED_PRIMARY_FONTS = {"inter", "roboto", "arial", "helvetica", "helvetica neue", "system-ui",
                        "-apple-system", "segoe ui", "sans-serif", "open sans", "lato", "montserrat",
                        "poppins"}
PLACEHOLDER_COPY = re.compile(
    r"\b(lorem ipsum|dolor sit amet|todo|tbd|title here|your text here|click here|placeholder text|"
    r"sample text|insert \w+ here)\b", re.I)
GENERIC_LABELS = {"submit", "ok", "go", "learn more", "read more", "click here", "more", "details", "button"}
DISCLOSURE = re.compile(r"(illustrative|hypothetical|simulated|sample data|demo data|for demonstration|"
                        r"not actual|not real|fictional|synthetic data|modeled|projection)", re.I)
MONEY = re.compile(r"[$€£]\s?\d|\d\s?(bn|mm|k)\b|\bUSD\b|\bP&amp;L\b|\bP&L\b|\bAUM\b", re.I)


@dataclass
class Finding:
    id: str
    severity: str
    message: str
    fix: str = ""

    def line(self) -> str:
        return f"[{self.severity.upper():5}] {self.id} {self.message}" + (f" -> {self.fix}" if self.fix else "")


class _Collector(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.styles: list[str] = []
        self.scripts: list[str] = []
        self.text: list[str] = []
        self.tags: list[tuple[str, dict]] = []
        self.h1: list[str] = []
        self._in: list[str] = []
        self._buttons: list[dict] = []

    def handle_starttag(self, tag, attrs):
        a = {k: (v or "") for k, v in attrs}
        self.tags.append((tag, a))
        self._in.append(tag)
        if tag == "button":
            self._buttons.append({"attrs": a, "text": ""})

    def handle_endtag(self, tag):
        if tag in self._in:
            while self._in and self._in.pop() != tag:
                pass

    def handle_data(self, data):
        cur = self._in[-1] if self._in else ""
        if cur == "style":
            self.styles.append(data)
        elif cur == "script":
            self.scripts.append(data)
        else:
            if data.strip():
                self.text.append(data.strip())
                if "h1" in self._in:
                    self.h1.append(data.strip())
                if "button" in self._in and self._buttons:
                    self._buttons[-1]["text"] += data


def _supports_ranges(css: str) -> list[tuple[int, int]]:
    """Character ranges covered by @supports (...) { ... } blocks (brace-matched)."""
    out = []
    for m in re.finditer(r"@supports[^{]*\{", css):
        depth, i = 1, m.end()
        while i < len(css) and depth:
            if css[i] == "{":
                depth += 1
            elif css[i] == "}":
                depth -= 1
            i += 1
        out.append((m.start(), i))
    return out


def _first_family(decl: str) -> str:
    first = decl.split(",")[0].strip().strip("'\"").lower()
    return first


def run(html: str, inlined: bool = True) -> list[Finding]:
    f: list[Finding] = []
    col = _Collector()
    try:
        col.feed(html)
    except Exception as e:  # malformed html
        f.append(Finding("HTML001", "error", f"HTML could not be parsed: {e}"))
    css = "\n".join(col.styles)
    js = "\n".join(s for s in col.scripts if "inlined for offline use" not in s[:200])
    own_html = deinline(html)  # the page's own code, without vendored libraries
    own_css = "\n".join(s for s in col.styles
                        if "@font-face" not in s[:40] and not re.match(r"\s*/\* [\w-]+ [\d.]+ \| ", s))
    body_text = " ".join(col.text)

    # ---------------------------------------------------------------- offline integrity
    ext = []
    for tag, a in col.tags:
        for attr in ("src", "href", "poster", "data", "srcset"):
            v = a.get(attr, "")
            if re.match(r"\s*(https?:)?//", v):
                if tag == "a" and attr == "href":
                    continue  # navigation links are allowed (they just won't work offline)
                ext.append(f"<{tag} {attr}={v[:60]}>")
    if ext:
        f.append(Finding("OFF001", "error", f"external resource reference(s): {', '.join(ext[:3])}",
                         "Inline it (vendor marker / data URI) - the work network has no internet."))
    if re.search(r"@import\s+(url\()?['\"]?https?://", css, re.I) or \
            re.search(r"url\(\s*['\"]?https?://", css, re.I):
        f.append(Finding("OFF001", "error", "CSS loads a remote URL (url()/@import).", "Inline or remove."))
    if re.search(r"""(fetch|import)\(\s*['"`]https?://|new\s+WebSocket\(|XMLHttpRequest""", js):
        f.append(Finding("OFF002", "error", "script makes network requests.",
                         "Generate data locally (seeded PRNG) so the demo runs offline and repeats exactly."))
    if re.search(r"""(@import\s+(url\()?\s*['"]?\s*//)|(url\(\s*['"]?\s*//)""", css, re.I) or any(
            re.search(r"url\(\s*['\"]?\s*(https?:)?//", a.get("style", ""), re.I) for _, a in col.tags):
        f.append(Finding("OFF001", "error", "CSS or a style attribute loads a remote/protocol-relative URL.",
                         "Inline it or remove it."))
    if any(t == "form" and re.match(r"\s*(https?:)?//", a.get("action", "")) for t, a in col.tags):
        f.append(Finding("OFF002", "error", "a form posts to a remote URL.", "Demos never submit data anywhere."))
    if re.search(r"\b(EventSource|sendBeacon|WebSocket|RTCPeerConnection)\b", js):
        f.append(Finding("OFF002", "error", "script opens a network channel (EventSource/sendBeacon/WebSocket).",
                         "Remove it; the demo must not talk to any server."))
    elif re.search(r"\bfetch\s*\(", js):
        f.append(Finding("OFF002", "warn", "script calls fetch() - blocked offline and by the page's CSP.",
                         "Embed data in the page instead."))
    rel = []
    for tag, a in col.tags:
        if tag in ("script", "img", "link", "source", "video", "audio", "iframe", "embed"):
            for attr in ("src", "href"):
                v = a.get(attr, "").strip()
                if v and not v.startswith(("data:", "blob:", "http", "//", "@asset:", "javascript:", "mailto:", "#")):
                    rel.append(v)
    if rel:
        f.append(Finding("OFF003", "error", f"relative file reference(s) break the single-file rule: {rel[:3]}",
                         "Inline as data URI or @asset:NAME."))
    unresolved = re.findall(r"<!--\s*@(vendor|font)\s+([\w\-]+)\s*-->|@asset:[\w\-.]+", html) if inlined else []
    if unresolved:
        f.append(Finding("OFF004", "error", f"unresolved marker(s): {unresolved[:4]}",
                         "Use only names from the offline catalog."))

    # ---------------------------------------------------------------- motion
    animates = bool(re.search(r"@keyframes|transition\s*:|animation\s*:|requestAnimationFrame|\.animate\(|"
                              r"anime\.|animate\(|createTimeline", css + js))
    if animates and "prefers-reduced-motion" not in own_html:
        f.append(Finding("MOT001", "error", "motion present but no prefers-reduced-motion handling.",
                         "Add @media (prefers-reduced-motion: reduce) and a JS matchMedia check that swaps "
                         "movement for crossfades and stops loops."))
    sup = _supports_ranges(css)
    for m in re.finditer(r"(animation-timeline|view-timeline|scroll-timeline|animation-range)\s*:", css):
        if not any(a <= m.start() < b for a, b in sup):
            f.append(Finding("MOT002", "error", f"`{m.group(1)}` used outside an @supports guard.",
                             "Wrap in @supports (animation-timeline: view()) { ... } and keep content visible "
                             "by default; browser support is not universal."))
            break
    if re.search(r"transition(-property)?\s*:\s*all\b", own_css):
        f.append(Finding("MOT003", "warn", "`transition: all` animates layout properties and causes jank.",
                         "List properties explicitly (transform, opacity, color, background-color)."))
    layout_kf = re.findall(r"@keyframes[^{]+\{(?:[^{}]*\{[^{}]*\b(width|height|top|left|right|bottom|margin|"
                           r"padding)\s*:[^{}]*\})+", own_css)
    if layout_kf:
        f.append(Finding("PERF003", "warn", "keyframes animate layout properties.",
                         "Animate transform/opacity/filter/clip-path instead; they stay on the compositor."))
    if own_css.count("will-change") > 6:
        f.append(Finding("PERF002", "warn", "will-change used on many elements.",
                         "Apply will-change only while an element is about to animate."))

    # ---------------------------------------------------------------- css details
    bf = len(re.findall(r"(?<!-webkit-)backdrop-filter\s*:", own_css))
    wbf = len(re.findall(r"-webkit-backdrop-filter\s*:", own_css))
    if bf and wbf < bf:
        f.append(Finding("CSS001", "warn", "backdrop-filter without the -webkit- prefix pair.",
                         "Add -webkit-backdrop-filter next to every backdrop-filter (Safari)."))
    if bf and "prefers-reduced-transparency" not in own_css:
        f.append(Finding("CSS002", "info", "glass effects without prefers-reduced-transparency fallback.",
                         "Provide a solid surface under @media (prefers-reduced-transparency: reduce)."))

    # ---------------------------------------------------------------- typography
    fams = re.findall(r"font-family\s*:\s*([^;}{]+)", own_css)
    banned = sorted({_first_family(x) for x in fams if _first_family(x) in BANNED_PRIMARY_FONTS})
    if banned:
        f.append(Finding("TYP001", "warn", f"generic primary font(s): {banned}",
                         "Lead with a characterful vendored family; keep generics only as fallbacks."))
    digits = len(re.findall(r"\d[\d,.]*", body_text + js))
    if digits > 40 and not re.search(r"tabular-nums|font-variant-numeric|monospace|mono'", own_css, re.I):
        f.append(Finding("TYP002", "warn", "number-heavy screen without tabular figures.",
                         "font-variant-numeric: tabular-nums on every number that updates or aligns."))
    for h in col.h1:
        if len(h.split()) > 12:
            f.append(Finding("CPY003", "warn", f"H1 is {len(h.split())} words: '{h[:60]}...'",
                             "Cut the headline to <= 9 words; move detail to the subhead."))

    # ---------------------------------------------------------------- copy
    ph = sorted({m.group(0).lower() for m in PLACEHOLDER_COPY.finditer(body_text)} |
                {t.lower() for t in col.text if t.strip().lower() in GENERIC_LABELS})
    if ph:
        f.append(Finding("CPY001", "warn", f"placeholder/generic copy: {ph}",
                         "Replace with specific, verb-led labels that say what happens or what it means."))
    if MONEY.search(body_text + js) and not DISCLOSURE.search(body_text):  # disclosure must be VISIBLE
        f.append(Finding("DATA001", "warn", "financial figures shown without an 'illustrative data' label.",
                         "Add a visible, persistent disclosure, e.g. 'Illustrative data - not actual client "
                         "accounts or results.'"))

    # ---------------------------------------------------------------- accessibility
    if not re.search(r"<html[^>]*\blang=", html, re.I):
        f.append(Finding("A11Y001", "warn", "<html> has no lang attribute.", '<html lang="en">'))
    if not re.search(r"<title>[^<]+</title>", html, re.I):
        f.append(Finding("A11Y002", "warn", "missing <title>.", "A 2-5 word title."))
    if not re.search(r"<meta[^>]+name=[\"']viewport", html, re.I):
        f.append(Finding("A11Y003", "warn", "missing viewport meta.",
                         '<meta name="viewport" content="width=device-width, initial-scale=1">'))
    noalt = [a for t, a in col.tags if t == "img" and "alt" not in a]
    if noalt:
        f.append(Finding("A11Y004", "warn", f"{len(noalt)} <img> without alt.", 'alt="" if decorative.'))
    nameless = [b for b in col._buttons if not b["text"].strip() and not b["attrs"].get("aria-label")
                and not b["attrs"].get("title")]
    if nameless:
        f.append(Finding("A11Y005", "warn", f"{len(nameless)} button(s) without an accessible name.",
                         "Visible text or aria-label."))
    if re.search(r"outline\s*:\s*(none|0)\b", own_css) and ":focus-visible" not in own_css:
        f.append(Finding("A11Y006", "warn", "focus outlines removed without :focus-visible styles.",
                         "Style :focus-visible with a clear ring."))
    for fg, bg, ratio in colors.token_contrast_pairs(own_css):
        if ratio < 3.0:
            f.append(Finding("CLR001", "error", f"contrast {fg} on {bg} = {ratio:.2f}:1",
                             "Body text needs >= 4.5:1, large text >= 3:1. Adjust OKLCH lightness."))
        elif ratio < 4.5:
            f.append(Finding("CLR002", "warn", f"contrast {fg} on {bg} = {ratio:.2f}:1 (ok only for large text)",
                             "Raise lightness difference for body-size text."))

    # ---------------------------------------------------------------- robustness / size
    if "localStorage" in js and "try" not in js:
        f.append(Finding("JS001", "warn", "localStorage used without try/catch.",
                         "Wrap storage access; it can throw in locked-down browsers."))
    size = len(html.encode("utf-8"))
    if size > 15_000_000:
        f.append(Finding("PERF001", "error", f"file is {size / 1e6:.1f} MB.", "Drop a library or compress assets."))
    elif size > 8_000_000:
        f.append(Finding("PERF001", "warn", f"file is {size / 1e6:.1f} MB.", "Consider dropping a heavy library."))
    return f


def summarize(findings: list[Finding]) -> dict[str, int]:
    out = {"error": 0, "warn": 0, "info": 0}
    for x in findings:
        out[x.severity] = out.get(x.severity, 0) + 1
    return out


def report_text(findings: list[Finding]) -> str:
    if not findings:
        return "No lint findings."
    order = {"error": 0, "warn": 1, "info": 2}
    return "\n".join(x.line() for x in sorted(findings, key=lambda x: order.get(x.severity, 3)))
