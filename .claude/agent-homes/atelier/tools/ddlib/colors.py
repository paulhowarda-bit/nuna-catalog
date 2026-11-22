"""Color parsing (hex, rgb, hsl, oklch, oklab) and WCAG 2.x contrast. No dependencies."""

from __future__ import annotations

import math
import re

NAMED = {"white": (1.0, 1.0, 1.0), "black": (0.0, 0.0, 0.0), "transparent": None}


def _num(tok: str, pct_scale: float = 1.0) -> float:
    tok = tok.strip()
    if tok == "none":
        return 0.0
    if tok.endswith("%"):
        return float(tok[:-1]) / 100.0 * pct_scale
    if tok.endswith("deg"):
        return float(tok[:-3])
    if tok.endswith("turn"):
        return float(tok[:-4]) * 360
    if tok.endswith("rad"):
        return math.degrees(float(tok[:-3]))
    return float(tok)


def _split(inner: str) -> list[str]:
    inner = inner.replace(",", " ").replace("/", " / ")
    parts = [p for p in inner.split() if p]
    if "/" in parts:
        parts = parts[: parts.index("/")]
    return parts


def _srgb_to_lin(c: float) -> float:
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def _lin_to_srgb(c: float) -> float:
    c = min(max(c, 0.0), 1.0)
    return 12.92 * c if c <= 0.0031308 else 1.055 * c ** (1 / 2.4) - 0.055


def oklab_to_srgb(L: float, a: float, b: float) -> tuple[float, float, float]:
    l_ = L + 0.3963377774 * a + 0.2158037573 * b
    m_ = L - 0.1055613458 * a - 0.0638541728 * b
    s_ = L - 0.0894841775 * a - 1.2914855480 * b
    l, m, s = l_ ** 3, m_ ** 3, s_ ** 3
    r = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
    g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
    bb = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
    return _lin_to_srgb(r), _lin_to_srgb(g), _lin_to_srgb(bb)


def srgb_to_oklch(r: float, g: float, b: float) -> tuple[float, float, float]:
    r, g, b = _srgb_to_lin(r), _srgb_to_lin(g), _srgb_to_lin(b)
    l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
    m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
    s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b
    l_, m_, s_ = (math.copysign(abs(v) ** (1 / 3), v) for v in (l, m, s))
    L = 0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_
    A = 1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_
    B = 0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_
    C = math.hypot(A, B)
    H = math.degrees(math.atan2(B, A)) % 360 if C > 2e-4 else 0.0   # achromatic: hue is meaningless
    return L, C, H


def hex_to_oklch_css(hex_color: str) -> str:
    rgb = parse(hex_color)
    if not rgb:
        return ""
    L, C, H = srgb_to_oklch(*rgb)
    return f"oklch({L * 100:.1f}% {C:.3f} {H:.1f})"


def _hsl(h: float, s: float, l: float) -> tuple[float, float, float]:
    def f(n):
        k = (n + h / 30) % 12
        a = s * min(l, 1 - l)
        return l - a * max(-1, min(k - 3, 9 - k, 1))
    return f(0), f(8), f(4)


def parse(value: str) -> tuple[float, float, float] | None:
    """Return sRGB 0..1 or None if not a parseable opaque-ish color."""
    v = value.strip().lower()
    if v in NAMED:
        return NAMED[v]
    m = re.fullmatch(r"#([0-9a-f]{3,8})", v)
    if m:
        h = m.group(1)
        if len(h) in (3, 4):
            h = "".join(c * 2 for c in h[:3])
        elif len(h) in (6, 8):
            h = h[:6]
        else:
            return None
        return tuple(int(h[i:i + 2], 16) / 255 for i in (0, 2, 4))  # type: ignore[return-value]
    m = re.fullmatch(r"(rgba?|hsla?|oklch|oklab)\((.*)\)", v)
    if not m or " from " in f" {m.group(2)} ":
        return None
    fn, parts = m.group(1), _split(m.group(2))
    try:
        if fn.startswith("rgb") and len(parts) >= 3:
            return tuple(min(max(_num(p, 255) / 255, 0), 1) for p in parts[:3])  # type: ignore[return-value]
        if fn.startswith("hsl") and len(parts) >= 3:
            sat = _num(parts[1]) if parts[1].endswith("%") else _num(parts[1]) / 100   # unitless = percent
            lig = _num(parts[2]) if parts[2].endswith("%") else _num(parts[2]) / 100
            return _hsl(_num(parts[0]) % 360, sat, lig)
        if fn == "oklch" and len(parts) >= 3:
            L = _num(parts[0])
            L = L if not parts[0].endswith("%") else L  # % already scaled to 0..1
            C = _num(parts[1], 0.4)
            H = math.radians(_num(parts[2]))
            return oklab_to_srgb(L, C * math.cos(H), C * math.sin(H))
        if fn == "oklab" and len(parts) >= 3:
            return oklab_to_srgb(_num(parts[0]), _num(parts[1], 0.4), _num(parts[2], 0.4))
    except ValueError:
        return None
    return None


