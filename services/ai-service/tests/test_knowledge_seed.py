"""knowledge_seed dry-run smoke tests."""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path


def test_seed_dry_run_smoke():
    repo_root = Path(__file__).resolve().parents[3]
    script = repo_root / "scripts" / "knowledge_seed.py"

    result = subprocess.run(
        [
            sys.executable,
            str(script),
            "--docs-root",
            str(repo_root / "docs"),
            "--dry-run",
        ],
        capture_output=True,
        text=True,
        timeout=30,
        check=False,
    )

    assert result.returncode == 0, f"exit code {result.returncode}\nstderr: {result.stderr}"
    assert "Summary" in result.stdout
    assert "docs indexed: 8" in result.stdout
    assert "failed: 0" in result.stdout
