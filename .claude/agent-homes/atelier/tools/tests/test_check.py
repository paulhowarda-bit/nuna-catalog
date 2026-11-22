import json

import pytest

import check
import inline
from conftest import EXAMPLES, FIXTURES


def run_json(capsys, *args):
    code = check.main([*args, "--json"])
    return code, json.loads(capsys.readouterr().out)


def test_bad_page_matches_golden_findings(no_brand, capsys):
    """Golden: one page that breaks a rule per area; the findings must not drift."""
    code, report = run_json(capsys, str(FIXTURES / "lint_bad.html"), "--brand-dir", str(no_brand))
    assert code == 1
    expected = json.loads((FIXTURES / "lint_bad.findings.json").read_text("utf-8"))
    assert report["findings"] == expected


def test_run_twice_is_identical(no_brand, capsys):
    args = (str(FIXTURES / "lint_bad.html"), "--brand-dir", str(no_brand))
    first = run_json(capsys, *args)
    assert run_json(capsys, *args) == first


@pytest.mark.parametrize("src", sorted(EXAMPLES.glob("*.src.html")), ids=lambda p: p.name)
def test_packaged_examples_are_clean(src, tmp_path, no_brand, capsys):
    assert inline.main([str(src), "--out-dir", str(tmp_path), "--brand-dir", str(no_brand)]) == 0
    capsys.readouterr()
    (built,) = tmp_path.glob("*_v1.html")
    code, report = run_json(capsys, str(built), "--brand-dir", str(no_brand))
    assert code == 0 and report["findings"] == []


def test_source_mode_skips_the_unresolved_marker_check(no_brand, capsys):
    src = str(EXAMPLES / "ledger_live.src.html")
    _, packaged = run_json(capsys, src, "--brand-dir", str(no_brand))
    _, source = run_json(capsys, src, "--source", "--brand-dir", str(no_brand))
    assert "OFF004" in [f["id"] for f in packaged["findings"]]
    assert "OFF004" not in [f["id"] for f in source["findings"]]


def test_missing_file_is_a_usage_error(tmp_path, capsys):
    with pytest.raises(SystemExit) as e:
        check.main([str(tmp_path / "nope.html")])
    assert e.value.code == 2
