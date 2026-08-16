#!/usr/bin/env python3
"""Install Cycles Renderer's tracked commit hook and message template locally."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    required = (root / ".githooks" / "commit-msg", root / ".gitmessage")
    missing = [str(path.relative_to(root)) for path in required if not path.is_file()]
    if missing:
        print(f"Missing commit policy files: {', '.join(missing)}", file=sys.stderr)
        return 1

    for key, value in (("core.hooksPath", ".githooks"), ("commit.template", ".gitmessage")):
        result = subprocess.run(
            ["git", "-C", str(root), "config", "--local", key, value],
            check=False,
        )
        if result.returncode != 0:
            print(f"Unable to set local Git config: {key}", file=sys.stderr)
            return result.returncode

    print("Cycles Renderer commit policy installed for this clone.")
    print("  core.hooksPath = .githooks")
    print("  commit.template = .gitmessage")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
