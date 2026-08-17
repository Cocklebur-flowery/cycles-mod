from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = REPOSITORY_ROOT / "scripts" / "validate-pull-request.py"
SPEC = importlib.util.spec_from_file_location("validate_pull_request", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
validate_pr_module = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = validate_pr_module
SPEC.loader.exec_module(validate_pr_module)


class PullRequestIssueLinkageTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.policy = validate_pr_module.load_policy()
        cls.checks = cls.policy["issue_linkage"]["pull_request_checks"]

    def validate(self, body: str) -> list[str]:
        return validate_pr_module.validate_pull_request(body, self.policy)

    def checked(self, key: str) -> str:
        return f"- [x] {self.checks[key]}"

    def test_pr_without_existing_issue_remains_valid(self) -> None:
        self.assertEqual([], self.validate("## Summary\n\nSmall direct correction."))

    def test_linked_pr_requires_context_confirmations(self) -> None:
        errors = self.validate("## Issue context\n\nRefs #123")
        self.assertIn(
            "linked PR must confirm that the Issue Failed attempts ledger was reviewed",
            errors,
        )
        self.assertIn(
            "linked PR must confirm that relevant commits use the existing Issue Refs",
            errors,
        )

    def test_linked_pr_with_context_confirmations_is_valid(self) -> None:
        body = "\n".join(
            (
                "## Issue context",
                "",
                "Refs #123",
                self.checked("reviewed_attempts"),
                self.checked("commit_refs"),
            )
        )
        self.assertEqual([], self.validate(body))

    def test_closing_pr_requires_resolution_confirmation(self) -> None:
        body = "\n".join(
            (
                "Closes #123",
                self.checked("reviewed_attempts"),
                self.checked("commit_refs"),
            )
        )
        errors = self.validate(body)
        self.assertIn(
            "closing PR must confirm that the linked Issue Resolution criteria are satisfied",
            errors,
        )

        complete = body + "\n" + self.checked("resolution_criteria")
        self.assertEqual([], self.validate(complete))

    def test_template_checks_are_sourced_from_policy(self) -> None:
        template = (
            REPOSITORY_ROOT / ".github" / "PULL_REQUEST_TEMPLATE.md"
        ).read_text(encoding="utf-8")
        for label in self.checks.values():
            self.assertIn(f"- [ ] {label}", template)
        self.assertIn(
            "Leave the reference as\n`None` when no existing Issue applies", template
        )
        self.assertEqual([], self.validate(template))


if __name__ == "__main__":
    unittest.main()