def alpha(value: str) -> float:
    """Alpha channel of a CSS color (1.0 when opaque or unknown)."""
    v = value.strip().lower()
    m = re.fullmatch(r"#([0-9a-f]{4}|[0-9a-f]{8})", v)
    if m:
        h = m.group(1)
        return int(h[3] * 2 if len(h) == 4 else h[6:8], 16) / 255
    m = re.fullmatch(r"(rgba?|hsla?|oklch|oklab)\((.*)\)", v)
    if not m:
        return 0.0 if v == "transparent" else 1.0
    inner = m.group(2).replace(",", " ")
    if "/" in inner:
        a = inner.split("/")[-1].strip()
    else:
        toks = inner.split()
        a = toks[3] if m.group(1) in ("rgba", "hsla") and len(toks) >= 4 else "1"
    try:
        return float(a[:-1]) / 100 if a.endswith("%") else float(a)
    except ValueError:
        return 1.0


def luminance(rgb: tuple[float, float, float]) -> float:
    r, g, b = (_srgb_to_lin(c) for c in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(a: tuple[float, float, float], b: tuple[float, float, float]) -> float:
    la, lb = luminance(a), luminance(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def _strip_at_blocks(css: str) -> str:
    """Remove @media/@supports/@container blocks so only top-level (default theme) rules remain."""
    out, i = [], 0
    for m in re.finditer(r"@(media|supports|container)[^{]*\{", css):
        if m.start() < i:
            continue
        out.append(css[i:m.start()])
        depth, j = 1, m.end()
        while j < len(css) and depth:
            depth += {"{": 1, "}": -1}.get(css[j], 0)
            j += 1
        i = j
    out.append(css[i:])
    return "".join(out)


def root_tokens(css: str) -> dict[str, str]:
    """Custom properties declared in top-level :root { } blocks (the default theme)."""
    tokens: dict[str, str] = {}
    css = re.sub(r"/\*.*?\*/", "", css, flags=re.S)
    for m in re.finditer(r"(?<![\w-]):root\s*\{([^{}]*)\}", _strip_at_blocks(css)):
        for decl in m.group(1).split(";"):
            if ":" in decl and decl.strip().startswith("--"):
                name, val = decl.split(":", 1)
                tokens.setdefault(name.strip(), val.strip())
    # resolve one level of var() indirection
    for k, v in list(tokens.items()):
        mm = re.fullmatch(r"var\((--[\w-]+)(?:,[^)]*)?\)", v)
        if mm and mm.group(1) in tokens:
            tokens[k] = tokens[mm.group(1)]
    return tokens


FG_HINT = re.compile(r"(text|fg|ink|foreground|on-|copy|body|heading|label)", re.I)
BG_HINT = re.compile(r"(^--bg|background|surface|canvas|paper|base|page|panel|card)", re.I)
SKIP_HINT = re.compile(r"(border|line|grid|shadow|glow|ring|outline|stroke|accent|up|down|pos|neg|chart|series)",
                       re.I)


def token_contrast_pairs(css: str) -> list[tuple[str, str, float]]:
    toks = root_tokens(css)
    opaque = {k: v for k, v in toks.items() if alpha(v) >= 0.9}
    fgs = {k: parse(v) for k, v in opaque.items() if FG_HINT.search(k) and not SKIP_HINT.search(k)}
    bgs = {k: parse(v) for k, v in opaque.items()
           if BG_HINT.search(k) and not FG_HINT.search(k) and not SKIP_HINT.search(k)}
    out = []
    for fk, fv in fgs.items():
        if not fv:
            continue
        for bk, bv in bgs.items():
            if bv:
                out.append((fk, bk, contrast(fv, bv)))
    return out
