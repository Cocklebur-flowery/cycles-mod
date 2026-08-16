#!/usr/bin/env python3
"""Validate Cycles Renderer commit messages without project dependencies."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path


ALLOWED_TYPES = {
    "feat",
    "fix",
    "perf",
    "refactor",
    "test",
    "docs",
    "build",
    "ci",
    "chore",
    "revert",
}
TITLE_PATTERN = re.compile(
    r"^(?P<type>[a-z]+)\((?P<scope>[a-z0-9][a-z0-9._/-]*)\): (?P<summary>\S(?:.*\S)?)$"
)
LEVEL_PATTERN = re.compile(r"^Commit-Level:\s*([SMH])\s*$")
SECTION_PATTERN = re.compile(r"^([A-Za-z][A-Za-z /_-]*):\s*$")
VALIDATION_LINE_PATTERN = re.compile(
    r"^-\s+(?:PASS|FAIL|KNOWN RED|BLOCKED|NOT RUN)\s+`[^`]+`\s+\S.*$"
)
REVERT_FOOTER_PATTERN = re.compile(r"^Reverts:\s*[0-9a-f]{40}\s*$")
VAGUE_SUMMARY_PATTERN = re.compile(
    r"^(?:update|updates|change|changes|stuff|misc|final|wip)(?:\s|$)",
    re.IGNORECASE,
)
ABSOLUTE_PATH_PATTERN = re.compile(
    r"(?i)(?:^|[\s(`])(?:[a-z]:[\\/]|/(?:home|users|private|mnt|workspace)/)"
)
SENSITIVE_PATTERN = re.compile(
    r"(?i)(?:ghp_|github_pat_|sk-[a-z0-9]{10,}|(?:token|secret|password)\s*[:=])"
)


def _clean_lines(message: str) -> list[str]:
    return [
        line.rstrip()
        for line in message.splitlines()
        if not line.lstrip().startswith("#")
    ]


def _parse_message(message: str) -> tuple[str, list[str]]:
    lines = _clean_lines(message)
    while lines and not lines[0].strip():
        lines.pop(0)
    if not lines:
        return "", []
    return lines[0].strip(), lines[1:]


def _sections(body: list[str]) -> dict[str, list[str]]:
    sections: dict[str, list[str]] = {}
    current: str | None = None
    for line in body:
        match = SECTION_PATTERN.fullmatch(line.strip())
        if match:
            current = match.group(1)
            sections.setdefault(current, [])
        elif current is not None:
            sections[current].append(line)
    return sections


def _has_content(lines: list[str] | None) -> bool:
    return bool(lines and any(line.strip() for line in lines))


def validate_message(message: str) -> list[str]:
    title, body = _parse_message(message)
    errors: list[str] = []

    if not title:
        return ["missing subject line"]

    title_match = TITLE_PATTERN.fullmatch(title)
    if not title_match:
        errors.append("subject must match <type>(<scope>): <imperative summary>")
    else:
        commit_type = title_match.group("type")
        summary = title_match.group("summary")
        if commit_type not in ALLOWED_TYPES:
            errors.append(f"unsupported type '{commit_type}'")
        if len(title) > 72:
            errors.append("subject must be 72 characters or fewer")
        if summary.endswith("."):
            errors.append("subject must not end with a period")
        if VAGUE_SUMMARY_PATTERN.match(summary):
            errors.append("subject uses a vague summary; describe the concrete result")

    if body and body[0].strip():
        errors.append("subject must be followed by a blank line")

    levels = [
        match.group(1)
        for line in body
        if (match := LEVEL_PATTERN.fullmatch(line.strip()))
    ]
    if len(levels) != 1:
        errors.append("include exactly one machine-readable 'Commit-Level: S/M/H' trailer")
        level = None
    else:
        level = levels[0]

    sections = _sections(body)
    body_without_level = [
        line for line in body if line.strip() and not LEVEL_PATTERN.fullmatch(line.strip())
    ]

    if level == "S":
        if body_without_level:
            errors.append("Level S may contain only the subject and Commit-Level trailer")
    elif level in {"M", "H"}:
        for required in ("Why", "Changes", "Validation"):
            if not _has_content(sections.get(required)):
                errors.append(f"Level {level} requires a non-empty '{required}:' section")

        validation = sections.get("Validation", [])
        validation_bullets = [line.strip() for line in validation if line.strip().startswith("-")]
        if not validation_bullets:
            errors.append("Validation must contain at least one status bullet")
        else:
            for line in validation_bullets:
                if not VALIDATION_LINE_PATTERN.fullmatch(line):
                    errors.append(
                        "Validation bullets must use PASS/FAIL/KNOWN RED/BLOCKED/NOT RUN, "
                        "a backtick command, and evidence"
                    )
                    break

        if level == "H":
            for required in ("Contracts", "Compatibility / Risks", "Known limitations"):
                if not _has_content(sections.get(required)):
                    errors.append(f"Level H requires a non-empty '{required}:' section")
            if not _has_content(sections.get("Manual verification")) and not _has_content(
                sections.get("Runtime evidence")
            ):
                errors.append("Level H requires Manual verification or Runtime evidence")

    for line in [title, *body]:
        if ABSOLUTE_PATH_PATTERN.search(line) or SENSITIVE_PATTERN.search(line):
            errors.append("commit message contains an absolute path or sensitive-looking value")
            break

    if title_match and title_match.group("type") == "revert":
        if not any(REVERT_FOOTER_PATTERN.fullmatch(line.strip()) for line in body):
            errors.append("revert commits require 'Reverts: <full 40-character hash>'")

    return errors


def _git(*args: str) -> str:
    result = subprocess.run(
        ["git", *args],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise RuntimeError(f"git {' '.join(args)} failed: {detail}")
    return result.stdout.strip()


def _is_ancestor(ancestor: str, commit: str) -> bool:
    result = subprocess.run(
        ["git", "merge-base", "--is-ancestor", ancestor, commit],
        check=False,
        capture_output=True,
    )
    return result.returncode == 0


def _validate_message(label: str, message: str) -> list[str]:
    errors = validate_message(message)
    if errors:
        print(f"Commit message rejected: {label}", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        print(
            "Expected: <type>(<scope>): <imperative summary>, a blank line, "
            "Commit-Level: S/M/H, and the required sections.",
            file=sys.stderr,
        )
    return errors


def _validate_message_file(path: str) -> int:
    message_path = Path(path)
    try:
        message = message_path.read_text(encoding="utf-8", errors="replace")
    except OSError as exc:
        print(f"Unable to read commit message file: {exc}", file=sys.stderr)
        return 1
    errors = _validate_message(str(message_path), message)
    if errors:
        print("Fix the message and retry; --no-verify bypasses only the local hook and will fail CI.", file=sys.stderr)
        return 1
    print(f"Commit message accepted: {message_path}")
    return 0


def _validate_range(commit_range: str, boundary: str | None) -> int:
    if boundary:
        _git("rev-parse", "--verify", f"{boundary}^{{commit}}")

    commits = _git("rev-list", "--reverse", "--no-merges", commit_range).splitlines()
    applicable = [
        commit
        for commit in commits
        if boundary is None or _is_ancestor(boundary, commit)
    ]
    if not applicable:
        print(f"Commit policy accepted: no non-merge commits after the policy boundary in {commit_range}")
        return 0

    failure_count = 0
    for commit in applicable:
        message = _git("show", "-s", "--format=%B", commit)
        if _validate_message(commit, message):
            failure_count += 1

    if failure_count:
        print(f"Commit policy rejected {failure_count} commit(s)", file=sys.stderr)
        return 1
    print(f"Commit policy accepted: {len(applicable)} commit(s) checked")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--message-file", help="validate one Git commit message file")
    source.add_argument("--range", dest="commit_range", help="validate commits in a Git revision range")
    parser.add_argument("--boundary", help="ignore commits that are not descendants of this policy boundary")
    args = parser.parse_args()

    try:
        if args.message_file:
            return _validate_message_file(args.message_file)
        return _validate_range(args.commit_range, args.boundary)
    except RuntimeError as exc:
        print(f"Commit policy could not run: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
