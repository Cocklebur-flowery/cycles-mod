#!/usr/bin/env python3
"""Validate a Cycles Renderer Issue against the repository policy."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any


REPOSITORY_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_POLICY_PATH = REPOSITORY_ROOT / "scripts" / "policy" / "issue-policy.json"
HEADING_PATTERN = re.compile(r"^(#{2,6})\s+(.+?)\s*$")
TITLE_PATTERN = re.compile(
    r"^(?P<prefix>[a-z]+)\((?P<scope>[a-z0-9][a-z0-9._/-]*)\): "
    r"(?P<summary>\S(?:.*\S)?)$"
)
CHECKED_PATTERN = re.compile(r"^\s*-\s*\[[xX]\]\s+\S", re.MULTILINE)
UNCHECKED_PATTERN = re.compile(r"^\s*-\s*\[\s\]\s+\S", re.MULTILINE)
CHECKLIST_PATTERN = re.compile(r"^\s*-\s*\[[ xX]\]\s+\S", re.MULTILINE)
ATTEMPT_PATTERN = re.compile(
    r"^#{3,6}\s+(?P<id>A(?P<number>\d+))\s+[—-]\s+(?P<name>\S.*)$",
    re.MULTILINE,
)
SENSITIVE_PATTERN = re.compile(
    r"(?i)(?:ghp_|github_pat_|sk-[a-z0-9]{10,}|"
    r"(?:token|secret|password)\s*[:=]\s*\S+)"
)
PLACEHOLDER_LINES = {
    "...",
    "- ...",
    "1. ...",
    "2. ...",
    "3. ...",
}
REPORT_MARKER = "<!-- cycles-issue-policy-report -->"


def load_policy(path: Path = DEFAULT_POLICY_PATH) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as policy_file:
        policy = json.load(policy_file)
    if policy.get("schema_version") != 1:
        raise ValueError(
            f"unsupported issue policy schema: {policy.get('schema_version')!r}"
        )
    return policy


def _known_sections(policy: dict[str, Any]) -> set[str]:
    sections = {"Deferred context"}
    sections.update(policy["dropdown_sections"])
    for issue_type in policy["issue_types"].values():
        sections.update(issue_type["intake_required_sections"])
        sections.update(issue_type["managed_required_sections"])
    return sections


def parse_sections(
    body: str, policy: dict[str, Any]
) -> tuple[dict[str, str], dict[str, int], list[str]]:
    known = _known_sections(policy)
    buffers: dict[str, list[str]] = {}
    counts: dict[str, int] = {}
    headings: list[str] = []
    current: str | None = None

    for line in body.splitlines():
        match = HEADING_PATTERN.fullmatch(line.strip())
        if match:
            heading = match.group(2).strip()
            headings.append(heading)
            if heading in known:
                current = heading
                counts[heading] = counts.get(heading, 0) + 1
                buffers.setdefault(heading, [])
                continue
        if current is not None:
            buffers[current].append(line)

    return (
        {name: "\n".join(lines).strip() for name, lines in buffers.items()},
        counts,
        headings,
    )


def _non_placeholder_lines(value: str) -> list[str]:
    lines: list[str] = []
    for raw_line in value.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("<!--"):
            continue
        if line in PLACEHOLDER_LINES:
            continue
        lines.append(line)
    return lines


def _has_meaningful_content(value: str) -> bool:
    return bool(_non_placeholder_lines(value))


def _label_values(labels: list[str], prefix: str) -> list[str]:
    return [label for label in labels if label.startswith(prefix)]


def _field_value(section: str, field: str) -> str | None:
    pattern = re.compile(
        rf"^\s*(?:-\s*)?{re.escape(field)}\s*:\s*(.*?)\s*$",
        re.MULTILINE,
    )
    match = pattern.search(section)
    return match.group(1).strip() if match else None


def _validate_environment(
    section: str,
    issue_type: str,
    policy: dict[str, Any],
) -> list[str]:
    if issue_type == "investigation" and re.search(
        r"(?im)^\s*Not applicable\s*[—-]\s*\S", section
    ):
        return []

    errors: list[str] = []
    for field in policy["environment_required_fields"]:
        value = _field_value(section, field)
        if value is None:
            errors.append(f"Environment is missing '{field}:'")
        elif not value:
            errors.append(f"Environment field '{field}' is empty")
        elif value.casefold() in {"unknown", "not available", "n/a"}:
            errors.append(
                f"Environment field '{field}' must explain why it is unknown"
            )
    return errors


def _validate_dropdowns(
    sections: dict[str, str], policy: dict[str, Any]
) -> list[str]:
    errors: list[str] = []
    for section, policy_key in policy["dropdown_sections"].items():
        if section not in sections:
            continue
        value = sections[section].strip()
        if value not in policy[policy_key]:
            allowed = ", ".join(policy[policy_key])
            errors.append(f"{section} must be one of: {allowed}")
    return errors


def _validate_attempts(section: str, policy: dict[str, Any]) -> list[str]:
    normalized = section.strip()
    if re.fullmatch(r"None recorded\.?,?", normalized, re.IGNORECASE):
        return []

    matches = list(ATTEMPT_PATTERN.finditer(section))
    if not matches:
        return [
            "Failed attempts must be 'None recorded.' or contain an A1/A2 ledger"
        ]

    errors: list[str] = []
    expected_number = 1
    seen_ids: set[str] = set()
    for index, match in enumerate(matches):
        attempt_id = match.group("id")
        number = int(match.group("number"))
        if attempt_id in seen_ids:
            errors.append(f"Failed attempts repeats {attempt_id}")
        seen_ids.add(attempt_id)
        if number != expected_number:
            errors.append(
                f"Failed attempts expected A{expected_number} but found {attempt_id}"
            )
        expected_number += 1

        end = matches[index + 1].start() if index + 1 < len(matches) else len(section)
        attempt_body = section[match.end() : end]
        values: dict[str, str] = {}
        for field in policy["attempt_required_fields"]:
            value = _field_value(attempt_body, field)
            if value is None:
                errors.append(f"{attempt_id} is missing '{field}:'")
            elif not value:
                errors.append(f"{attempt_id} field '{field}' is empty")
            else:
                values[field] = value

        disposition = values.get("Disposition")
        if disposition and disposition not in policy["dispositions"]:
            allowed = ", ".join(policy["dispositions"])
            errors.append(f"{attempt_id} Disposition must be one of: {allowed}")
    return errors


def _validate_deferred(section: str, policy: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    for field in policy["deferred_required_fields"]:
        value = _field_value(section, field)
        if value is None:
            errors.append(f"Deferred context is missing '{field}:'")
        elif not value:
            errors.append(f"Deferred context field '{field}' is empty")
    return errors


def _validate_chinese_summary(section: str, policy: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    for marker in policy["chinese_summary_required_markers"]:
        if marker not in section:
            errors.append(f"中文概要 is missing marker '{marker}'")

    if "用 2～4 句话说明" in section:
        errors.append("中文概要 still contains the problem placeholder")
    if re.search(r"(?m)^\s*\.\.\.\s*$", section):
        errors.append("中文概要 still contains an ellipsis placeholder")
    empty_summary_field = re.compile(
        r"(?m)^\s*-\s*(?:已确认|尚未确认|当前状态|A\d+|最重要的证据|"
        r"不要重复尝试|恢复工作的触发条件|关闭前必须验证)[：:]\s*$"
    )
    if empty_summary_field.search(section):
        errors.append("中文概要 contains an empty context-recovery field")
    return errors


def _format_report(
    *,
    errors: list[str],
    mode: str,
    issue_type: str | None,
    status: str | None,
) -> str:
    if errors:
        details = "\n".join(f"- {error}" for error in errors)
        return (
            f"{REPORT_MARKER}\n"
            "### Issue policy report\n\n"
            f"**Result:** incomplete ({mode})\n\n"
            f"{details}\n\n"
            "Edit the Issue to preserve the missing engineering context. The "
            "policy does not close or reopen Issues."
        )
    return (
        f"{REPORT_MARKER}\n"
        "### Issue policy report\n\n"
        f"**Result:** complete ({mode})\n\n"
        f"Type `{issue_type}` with status `{status}` satisfies the current "
        "repository Issue policy."
    )


def validate_issue(
    *,
    title: str,
    body: str,
    labels: list[str],
    state: str,
    policy: dict[str, Any],
) -> dict[str, Any]:
    del state  # Closed Issues are reported but never reopened by policy.
    errors: list[str] = []
    sections, section_counts, headings = parse_sections(body, policy)

    type_labels = _label_values(labels, "type:")
    configured_type_labels = {
        value["label"]: key for key, value in policy["issue_types"].items()
    }
    issue_type: str | None = None
    if len(type_labels) != 1:
        errors.append("Issue must have exactly one type:* label")
    elif type_labels[0] not in configured_type_labels:
        errors.append(f"unsupported Issue type label '{type_labels[0]}'")
    else:
        issue_type = configured_type_labels[type_labels[0]]

    title_match = TITLE_PATTERN.fullmatch(title.strip())
    if not title_match:
        errors.append(
            "title must match <issue-type>(<scope>): <observed problem>"
        )
    else:
        prefix = title_match.group("prefix")
        scope = title_match.group("scope")
        summary = title_match.group("summary")
        inferred_types = [
            key
            for key, value in policy["issue_types"].items()
            if value["title_prefix"] == prefix
        ]
        if not inferred_types:
            errors.append(f"unsupported title issue type '{prefix}'")
        elif issue_type is None:
            issue_type = inferred_types[0]
        elif issue_type not in inferred_types:
            expected = policy["issue_types"][issue_type]["title_prefix"]
            errors.append(
                f"title prefix '{prefix}' does not match type label; expected '{expected}'"
            )
        if scope == "scope":
            errors.append("title still contains the literal scope placeholder")
        elif scope not in policy["scopes"]:
            errors.append(f"unsupported Issue scope '{scope}'")
        if title.endswith("."):
            errors.append("title must not end with a period")
        if len(title) > policy["title_max_length"]:
            errors.append(
                f"title must be {policy['title_max_length']} characters or fewer"
            )
        lowered_summary = summary.casefold()
        if any(
            lowered_summary == value.casefold()
            for value in policy["vague_title_summaries"]
        ):
            errors.append("title uses a vague summary")

    status_labels = _label_values(labels, "status:")
    status: str | None = None
    if len(status_labels) != 1:
        errors.append("Issue must have exactly one status:* label")
    else:
        status = status_labels[0].removeprefix("status:")
        if status not in policy["statuses"]:
            errors.append(f"unsupported Issue status '{status}'")

    managed = status in policy["managed_statuses"]
    mode = "managed" if managed else "intake"
    severity_labels = _label_values(labels, "severity:")
    if managed:
        if len(severity_labels) != 1:
            errors.append("managed Issue must have exactly one severity:* label")
        elif severity_labels[0].removeprefix("severity:") not in policy["severities"]:
            errors.append(f"unsupported Issue Severity '{severity_labels[0]}'")
    elif len(severity_labels) > 1:
        errors.append("intake Issue may not have multiple severity:* labels")
    elif severity_labels and severity_labels[0].removeprefix("severity:") not in policy["severities"]:
        errors.append(f"unsupported Issue Severity '{severity_labels[0]}'")

    if issue_type is not None:
        type_policy = policy["issue_types"][issue_type]
        required_key = (
            "managed_required_sections" if managed else "intake_required_sections"
        )
        for section in type_policy[required_key]:
            if section_counts.get(section, 0) == 0:
                errors.append(f"{mode} {issue_type} Issue is missing '{section}'")
            elif section_counts[section] > 1:
                errors.append(f"Issue repeats section '{section}'")
            elif not _has_meaningful_content(sections.get(section, "")):
                errors.append(f"section '{section}' has no meaningful content")

    forbidden = {value.casefold() for value in policy["forbidden_sections"]}
    for heading in headings:
        if heading.casefold() in forbidden:
            errors.append(f"Issue contains forbidden solution section '{heading}'")

    errors.extend(_validate_dropdowns(sections, policy))

    submission_checks = sections.get("Submission checks")
    if submission_checks is not None:
        if not CHECKED_PATTERN.search(submission_checks):
            errors.append("Submission checks contains no confirmed checkbox")
        if UNCHECKED_PATTERN.search(submission_checks):
            errors.append("Submission checks contains an unchecked confirmation")

    if managed and issue_type is not None:
        environment = sections.get("Environment", "")
        errors.extend(_validate_environment(environment, issue_type, policy))

        failed_attempts = sections.get("Failed attempts", "")
        errors.extend(_validate_attempts(failed_attempts, policy))

        chinese_summary = sections.get("中文概要", "")
        errors.extend(_validate_chinese_summary(chinese_summary, policy))

        criteria_section = (
            "Exit criteria" if issue_type == "investigation" else "Resolution criteria"
        )
        if criteria_section in sections and not CHECKLIST_PATTERN.search(
            sections[criteria_section]
        ):
            errors.append(f"{criteria_section} must contain a Markdown checklist")

        if status == "deferred":
            deferred = sections.get("Deferred context")
            if deferred is None:
                errors.append("deferred Issue is missing 'Deferred context'")
            else:
                errors.extend(_validate_deferred(deferred, policy))

    if SENSITIVE_PATTERN.search(body):
        errors.append("Issue body contains a sensitive-looking value")

    # Stable order makes workflow reports idempotent.
    errors = list(dict.fromkeys(errors))
    complete = not errors
    desired_policy_label = policy["policy_labels"][
        "complete" if complete else "incomplete"
    ]
    return {
        "valid": complete,
        "mode": mode,
        "issue_type": issue_type,
        "status": status,
        "desired_policy_label": desired_policy_label,
        "errors": errors,
        "report": _format_report(
            errors=errors,
            mode=mode,
            issue_type=issue_type,
            status=status,
        ),
    }


def _load_event(path: Path) -> tuple[str, str, list[str], str]:
    with path.open("r", encoding="utf-8") as event_file:
        event = json.load(event_file)
    issue = event.get("issue")
    if not isinstance(issue, dict):
        raise ValueError("event does not contain an Issue payload")
    labels = [
        label["name"] if isinstance(label, dict) else str(label)
        for label in issue.get("labels", [])
    ]
    return (
        str(issue.get("title", "")),
        str(issue.get("body") or ""),
        labels,
        str(issue.get("state", "open")),
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--event-file", type=Path, help="GitHub Issues event JSON")
    source.add_argument("--body-file", type=Path, help="Issue Markdown body")
    parser.add_argument("--title", help="Issue title when using --body-file")
    parser.add_argument(
        "--labels",
        default="",
        help="comma-separated Issue labels when using --body-file",
    )
    parser.add_argument("--state", default="open", choices=("open", "closed"))
    parser.add_argument("--policy", type=Path, default=DEFAULT_POLICY_PATH)
    parser.add_argument("--json-output", type=Path)
    args = parser.parse_args()

    try:
        policy = load_policy(args.policy)
        if args.event_file:
            title, body, labels, state = _load_event(args.event_file)
        else:
            if not args.title:
                parser.error("--title is required with --body-file")
            title = args.title
            body = args.body_file.read_text(encoding="utf-8")
            labels = [value.strip() for value in args.labels.split(",") if value.strip()]
            state = args.state
        result = validate_issue(
            title=title,
            body=body,
            labels=labels,
            state=state,
            policy=policy,
        )
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"Issue policy could not run: {exc}", file=sys.stderr)
        return 2

    if args.json_output:
        args.json_output.write_text(
            json.dumps(result, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

    if result["valid"]:
        print(
            f"Issue policy accepted: {result['mode']} "
            f"{result['issue_type']} ({result['status']})"
        )
        return 0

    print("Issue policy rejected:", file=sys.stderr)
    for error in result["errors"]:
        print(f"  - {error}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
