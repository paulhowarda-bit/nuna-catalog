"""Versioned file output. Nothing is ever overwritten: every change gets a new _vN."""

from __future__ import annotations

import re
from pathlib import Path

_VER_RE = re.compile(r"^(?P<stem>.+)_v(?P<n>\d+)$")


def slugify(text: str, max_len: int = 48) -> str:
    s = re.sub(r"[^a-zA-Z0-9]+", "_", text.strip().lower()).strip("_")
    s = s[:max_len].rstrip("_")
    return s or "demo"


def split_version(path: Path) -> tuple[str, int | None]:
    """'trading_v3.html' -> ('trading', 3); 'trading.html' -> ('trading', None)."""
    m = _VER_RE.match(path.stem if path.suffix in (".html", ".htm", ".md", ".json", ".css") else path.name)
    if m:
        return m.group("stem"), int(m.group("n"))
    return path.stem, None


def versions(directory: Path, stem: str, suffix: str) -> list[tuple[int, Path]]:
    """Existing versions of stem_vN<suffix>. suffix "" means versioned *folders* (names may contain dots)."""
    if not directory.exists():
        return []
    out = []
    for p in directory.iterdir():
        if suffix == "":
            if not p.is_dir():
                continue
            name = p.name
        else:
            if p.is_dir() or not p.name.endswith(suffix):
                continue
            name = p.name[: -len(suffix)]
        m = _VER_RE.match(name)
        if m and m.group("stem") == stem:
            out.append((int(m.group("n")), p))
    return sorted(out)


def latest(directory: Path, stem: str, suffix: str) -> Path | None:
    v = versions(directory, stem, suffix)
    return v[-1][1] if v else None


def next_path(directory: Path, stem: str, suffix: str) -> Path:
    v = versions(directory, stem, suffix)
    n = (v[-1][0] + 1) if v else 1
    return directory / f"{stem}_v{n}{suffix}"


def sibling_next(path: Path, suffix: str | None = None) -> Path:
    """Next version in the same family as `path` (e.g. foo_v2.html -> foo_v3.html)."""
    stem, _ = split_version(path)
    return next_path(path.parent, stem, suffix or path.suffix)


def write_new(path: Path, data: str | bytes) -> Path:
    """Create a file that must not already exist (mode 'x'): never overwrite."""
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(data, bytes):
        with open(path, "xb") as f:
            f.write(data)
    else:
        with open(path, "x", encoding="utf-8", newline="\n") as f:
            f.write(data)
    return path


def next_dir(directory: Path, stem: str) -> Path:
    """Create and return the next versioned folder stem_vN (never reuses an existing one)."""
    path = next_path(directory, stem, "")
    path.mkdir(parents=True, exist_ok=False)
    return path


def read_text(path: Path) -> str:
    """Read user files tolerant of a UTF-8 BOM (PowerShell) or legacy Windows-1252 encoding."""
    data = Path(path).read_bytes()
    try:
        return data.decode("utf-8-sig")
    except UnicodeDecodeError:
        return data.decode("cp1252", errors="replace")
