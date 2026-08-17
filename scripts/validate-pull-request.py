#!/usr/bin/env python3
"""Validate conditional Issue context in a Cycles Renderer pull request."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any


REPOSITORY_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_POLICY_PATH = REPOSITORY_ROOT / "scripts" / "policy" / "issue-policy.json"
ISSUE_REFERENCE = r"(?:[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)?#[1-9][0-9]*"


def load_policy(path: Path = DEFAULT_POLICY_PATH) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as policy_file:
        policy = json.load(policy_file)
    if policy.get("schema_version") != 1:
        raise ValueError(
            f"unsupported issue policy schema: {policy.get('schema_version')!r}"
        )
    return policy


def _checked(body: str, label: str) -> bool:
    return bool(
        re.search(
            rf"(?im)^\s*-\s*\[[xX]\]\s+{re.escape(label)}\s*$",
            body,
        )
    )


def validate_pull_request(body: str, policy: dict[str, Any]) -> list[str]:
    linkage = policy["issue_linkage"]
    reference_pattern = re.compile(
        rf"\b{re.escape(linkage['pull_request_reference'])}\s*:?[ \t]+"
        rf"(?P<issue>{ISSUE_REFERENCE})\b",
        re.IGNORECASE,
    )
    closing_pattern = re.compile(
        r"\b(?:"
        + "|".join(re.escape(value) for value in linkage["closing_keywords"])
        + rf")\s*:?[ \t]+(?P<issue>{ISSUE_REFERENCE})\b",
        re.IGNORECASE,
    )
    references = {match.group("issue") for match in reference_pattern.finditer(body)}
    closing = {match.group("issue") for match in closing_pattern.finditer(body)}
    linked = references | closing
    if not linked:
        return []

    checks = linkage["pull_request_checks"]
    errors: list[str] = []
    if not _checked(body, checks["reviewed_attempts"]):
        errors.append(
            "linked PR must confirm that the Issue Failed attempts ledger was reviewed"
        )
    if not _checked(body, checks["commit_refs"]):
        errors.append(
            "linked PR must confirm that relevant commits use the existing Issue Refs"
        )
    if closing and not _checked(body, checks["resolution_criteria"]):
        errors.append(
            "closing PR must confirm that the linked Issue Resolution criteria are satisfied"
        )
    return errors


def _event_body(path: Path) -> str:
    with path.open("r", encoding="utf-8") as event_file:
        event = json.load(event_file)
    pull_request = event.get("pull_request")
    if not isinstance(pull_request, dict):
        raise ValueError("event does not contain a pull_request payload")
    return str(pull_request.get("body") or "")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--event-file", type=Path)
    source.add_argument("--body-file", type=Path)
    parser.add_argument("--policy", type=Path, default=DEFAULT_POLICY_PATH)
    args = parser.parse_args()

    try:
        policy = load_policy(args.policy)
        body = (
            _event_body(args.event_file)
            if args.event_file
            else args.body_file.read_text(encoding="utf-8")
        )
        errors = validate_pull_request(body, policy)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"Pull request policy could not run: {exc}", file=sys.stderr)
        return 2

    if not errors:
        print("Pull request Issue linkage accepted")
        return 0

    print("Pull request Issue linkage rejected:", file=sys.stderr)
    for error in errors:
        print(f"  - {error}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
