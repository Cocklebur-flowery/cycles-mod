# Cycles Renderer Engineering Issue Convention

> Convention version: 0.1
>
> Status: repository engineering policy
>
> Applies to issues created or promoted after this policy is adopted

This document defines how Cycles Renderer records an engineering problem from
initial report through investigation, deferral, implementation, verification,
and closure. Its primary purpose is to preserve enough durable context for an
AI or human developer to resume work without guessing, repeating rejected
experiments, or treating an unverified hypothesis as a root cause.

The policy is deliberately stricter for issues that have entered the managed
engineering lifecycle than for new external reports. It must not make a
contributor understand the internal ABI, renderer lifecycle, or validation
matrix merely to report an observed problem.

The machine-readable vocabulary is
[`scripts/policy/issue-policy.json`](../scripts/policy/issue-policy.json). This
document is the human semantic authority; the policy JSON is the executable
fact authority. Validators and tests must load the JSON instead of maintaining
private copies of its types, scopes, states, severities, variants, devices,
presentation paths, required sections, or forbidden sections.

## 1. Engineering artifact boundaries

Each artifact has one primary responsibility:

| Artifact | Responsibility | May contain an implementation solution? |
| --- | --- | --- |
| Issue | The lifecycle of an observed problem or bounded unknown | No |
| Stage / ADR | An accepted design and execution decision | Yes |
| Commit | One concrete, atomic, validated engineering change | Only what actually changed |
| Pull request | A merge-ready group of related changes and aggregate evidence | Yes |
| Test | A behavior that must remain protected from regression | No |

In shorthand:

```text
Issue = what is wrong
Stage / ADR = what was decided
Commit = what changed
Pull request = what is ready
Test = what must not regress
```

An Issue records facts, evidence, attempted directions, current status,
workarounds, and observable closure criteria. It does not prescribe future
files, classes, shaders, algorithms, or implementation stages. Those decisions
belong in a Stage, ADR, or pull request after investigation supports them.

## 2. Issue title

Use:

```text
<issue-type>(<scope>): <observed problem or bounded question>
```

Examples:

```text
bug(presentation): asynchronous reprojection shakes during camera motion
perf(scene): section snapshots stall during sustained chunk streaming
investigation(interop): determine why timeline progress stops after resize
```

Rules:

- Use an issue type and scope from the policy JSON.
- Use English for the title so Git history, Issues, pull requests, and searches
  share the same technical vocabulary.
- Describe an observed problem or investigation question, not a proposed fix.
- Replace the literal `scope` placeholder inserted by an Issue Form.
- Keep the title within the policy maximum and do not end it with a period.
- Do not use vague summaries such as `fix everything`, `renderer issue`,
  `performance problem`, `misc`, `final`, or `updates`.

`presentation` is the owner of source-frame acquisition, depth reprojection,
post-process presentation inputs, and the final display chain. It is a stable
scope even though some earlier repository documentation grouped those duties
under `render`.

## 3. Intake and managed issues

### 3.1 Intake

Every Issue Form starts with:

```text
status:needs-triage
```

Intake exists to accept a useful external report without demanding internal
engineering knowledge. It requires only the minimum fields defined for that
issue type. Unknown details may be written as `Unknown` or `Not available`.

An intake issue does not need a Severity, complete ABI matrix, complete attempt
ledger, or repository closure checklist. Closing an intake issue as spam,
duplicate, invalid, or not planned does not invoke managed closure rules.

### 3.2 Managed lifecycle

An issue becomes a managed engineering record when its status changes to one of:

```text
status:confirmed
status:in-progress
status:blocked
status:deferred
status:verification
```

Before that transition, a maintainer or AI must normalize the body and preserve
all known evidence. A managed issue must satisfy the type-specific required
sections in the policy JSON, have exactly one Severity, and retain a first-class
Failed attempts ledger.

Strict validation applies to the decision to adopt and progress engineering
work, not to a person's ability to report a problem.

## 4. Severity

Severity describes current impact. It is not priority, schedule, ownership, or
an implied promise to work on the issue next.

### S1 Critical

- Data corruption.
- Crash or deadlock.
- Renderer unusable.
- Severe resource or security failure.
- No reasonable workaround.

