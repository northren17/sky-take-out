"""Start the local FastAPI Agent using this project's virtual environment."""

from __future__ import annotations

import os
import subprocess
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parent
PYTHON = (
    PROJECT_ROOT / ".venv" / "Scripts" / "python.exe"
    if os.name == "nt"
    else PROJECT_ROOT / ".venv" / "bin" / "python"
)


def main() -> int:
    if not PYTHON.is_file():
        raise FileNotFoundError(
            f"Project virtual environment not found: {PYTHON}. "
            "Create it and install requirements.txt first."
        )

    return subprocess.call(
        [
            str(PYTHON),
            "-m",
            "uvicorn",
            "agent.main:app",
            "--reload",
            "--host",
            "127.0.0.1",
            "--port",
            "8000",
        ],
        cwd=PROJECT_ROOT,
    )


if __name__ == "__main__":
    raise SystemExit(main())
