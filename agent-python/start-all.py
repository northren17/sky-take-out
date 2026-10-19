"""Backward-compatible launcher; the Python project now starts its backend only."""

from start_backend import main


if __name__ == "__main__":
    raise SystemExit(main())