### S2 Major

- A core capability is materially incorrect.
- Frequent severe performance degradation.
- An important backend or lifecycle path fails.
- A workaround exists but is not acceptable for continued use.

### S3 Moderate

- A localized capability is incorrect.
- Failure requires a specific combination.
- Moderate performance degradation.
- A reliable workaround preserves the main workflow.

### S4 Minor

- Edge case.
- Diagnostics or user-experience defect.
- Small technical debt with named impact.
- Main workflows remain unaffected.

Rules:

- `status:needs-triage` may temporarily have no Severity.
- Every managed open issue has exactly one `severity:*` label.
- A Severity change requires a comment naming the new fact or workaround.
- Issue Severity does not map to commit `Commit-Level: S/M/H`.

Suspected security vulnerabilities must not be disclosed in a public Issue.
A private reporting route must be confirmed before it is advertised by the
Issue chooser.

## 5. Normative English body

The English body is the engineering record. Forms may make some sections
optional during intake, but managed validation applies the policy JSON.

```markdown
## Summary

Describe the observed problem in one concise paragraph.

## Current behavior

Record observable facts. Do not assume a root cause.

## Expected behavior

Describe an observable correct outcome without prescribing an implementation.

## Reproduction

1. ...
2. ...
3. ...

Reproducibility: Always | Intermittent | Not reliably reproducible | Unknown

## Environment

- Commit:
- Working tree / artifact identity:
- Minecraft:
- NeoForge:
- ABI:
- Variant:
- Render device:
- Presentation path:
- GPU / driver:
- OS:
- Resolution:
- Relevant configuration:

## Evidence

### Runtime

...

### Logs / telemetry

...

### Relevant code / contracts

List entry points and stable contracts, not proposed edits.

## Impact

- Correctness:
- Stability:
- Performance:
- Visual output:
- Maintainability / investigation cost:

## Confirmed boundaries

Reproduces with:

- ...

Does not reproduce with:

- ...

Not evaluated:

- ...

## Investigation hypotheses

None recorded.

Or:

- HYPOTHESIS:
- Supporting evidence:
- Missing evidence:

## Failed attempts

None recorded.

Or use the attempt ledger defined in section 6.

## Workaround

- Workaround:
- Reliability:
- Lost capability / cost:

If none exists, write `None known.`

## Resolution criteria

- [ ] ...
- [ ] ...

## Deferred context

Only when deferred:

- Deferred reason:
- Last evaluated:
- Resume trigger:

## Related

- Issue:
- Stage / ADR:
- Commit:
- Pull request:
- Regression test:
- Documentation:
```

The body must not add formal sections named `Proposed solution`,
`Implementation plan`, `Recommended fix`, `Files to change`, `Work breakdown`,
or `Preferred algorithm`. A hypothesis is permitted only when it is explicitly
separated from confirmed facts.

## 6. Failed attempts are a first-class record

For long-running AI work, the attempt ledger is often the most valuable part of
an Issue. It prevents a new thread from presenting an old failure as a new idea.

Every managed issue contains `Failed attempts`. If no attempt exists, write:

```text
None recorded.
```

Otherwise append entries in chronological order:

```markdown
### A1 — <short name>

- Date:
- Hypothesis tested:
- Change / configuration:
- Commit / diff:
- Evidence:
- Observed result:
- Why it did not resolve the Issue:
- Disposition: reverted | retained-partial | rejected | inconclusive
```

Rules:

- Attempt IDs are stable and monotonically increasing: A1, A2, A3.
- Runtime configuration experiments count even when no source file changed.
- Use `None — runtime experiment` when there is no commit or diff.
- Keep retained partial changes in the ledger when they solved a subproblem but
  did not resolve the Issue.
- Do not delete a failed direction after a later attempt succeeds.
- Keep raw logs in comments or attachments; retain the decision-relevant summary
  in the body.
- A developer or AI must read the complete ledger before proposing new work.

## 7. Chinese overview

The final section of every managed issue is a short Chinese overview for rapid
human reading and context recovery. It is not a line-by-line translation and is
not an engineering fact authority. If it differs from the English body, the
English body wins.

It must appear after the English sections:

