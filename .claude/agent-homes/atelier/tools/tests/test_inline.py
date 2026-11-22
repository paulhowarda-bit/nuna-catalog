import hashlib
import json
import shutil

import pytest

import inline
from conftest import EXAMPLES, FIXTURES
from ddlib import inliner, paths


def expected_hashes():
    out = {}
    for line in (FIXTURES / "examples_v1.sha256").read_text("utf-8").splitlines():
        digest, name = line.split()
        out[name] = digest
    return out


def sources():
    return sorted(EXAMPLES.glob("*.src.html"))


@pytest.mark.parametrize("src", sources(), ids=lambda p: p.name)
def test_examples_rebuild_byte_identical_to_the_shipped_files(src, tmp_path, no_brand, capsys):
    """Golden: the zip shipped name_v1.html for each source; inline.py must rebuild it exactly."""
    out = tmp_path / "out"
    assert inline.main([str(src), "--out-dir", str(out), "--brand-dir", str(no_brand), "--json"]) == 0
    report = json.loads(capsys.readouterr().out)
    target = out / src.name.replace(".src.html", "_v1.html")
    assert report["target"] == str(target) and report["written"] and report["unknown"] == []
    assert hashlib.sha256(target.read_bytes()).hexdigest() == expected_hashes()[target.name]
    assert report["lint"] == {"error": 0, "warn": 0, "info": 0}


def test_run_twice_is_byte_identical_and_never_overwrites(tmp_path, no_brand, capsys):
    src = EXAMPLES / "ledger_live.src.html"
    args = [str(src), "--out-dir", str(tmp_path), "--brand-dir", str(no_brand)]
    assert inline.main(args) == 0 and inline.main(args) == 0
    capsys.readouterr()
    v1, v2 = tmp_path / "ledger_live_v1.html", tmp_path / "ledger_live_v2.html"
    assert v1.read_bytes() == v2.read_bytes()


def test_a_packaged_file_repackages_to_itself(tmp_path, no_brand, capsys):
    """Packaging collapses inlined blocks back to markers first, so re-packaging changes nothing."""
    src = EXAMPLES / "effects_gallery.src.html"
    assert inline.main([str(src), "--out-dir", str(tmp_path), "--brand-dir", str(no_brand)]) == 0
    first = tmp_path / "effects_gallery_v1.html"
    assert inline.main([str(first), "--brand-dir", str(no_brand)]) == 0
    capsys.readouterr()
    assert (tmp_path / "effects_gallery_v2.html").read_bytes() == first.read_bytes()


def test_unknown_marker_fails_and_writes_nothing(tmp_path, no_brand, capsys):
    src = tmp_path / "bad.src.html"
    src.write_text('<!doctype html><html lang="en"><head><meta charset="utf-8"><title>x</title>'
                   "<!-- @vendor nope --></head><body></body></html>", encoding="utf-8")
    assert inline.main([str(src), "--brand-dir", str(no_brand), "--json"]) == 1
    report = json.loads(capsys.readouterr().out)
    assert report["unknown"] == ["vendor:nope"] and report["written"] is False
    assert not (tmp_path / "bad_v1.html").exists()


def test_dry_run_writes_nothing(tmp_path, no_brand, capsys):
    src = EXAMPLES / "ledger_live.src.html"
    out = tmp_path / "out"
    assert inline.main([str(src), "--out-dir", str(out), "--brand-dir", str(no_brand), "--dry-run"]) == 0
    assert "would write" in capsys.readouterr().out
    assert not out.exists()


def test_a_modified_vendored_file_is_refused(tmp_path, no_brand, monkeypatch, capsys):
    vendor = tmp_path / "vendor"
    shutil.copytree(paths.VENDOR_DIR, vendor)
    with open(vendor / "js" / "anime.min.js", "a", encoding="utf-8") as f:
        f.write("\n// tampered\n")
    monkeypatch.setattr(paths, "VENDOR_DIR", vendor)
    out = tmp_path / "out"
    assert inline.main([str(EXAMPLES / "ledger_live.src.html"), "--out-dir", str(out),
                        "--brand-dir", str(no_brand)]) == 1
    assert "Checksum mismatch" in capsys.readouterr().out
    assert not out.exists()


def test_catalog_lists_every_manifest_entry(no_brand, capsys):
    assert inline.main(["--catalog", "--json", "--brand-dir", str(no_brand)]) == 0
    cat = json.loads(capsys.readouterr().out)
    man = inliner.load_manifest()
    assert [v["name"] for v in cat["vendors"]] == [lib["name"] for lib in man["libraries"]]
    assert [f["name"] for f in cat["fonts"]] == [f["name"] for f in man["fonts"]]
    assert cat["assets"] == []


def test_no_source_is_a_usage_error(capsys):
    with pytest.raises(SystemExit) as e:
        inline.main([])
    assert e.value.code == 2
