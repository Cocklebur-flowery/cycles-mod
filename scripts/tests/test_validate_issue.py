from __future__ import annotations

import importlib.util
import json
import sys
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = REPOSITORY_ROOT / "scripts" / "validate-issue.py"
SPEC = importlib.util.spec_from_file_location("validate_issue", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
validate_issue_module = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = validate_issue_module
SPEC.loader.exec_module(validate_issue_module)


class IssuePolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.policy = validate_issue_module.load_policy()

    def validate(
        self,
        *,
        title: str,
        body: str,
        labels: list[str],
        state: str = "open",
    ) -> dict[str, object]:
        return validate_issue_module.validate_issue(
            title=title,
            body=body,
            labels=labels,
            state=state,
            policy=self.policy,
        )

    @staticmethod
    def dropdown_options(form: str, field_id: str) -> list[str]:
        lines = form.splitlines()
        id_line = f"    id: {field_id}"
        start = lines.index(id_line)
        end = next(
            (
                index
                for index in range(start + 1, len(lines))
                if lines[index].startswith("  - type:")
            ),
            len(lines),
        )
        block = lines[start:end]
        options_index = block.index("      options:")
        return [
            line.removeprefix("        - ")
            for line in block[options_index + 1 :]
            if line.startswith("        - ")
        ]

    @staticmethod
    def intake_bug_body(level: str = "###") -> str:
        return f"""{level} Summary

Camera motion produces visible frame-wide shaking when reprojection is active.

{level} Current behavior

The image shakes during sustained camera rotation.

{level} Expected behavior

Camera motion remains stable while the last source frame is reprojected.

{level} Reproduction

1. Enable reprojection.
2. Enter a grass scene.
3. Rotate the camera.

{level} Reproducibility

Always

{level} Submission checks

- [x] I searched for an existing duplicate Issue.
- [x] I separated confirmed observations from hypotheses.
"""

    @staticmethod
    def managed_bug_body(*, deferred: bool = False, attempts: str | None = None) -> str:
        attempt_body = attempts or "None recorded."
        deferred_body = """
## Deferred context

Deferred reason: Current investigation cost exceeds the project value.
Last evaluated: 2026-08-18
Resume trigger: A deterministic motion capture becomes available.
""" if deferred else ""
        return f"""## Summary

Reprojection activates over depth-covered grass but shakes during camera motion.

## Current behavior

The displayed image oscillates at high frequency during rotation and movement.

## Expected behavior

Display-frame reprojection follows camera motion without frame-wide oscillation.

## Reproduction

1. Enable reprojection.
2. Enter a grass scene.
3. Rotate or translate the camera.

## Reproducibility

Always

## Environment

Commit: bc36e9c
Working tree / artifact identity: committed R6c runtime artifact
Minecraft: 26.2
NeoForge: 26.2.0.58
ABI: 45
GPU / driver: NVIDIA RTX class / driver recorded in runtime attachment
OS: Windows 11
Resolution: 1920x1080 source and display
Relevant configuration: reprojection on; Post DoF and denoiser off

## Variant

default

## Render device

optix

## Presentation path

vulkan-interop

## Evidence

Runtime F10 telemetry reports active reprojection while visible shaking occurs.

## Impact

The optional reprojection path is unusable; disabling it preserves rendering.

## Confirmed boundaries

Reproduces with camera motion and without Post DoF or denoising. Other devices are not evaluated.

## Investigation hypotheses

None recorded.

## Failed attempts

{attempt_body}

## Workaround

Disable reprojection. This is reliable but loses display-frame smoothing.

## Resolution criteria

- [ ] The original grass-scene reproduction no longer shakes.
- [ ] Default and DLSS repository gates pass when applicable.

{deferred_body}

## Related

Stage: docs/stages/2d-asynchronous-depth-reprojection.md
Commit: bc36e9c

## Submission checks

- [x] I searched for an existing duplicate Issue.
- [x] I separated confirmed observations from hypotheses.

## 中文概要

> 本节用于快速理解和上下文恢复，不是工程事实的规范来源。
> 如与上方英文正文存在差异，以英文正文为准。

### 问题

重投影已能在草地场景启用，但相机运动时发生高频全画面震动。

### 当前结论

- 已确认：关闭景深和降噪后仍会复现。
- 尚未确认：尚无稳定性错误的确定根因。
- 当前状态：已确认问题。

### 已失败 / 无效方向

- A1：如果存在尝试，以英文账本为准；否则尚无记录。

### 当前规避方案

关闭重投影，代价是失去显示帧平滑能力。

### 以后重新处理时先看

- 最重要的证据：重投影实际启用时震动才出现。
- 不要重复尝试：先阅读英文 Failed attempts。
- 恢复工作的触发条件：建立确定性的运动捕获。
- 关闭前必须验证：原始草地复现和双变体门禁。
"""

    def test_accepts_minimal_external_bug_intake(self) -> None:
        result = self.validate(
            title="bug(presentation): reprojection shakes during camera motion",
            body=self.intake_bug_body(),
            labels=["type:bug", "status:needs-triage"],
        )
        self.assertTrue(result["valid"], result["errors"])
        self.assertEqual("intake", result["mode"])
        self.assertEqual("policy:complete", result["desired_policy_label"])
        self.assertEqual(
            ["policy:complete", "policy:incomplete"], result["policy_labels"]
        )

    def test_accepts_complete_managed_bug(self) -> None:
        result = self.validate(
            title="bug(presentation): reprojection shakes during camera motion",
            body=self.managed_bug_body(),
            labels=["type:bug", "status:confirmed", "severity:S3-moderate"],
        )
        self.assertTrue(result["valid"], result["errors"])
        self.assertEqual("managed", result["mode"])

    def test_managed_issue_requires_one_severity(self) -> None:
        result = self.validate(
            title="bug(presentation): reprojection shakes during camera motion",
            body=self.managed_bug_body(),
            labels=["type:bug", "status:confirmed"],
        )
        self.assertFalse(result["valid"])
        self.assertIn(
            "managed Issue must have exactly one severity:* label",
            result["errors"],
        )

    def test_rejects_literal_scope_placeholder(self) -> None:
        result = self.validate(
            title="bug(scope): reprojection shakes during camera motion",
            body=self.intake_bug_body(),
            labels=["type:bug", "status:needs-triage"],
        )
        self.assertFalse(result["valid"])
        self.assertIn(
            "title still contains the literal scope placeholder", result["errors"]
        )

    def test_rejects_forbidden_solution_section(self) -> None:
        body = self.intake_bug_body() + "\n## Implementation plan\n\nRewrite it.\n"
        result = self.validate(
            title="bug(presentation): reprojection shakes during camera motion",
            body=body,
            labels=["type:bug", "status:needs-triage"],
        )
        self.assertFalse(result["valid"])
        self.assertIn(
            "Issue contains forbidden solution section 'Implementation plan'",
            result["errors"],
        )

    def test_accepts_structured_failed_attempt_ledger(self) -> None:
        attempts = """### A1 — Disable post effects

- Date: 2026-08-18
- Hypothesis tested: Post effects cause the visible shaking.
- Change / configuration: Disabled Post DoF and denoising.
- Commit / diff: None — runtime experiment
- Evidence: Shaking remained in the same grass workflow.
- Observed result: No meaningful improvement.
- Why it did not resolve the Issue: The defect reproduces without those effects.
- Disposition: rejected
"""
        result = self.validate(
            title="bug(presentation): reprojection shakes during camera motion",
            body=self.managed_bug_body(attempts=attempts),
            labels=["type:bug", "status:confirmed", "severity:S3-moderate"],
        )
        self.assertTrue(result["valid"], result["errors"])

    def test_rejects_incomplete_failed_attempt(self) -> None:
        attempts = """### A1 — Disable post effects

- Date: 2026-08-18
- Hypothesis tested: Post effects cause the visible shaking.
- Disposition: rejected
"""
        result = self.validate(
            title="bug(presentation): reprojection shakes during camera motion",
            body=self.managed_bug_body(attempts=attempts),
            labels=["type:bug", "status:confirmed", "severity:S3-moderate"],
        )
        self.assertFalse(result["valid"])
        self.assertTrue(
            any("A1 is missing 'Evidence:'" == error for error in result["errors"]),
            result["errors"],
        )

    def test_deferred_issue_requires_complete_context(self) -> None:
        body = self.managed_bug_body(deferred=False)
        result = self.validate(
            title="bug(presentation): reprojection shakes during camera motion",
            body=body,
            labels=["type:bug", "status:deferred", "severity:S3-moderate"],
        )
        self.assertFalse(result["valid"])
        self.assertIn("deferred Issue is missing 'Deferred context'", result["errors"])

        complete = self.validate(
            title="bug(presentation): reprojection shakes during camera motion",
            body=self.managed_bug_body(deferred=True),
            labels=["type:bug", "status:deferred", "severity:S3-moderate"],
        )
        self.assertTrue(complete["valid"], complete["errors"])

    def test_managed_environment_requires_reasons_for_unknowns(self) -> None:
        body = self.managed_bug_body().replace(
            "GPU / driver: NVIDIA RTX class / driver recorded in runtime attachment",
            "GPU / driver: Unknown",
        )
        result = self.validate(
            title="bug(presentation): reprojection shakes during camera motion",
            body=body,
            labels=["type:bug", "status:confirmed", "severity:S3-moderate"],
        )
        self.assertFalse(result["valid"])
        self.assertIn(
            "Environment field 'GPU / driver' must explain why it is unknown",
            result["errors"],
        )

    def test_managed_chinese_overview_rejects_form_placeholders(self) -> None:
        body = self.managed_bug_body().replace(
            "重投影已能在草地场景启用，但相机运动时发生高频全画面震动。",
            "用 2～4 句话说明这个 Issue 到底出了什么问题。",
        )
        result = self.validate(
            title="bug(presentation): reprojection shakes during camera motion",
            body=body,
            labels=["type:bug", "status:confirmed", "severity:S3-moderate"],
        )
        self.assertFalse(result["valid"])
        self.assertIn(
            "中文概要 still contains the problem placeholder", result["errors"]
        )

    def test_closed_issue_is_reported_without_state_mutation(self) -> None:
        result = self.validate(
            title="bug(scope): incomplete closed issue",
            body=self.intake_bug_body(),
            labels=["type:bug", "status:needs-triage"],
            state="closed",
        )
        self.assertFalse(result["valid"])
        self.assertNotIn("reopen", result)
        self.assertEqual("policy:incomplete", result["desired_policy_label"])

    def test_policy_and_forms_keep_stable_identity_fields(self) -> None:
        policy_path = REPOSITORY_ROOT / "scripts" / "policy" / "issue-policy.json"
        loaded = json.loads(policy_path.read_text(encoding="utf-8"))
        self.assertEqual(1, loaded["schema_version"])

        form_root = REPOSITORY_ROOT / ".github" / "ISSUE_TEMPLATE"
        for issue_type, type_policy in loaded["issue_types"].items():
            form = (form_root / type_policy["form"]).read_text(encoding="utf-8")
            self.assertIn(
                f'title: "{type_policy["title_prefix"]}(scope): "', form
            )
            self.assertIn(f'  - "{type_policy["label"]}"', form)
            self.assertIn('  - "status:needs-triage"', form)
            self.assertTrue(form.rstrip().endswith("- 关闭前必须验证："), issue_type)

        bug_form = (form_root / "bug.yml").read_text(encoding="utf-8")
        self.assertEqual(
            loaded["reproducibility"],
            self.dropdown_options(bug_form, "reproducibility"),
        )
        for form_name in ("bug.yml", "performance.yml"):
            form = (form_root / form_name).read_text(encoding="utf-8")
            self.assertEqual(
                loaded["variants"], self.dropdown_options(form, "variant")
            )
            self.assertEqual(
                loaded["render_devices"],
                self.dropdown_options(form, "render-device"),
            )
            self.assertEqual(
                loaded["presentation_paths"],
                self.dropdown_options(form, "presentation-path"),
            )

    def test_workflow_never_changes_issue_open_state(self) -> None:
        workflow_path = (
            REPOSITORY_ROOT / ".github" / "workflows" / "issue-policy.yml"
        )
        workflow = workflow_path.read_text(encoding="utf-8")
        self.assertNotIn("issues.update({", workflow)
        self.assertNotIn("state: open", workflow)
        self.assertNotIn("gh issue reopen", workflow)
        self.assertIn("issues.addLabels", workflow)
        self.assertIn("issues.removeLabel", workflow)
        self.assertIn("issues.listComments", workflow)
        self.assertIn("issues.createComment", workflow)
        self.assertIn("issues.updateComment", workflow)
        self.assertEqual(2, workflow.count("github.rest.issues.get"))
        self.assertNotIn("context.payload.issue.labels.map", workflow)
        self.assertIn("group: issue-policy-${{ github.event.issue.number }}", workflow)
        self.assertGreaterEqual(workflow.count("retries: 3"), 2)
        self.assertIn("result.policy_labels", workflow)
        self.assertNotIn("policy:complete", workflow)
        self.assertNotIn("policy:incomplete", workflow)


if __name__ == "__main__":
    unittest.main()