```markdown
## 中文概要

> 本节用于快速理解和上下文恢复，不是工程事实的规范来源。
> 如与上方英文正文存在差异，以英文正文为准。

### 问题

用 2～4 句话说明这个 Issue 到底出了什么问题。

### 当前结论

- 已确认：
- 尚未确认：
- 当前状态：

### 已失败 / 无效方向

- A1：
- A2：
- A3：

### 当前规避方案

...

### 以后重新处理时先看

- 最重要的证据：
- 不要重复尝试：
- 恢复工作的触发条件：
- 关闭前必须验证：
```

The overview must summarize the current English facts and attempt IDs. It must
not introduce a new root cause, solution, acceptance result, or severity.

## 8. Status semantics

| Status | Meaning |
| --- | --- |
| `needs-triage` | Intake report; not yet a complete engineering record |
| `confirmed` | Evidence is sufficient to plan work |
| `in-progress` | Investigation or implementation is active |
| `blocked` | A named external condition prevents useful progress |
| `deferred` | Confirmed problem retained for later work |
| `verification` | A candidate resolution exists and awaits closure evidence |

The policy workflow validates the status selected by a maintainer. It does not
change status, Severity, assignee, milestone, issue state, or body.

Deferred issues remain open. They record a reason, last evaluation, and an
observable trigger for resuming work.

## 9. Conditional Commit references

An Issue is not a prerequisite for every `fix` or `perf` commit. Small defects
and measured improvements may be discovered, implemented, and validated without
manufacturing an Issue merely to satisfy process.

When the same problem has already entered an Issue lifecycle, all later material
investigation, regression-test, fix, and performance commits reference it:

```text
Refs: #123
```

If the Issue was created after earlier commits, do not rewrite history. Add the
earlier hashes to `Related` and use `Refs` in subsequent commits.

Commit messages must not use `Closes #123`, `Fixes #123`, or `Resolves #123`.
Closure belongs to an accepted pull request or an explicit manual closure after
the Issue criteria have been reviewed.

A local commit validator can validate a provided `Refs` footer, but it cannot
infer whether a semantically equivalent Issue already exists. Do not implement
a global rule requiring every `fix` or `perf` commit to have an Issue.

## 10. Pull requests and tests

A pull request linked to an Issue uses `Refs #123` while work remains. It uses
`Closes #123` only when the Issue's observable resolution criteria are met.

The pull request author confirms:

- The Failed attempts ledger was reviewed before implementation.
- The change does not repeat a rejected attempt without new evidence.
- Relevant commits reference the existing Issue.
- Validation maps to the Issue's resolution criteria.

Tests use behavior-based names. They may include `Regression: #123` in a
comment when the historical reason would otherwise be lost, but Issue numbers
do not replace descriptive test names.

## 11. Policy workflow behavior

The workflow is advisory for intake and strict for managed records. It manages
only:

```text
policy:complete
policy:incomplete
```

It never opens, closes, or reopens an Issue. In particular, it must not reopen a
spam, duplicate, invalid, or deliberately closed Issue merely because managed
resolution fields are incomplete.

The workflow is idempotent:

1. Read current issue state and the policy JSON.
2. Compute the desired policy label and report.
3. Compare against current policy labels and the existing report.
4. Apply only the exact label delta.
5. Update one marked report comment only when its content changes.
6. If a workflow-owned `policy:*` label event produces no desired-state change,
   exit without another mutation.

The workflow never modifies type, status, Severity, assignee, milestone, body,
or open/closed state. A closed incomplete managed issue is reported as
`policy:incomplete`; the maintainer decides whether to reopen it.

## 12. Final discipline

Before starting work from an existing managed Issue:

1. Read the English body.
2. Read every Failed attempts entry.
3. Inspect linked commits, Stage/ADR, tests, and current source.
4. Reproduce the current state or record why reproduction is blocked.
5. Add new evidence without deleting prior failures.

Before claiming resolution:

1. Confirm every issue-specific resolution criterion.
2. Run only applicable repository gates and report their actual status.
3. Repeat the original runtime reproduction.
4. Update the attempt ledger and Related links.
5. Refresh the Chinese overview without changing the English authority.
