import sys
from pathlib import Path

import pytest

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))

HOME = TOOLS.parent
EXAMPLES = HOME / "examples"
FIXTURES = Path(__file__).resolve().parent / "fixtures"


@pytest.fixture
def no_brand(tmp_path):
    """An empty brand folder, so a machine's own ~/.atelier/brand never changes a result."""
    d = tmp_path / "brand"
    d.mkdir()
    return d
