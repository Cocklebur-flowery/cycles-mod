from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = REPOSITORY_ROOT / "scripts" / "validate-commit-message.py"
SPEC = importlib.util.spec_from_file_location("validate_commit_message", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
validate_commit_module = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = validate_commit_module
SPEC.loader.exec_module(validate_commit_module)


def message(*, footer: str = "", extra: str = "") -> str:
    suffix = f"\n{footer}\n" if footer else ""
    return f"""fix(presentation): preserve stable reprojection output

Why:
- Runtime evidence confirmed a visible presentation defect.

Changes:
- Preserve stable output for the confirmed reproduction.

Validation:
- PASS `focused policy test` — exercised the commit-message rule.

Compatibility / Risks:
- ABI, settings, resources, and runtime ownership remain unchanged.

{extra}
Commit-Level: M
{suffix}"""


class CommitIssueReferenceTest(unittest.TestCase):
    def test_fix_without_issue_reference_remains_valid(self) -> None:
        errors = validate_commit_module.validate_message(message())
        self.assertEqual([], errors)

    def test_existing_issue_reference_is_valid(self) -> None:
        errors = validate_commit_module.validate_message(message(footer="Refs: #123"))
        self.assertEqual([], errors)

        cross_repository = validate_commit_module.validate_message(
            message(footer="Refs: Cocklebur-flowery/cycles-mod#123")
        )
        self.assertEqual([], cross_repository)

    def test_generic_authoritative_reference_remains_valid(self) -> None:
        errors = validate_commit_module.validate_message(
            message(footer="Refs: docs/stages/example.md")
        )
        self.assertEqual([], errors)

    def test_malformed_issue_reference_is_rejected(self) -> None:
        errors = validate_commit_module.validate_message(message(footer="Refs: #abc"))
        self.assertIn(
            "Issue references in Refs must use #123 or owner/repository#123",
            errors,
        )

    def test_multiple_refs_footers_are_rejected(self) -> None:
        errors = validate_commit_module.validate_message(
            message(extra="Refs: #123\n", footer="Refs: #124")
        )
        self.assertIn("include at most one Refs footer", errors)

    def test_commit_closing_keyword_is_rejected(self) -> None:
        for closing in ("Closes #123", "Fixes: #123", "Resolves owner/repo#123"):
            with self.subTest(closing=closing):
                errors = validate_commit_module.validate_message(
                    message(extra=closing)
                )
                self.assertIn(
                    "commit messages must not close Issues; use Refs and close from an accepted PR or manual review",
                    errors,
                )


if __name__ == "__main__":
    unittest.main()
