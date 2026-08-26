#!/usr/bin/env python3
"""
Codex CLI bootstrap for Trellis workflow.

Usage:
    python3 ./.trellis/scripts/codex_start.py
    python3 ./.trellis/scripts/codex_start.py --scope backend
    python3 ./.trellis/scripts/codex_start.py --developer codex-agent --scope cross-layer
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from common.developer import init_developer
from common.git_context import get_context_text
from common.paths import get_developer, get_repo_root


GUIDE_MAP = {
    "backend": [
        ".trellis/spec/backend/index.md",
        ".trellis/spec/guides/index.md",
    ],
    "frontend": [
        ".trellis/spec/frontend/index.md",
        ".trellis/spec/guides/index.md",
    ],
    "cross-layer": [
        ".trellis/spec/backend/index.md",
        ".trellis/spec/guides/cross-layer-thinking-guide.md",
        ".trellis/spec/guides/index.md",
    ],
    "general": [
        ".trellis/workflow.md",
        ".trellis/spec/guides/codex-cli-trellis-guide.md",
    ],
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Start a Trellis session for Codex CLI")
    parser.add_argument(
        "--developer",
        "-d",
        help="Initialize developer identity when .trellis/.developer is missing",
    )
    parser.add_argument(
        "--scope",
        "-s",
        choices=["backend", "frontend", "cross-layer", "general"],
        default="backend",
        help="Recommend relevant Trellis guides for the current task",
    )
    return parser.parse_args()


def ensure_developer(repo_root: Path, developer_name: str | None) -> str:
    existing = get_developer(repo_root)
    if existing:
        return existing

    if not developer_name:
        print("Developer not initialized.", file=sys.stderr)
        print(
            "Run `python3 ./.trellis/scripts/init_developer.py <name>` "
            "or rerun this command with `--developer <name>`.",
            file=sys.stderr,
        )
        sys.exit(1)

    if not init_developer(developer_name):
        sys.exit(1)

    return developer_name


def print_header(title: str) -> None:
    print("=" * 40)
    print(title)
    print("=" * 40)
    print()


def print_paths(repo_root: Path, scope: str) -> None:
    print_header("TRELLIS NEXT STEPS")
    print("Recommended reading:")
    for relative_path in GUIDE_MAP[scope]:
        path = repo_root / relative_path
        marker = "[exists]" if path.exists() else "[missing]"
        print(f"- {relative_path} {marker}")

    print()
    print("Common commands:")
    print("- python3 ./.trellis/scripts/get_context.py")
    print("- python3 ./.trellis/scripts/task.py list --mine")
    print("- python3 ./.trellis/scripts/task.py start <task-dir>")
    print("- python3 ./.trellis/scripts/task.py finish")


def main() -> None:
    args = parse_args()
    repo_root = get_repo_root()
    developer = ensure_developer(repo_root, args.developer)

    print_header("CODEX + TRELLIS SESSION")
    print(f"Developer: {developer}")
    print(f"Repository: {repo_root}")
    print(f"Scope: {args.scope}")
    print()

    print(get_context_text(repo_root))
    print()
    print_paths(repo_root, args.scope)


if __name__ == "__main__":
    main()
