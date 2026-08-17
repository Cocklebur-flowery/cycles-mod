# Cycles Renderer Git 提交信息规范

> 规范版本：0.3
>
> 状态：仓库级提交规范
>
> 最后更新：2026-08-16
>
> 生效边界：自 `5ff245e` 的后续提交起适用；既有历史不追溯整改

本文规定 Cycles Renderer 提交信息如何记录变更原因、跨语言契约、资源所有权、验证变体和运行证据。目标是让 Git 历史不仅说明“改了什么”，还足以支持 ABI 回归、GPU/Backend 差异、Session 生命周期、画面变化和长周期维护调查。

仓库根目录的 `.gitmessage` 是编辑器提示模板；本文是它的语义来源，并受根目录 [工程约束](../AGENTS.md) 管理。

## 1. 为什么 Cycles 需要增强正文

Cycles 同时跨越：

- Minecraft/NeoForge Java 生命周期。
- Java FFM 与稳定 C ABI。
- Native Session、Scene、Camera、Pass 和 Denoiser。
- OptiX、CUDA、CPU 与实验性 DLSS 变体。
- Vulkan external memory、timeline semaphore 与 CPU 上传回退。
- Shader、材质、纹理、OCIO、HDR 和显示管线。
- 第三方 Cycles 源码、正式 patch、构建目录和 packaged native artifact。

一个简短标题可以描述结果，却通常无法说明：ABI 是否改变、哪个 Backend 实测、哪种变体未运行、Frame generation 是否重置、资源由谁释放，或者完整验证为什么返回非零。

提交正文应保留这些决策和证据，但不应复制整个测试日志。只记录理解、复现和回退该提交所需的信息。

## 2. 完整格式

```text
<type>(<scope>): <imperative summary>

Why:
- <confirmed problem, requirement, invariant, or measurement>

Changes:
- <observable change and owning responsibility>
- <failure, reset, fallback, or cleanup behavior>

Contracts:
- ABI / layouts: <changed or deliberately preserved contract>
- Ownership / lifecycle: <thread, handle, lease, generation, reset, close>
- Settings / resources: <keys, IDs, defaults, formats, pipeline order>

Validation:
- PASS `<command or suite>` — <variant and evidence>
- KNOWN RED `<command or suite>` — <earliest failing domain>
- NOT RUN `<check>` — <exact reason>

Variants / Backends:
- default / OPTIX — <result>
- DLSS / OPTIX — <result>
- CPU fallback — <result or exclusion reason>

Compatibility / Risks:
- <migration, fallback, upstream, packaging, or rollback>

Runtime evidence:
- PASS `<Minecraft workflow>` — <backend, device, mode, observation>

Known limitations:
- <remaining named limitation>

Stage:
- <authoritative stage document or stage ID>

Commit-Level: M

Refs: <issue, contract, upstream revision, or authoritative document>
BREAKING CHANGE: <consumer impact and migration>
```

根据风险选择第 3 节中的最小充分级别，并删除不适用的可选 Section。

## 3. 三档信息级别

### 3.1 Level S：简短提交

只写标题和机器可读的 `Commit-Level: S` trailer。仅在以下条件全部成立时使用：

- 改动小且含义显然。
- 不改变运行行为、画面、性能、配置、资源、ABI 或生命周期。
- 不需要说明变体、Backend、手工验证或未验证项。
- 不涉及生成输入、第三方 patch 或 artifact 身份。

适用：拼写、链接、纯注释和无语义格式修正。

```text
docs(readme): fix the native build link

Commit-Level: S
```

### 3.2 Level M：标准提交

普通功能、修复、测试、重构和构建工作的默认级别。

必须包含：

- 标题。
- `Why`。
- `Changes`。
- `Validation`。

按需增加：

- `Compatibility / Risks`。
- `Known limitations`。
- `Stage` 与 `Refs`。

```text
test(native): report smoke domains independently

Why:
- A failure in an early smoke domain hid the status of later independent
  renderer capabilities.

Changes:
- Register each smoke domain as an independently reported CTest case.
- Preserve shared setup while returning explicit pass, fail, and skip codes.

Validation:
- PASS `ctest --test-dir build/native -C Release` — reported contract, color,
  render, denoiser, lifecycle, and scene-update domains independently.

Compatibility / Risks:
- Production ABI and renderer behavior are unchanged.

Commit-Level: M
```

### 3.3 Level H：高风险提交

以下任一情况成立时使用完整高风险模板：

- 修改 C ABI、FFM Layout、Symbol、Struct、Enum、Flag 或拒绝行为。
- 修改 Frame slot、generation、lease、HANDLE、timeline value 或同步所有权。
- 修改 Session create/reset/disable/re-enable/close 或设备切换。
- 修改 Scene/Camera/Material/Pass/Denoiser 的失效级别。
- 修改 Vulkan/CUDA external memory、semaphore、queue 或 CPU fallback。
- 修改 Shader 名称、Keyword、Uniform、Sampler、Format 或 Pipeline 顺序。
- 修改配置键、默认值、范围、持久化或 UI 可见性。
- 修改第三方 patch 顺序、upstream revision、setup fingerprint 或 artifact。
- 修改 PBR、色彩、曝光、HDR、透明、降噪等可见画面。
- 修改 Native 依赖、工具链、打包或实验变体。
- 修复崩溃、竞态、数据损坏、设备丢失或难复现生命周期问题。

必须逐项评估：

- `Contracts`。
- `Variants / Backends`。
- `Compatibility / Risks`。
- `Runtime evidence`。
- `Known limitations`。

没有执行的高风险验证不能通过删除 Section 隐藏，应标记 `NOT RUN`、`BLOCKED` 或 `KNOWN RED`。

## 4. 标题规则

格式：

```text
<type>(<scope>): <imperative summary>
```

规则：

- 使用英文祈使语气，描述提交完成后的一个结果。
- 使用最窄、稳定、长期可搜索的 scope。
- 自动检查要求标题不超过 72 个字符，不以句号结尾。
- 不写文件名清单、日期、测试状态或设备详情。
- 不用 `misc`、`stuff`、`changes`、`updates`、`final` 等模糊词。
- 不把 Spike、基线、接口占位或部分 Backend 路径声明成完整功能。
- 若标题需要 `and` 连接无关结果，应拆分提交。

正确：

```text
fix(camera): rebuild sessions for panorama topology changes
build(abi): generate the Vulkan interop state layout contract
perf(cycles): avoid atlas mesh-light rebuilds
test(pbr): isolate the green texture smoke baseline
```

不正确：

```text
feat-pbr-tune-foliage-principled-bsdf
update native stuff
fix everything
final renderer changes
camera and denoiser improvements
```

## 5. Type 词表

| Type | 用途 | 证据要求 |
| --- | --- | --- |
| `feat` | 新的用户/开发者可用能力 | 能力入口、成功与失败路径 |
| `fix` | 已确认错误或回归 | 根因、回归测试、受影响边界 |
| `perf` | 保持语义的性能改善 | 同环境前后测量与画面/契约一致性 |
| `refactor` | 外部行为保持不变的责任重组 | Characterization/contract 测试 |
| `test` | 测试能力、fixture 或诊断断言 | 测试能在错误实现上失败的证据 |
| `docs` | 仅文档和注释 | 链接、当前事实和 diff 检查 |
| `build` | Gradle、CMake、ABI 生成、依赖和打包 | 干净或适用变体构建 |
| `ci` | 自动验证流水线 | 触发条件和失败传播 |
| `chore` | 仓库维护，无更准确类型 | 证明无产品语义变化 |
| `revert` | 撤销已有提交 | 目标 hash、原因和恢复验证 |

不要因为改了测试就使用 `test`，也不要因为改了构建文件就自动使用 `build`。Type 由提交的主要结果决定。

## 6. Scope 词表

| Scope | 责任 |
| --- | --- |
| `render` | 渲染请求、帧生产和总体渲染行为 |
| `scene` | Minecraft 场景缓存、增量更新和 Scene sync |
| `native` | Native bridge 私有实现与通用 smoke |
| `abi` | C ABI、FFM Layout、Symbol、生成契约和拒绝行为 |
| `interop` | 跨 API memory/semaphore/frame transport |
| `vulkan` | Vulkan 设备、资源和命令路径 |
| `camera` | 投影、物理镜头、全景、焦点和 Camera reset |
| `sampling` | Integrator、seed、samples 和自适应采样 |
| `denoiser` | OptiX/OIDN/DLSS 调度、输入和输出 variant |
| `color` | Working space、OCIO、曝光、白平衡和显示变换 |
| `hdr` | FP16、scRGB、HDR 输出和 SDR fallback |
| `pbr` | LabPBR 数据和整体材质语义 |
| `material` | Shader graph、BSDF、透明和材质构建 |
| `texture` | Atlas、采样、颜色标记和资源重载 |
| `config` | 稳定设置、默认值、持久化和失效级别 |
| `ui` | 设置编辑器、HUD、F10 和用户交互 |
| `mixin` | Minecraft 注入点和兼容性 |
| `runtime` | Mod/Client 生命周期、启动、关闭和 fallback |
| `diagnostics` | 结构化错误、状态、日志和 telemetry |
| `cycles` | Cycles 上游引擎集成和私有调用策略 |
| `offline` | 与调用者无关的公共离线渲染能力 |
| `build` | Gradle/CMake 工具链和产物图 |
| `patch` | 上游 revision、正式 patch 和 setup fingerprint |
| `repo` | 仓库政策、模板和维护 |
| `docs` | 跨领域文档体系 |

Scope 不使用开发者名、临时分支、提交 hash、Stage 编号或具体文件名。跨 Java/Native 的稳定契约变更应选择 `abi` 或拥有该契约的能力，而不是使用 `java-native`。

## 7. 正文 Section 规则

### 7.1 Why

记录已经确认的原因：用户可见问题、首个底层错误、生命周期不变量、基准瓶颈、Stage 要求或上游变化。

避免：

- “按要求修改”。
- “优化代码”。
- “为了更稳定”。
- 没有证据的根因推断。

### 7.2 Changes

描述行为和所有权，而不是逐文件抄写 diff：

- 哪个组件现在创建、借用、失效或释放资源。
- 哪个设置变化映射到哪个 reset level。
- 哪个失败现在保留原始错误或进入 fallback。
- 哪个 Scene/Camera/Pass revision 触发同步。
- 哪个可见材质、颜色或输出语义改变。

### 7.3 Contracts

高风险提交按相关维度写明改变或保持：

```text
Contracts:
- ABI / layouts: Preserve ABI version, struct size, offsets, enum IDs, and
  mismatch rejection.
- Ownership / lifecycle: Transfer the frame lease only after semaphore wait;
  repeated close remains idempotent.
- Settings / resources: Preserve config keys, defaults, shader IDs, formats,
  and pipeline ordering.
```

#### ABI / Layout

至少检查：

- ABI Version。
- Struct `size/alignment/offset`。
- Enum/Flag 数值。
- Java Layout 与 Native Header。
- Symbol 名称和参数。
- 旧/新版本拒绝路径。

#### Ownership / Lifecycle

至少检查：

- 创建者和销毁者。
- Java、Native、Vulkan、CUDA 或 Backend 的 owning thread/domain。
- HANDLE/memory/semaphore 是 owned、borrowed、duplicated 还是 leased。
- generation、timeline value 和 reset 如何使对象失效。
- create、first frame、resize、reset、fallback、disable、re-enable、close。

#### Settings / Resources

至少检查：

- 配置键、默认值、范围和 enum ID。
- 设置对应的 invalidation/reset level。
- Resource/Shader/Sampler/Uniform 名称。
- Pixel format、color space 和 pipeline order。
- 资源重载、关闭和 fallback。

### 7.4 Validation

格式：

```text
Validation:
- PASS `<command or suite>` — <variant, domain, and evidence>.
- FAIL `<command or suite>` — <first actionable failure>.
- KNOWN RED `<command or suite>` — <named existing domain>.
- BLOCKED `<check>` — <external state or missing capability>.
- NOT RUN `<check>` — <exact reason>.
```

规则：

- 完整命令返回非零时，不能把完整命令写为 PASS；应记录通过的子域和整体 KNOWN RED/FAIL。
- `build` PASS 不等于 Native smoke、Minecraft startup 或 GPU 运行时 PASS。
- 一个 smoke frame 不等于真实 Scene、steady-state、resize 或 close PASS。
- 不允许用较早 timeout 隐藏后续域；说明 skipped/blocked 原因。
- 只记录本次实际执行证据，不从旧基线复制结果。
- 临时或一次性检查应记录实际的仓库相对命令；稳定命名的 suite/workflow
  必须能从仓库验证入口或文档定位，不能使用无法复现的自定义简称。
- 不记录 Token、凭据、私有主目录、用户名、主机名、设备序列号或其他
  与结论无关的机器标识。设备相关验证只保留 Backend、设备类别和复现所需
  的最小环境信息。
- 不把原始日志倾倒进正文；记录首个可行动错误、受影响域和仓库内可定位的
  诊断入口。

### 7.5 Variants / Backends

涉及 Native、Shader、Denoiser、Interop 或设备策略时，明确测试矩阵：

```text
Variants / Backends:
- default / OPTIX — PASS native render smoke on the selected device.
- DLSS / OPTIX — PASS isolated experimental build and render smoke.
- CUDA — NOT RUN; the change does not select this backend in the current rig.
- CPU fallback — PASS initialization and first-frame fallback contract.
```

不要从一个 Backend 推断另一个 Backend。设备相关结果应记录足以复现的设备类别；详细驱动、尺寸和性能数据放在质量基线或 Stage 文档，正文只保留必要上下文。

### 7.6 Compatibility / Risks

评估：

- ABI/配置/资源是否需要迁移。
- 旧 artifact、旧 patch 或旧 upstream 是否被拒绝。
- 缺失 Vulkan/CUDA/DLSS/OCIO/denoiser 时如何 fallback。
- 画面有意改变的范围。
- 性能、显存、缓存和 Session 重建成本。
- 回退或 revert 是否安全。

“无风险”不是有效描述。应说明检查过哪些边界没有变化。

### 7.7 Runtime evidence

修改 Minecraft Hook、Interop、Presentation、Camera、UI 或 GPU 生命周期时通常必需。

记录操作链而不是只写 `runClient`：

```text
Runtime evidence:
- PASS `runClient` — loaded a singleplayer scene on OPTIX, received the first
  frame, moved the camera, resized the window, disabled/re-enabled the renderer,
  and returned to the title screen without a stale lease.
```

运行证据应区分：客户端启动、Renderer enable、第一帧、真实场景、steady-state、resize/reset/fallback 和 shutdown。

### 7.8 Known limitations

列出提交完成后仍相关的已知红项。不能把主体成功条件缺失写成 limitation 后宣布功能完成。

### 7.9 Stage 与 Refs

`Stage` 指向当前权威阶段文档或独立验收阶段。`Refs` 可指 Issue、ABI Schema、正式 patch、upstream revision 或权威文档。

不要引用聊天消息、本机绝对路径、临时日志或被忽略构建目录作为唯一依据。

Issue 不是每个 `fix` 或 `perf` 提交的前置条件。开发中直接发现并完成验证的小型错误或
性能改进，不需要为了提交而临时制造 Issue。

如果同一个问题已经进入 Issue 生命周期，后续实质性调查、回归测试、修复和性能提交必须
使用现有 footer 关联：

```text
Refs: #123
```

跨仓库引用使用 `Refs: owner/repository#123`。Issue 晚于早期提交创建时不重写历史；在 Issue
的 `Related` 中补记旧 hash，后续提交再使用 `Refs`。本地提交校验器只能验证已经写出的引用
格式，不能可靠推断仓库中是否存在语义相同的 Issue，因此不得实现“所有 fix/perf 必须先有
Issue”的全局规则。

Commit message 禁止使用 `Closes #123`、`Fixes #123` 或 `Resolves #123`。关闭权属于满足
Resolution criteria 的 PR，或完成显式人工验收后的手动关闭；不能让一个中间提交提前结束
问题生命周期。完整 Issue 语义见 [Issue 规范](issue-conventions.md)。

## 8. 按变更类型选择验证正文

### 8.1 Java 私有实现

至少记录聚焦测试和 Java build。若影响资源加载、Mixin 或 Native 调用，还需 package/startup 证据。

### 8.2 Native 私有实现

至少记录 Native build、相关 CTest suite 和适用变体。若完整 suite 有已知红项，逐域记录，不得写全绿。

### 8.3 ABI/Layout

至少记录：Schema/Generator、Java compile、Native compile、size/offset assertions、mismatch rejection、相关 smoke，以及 Default/Experimental artifact 一致性。

### 8.4 Shader、PBR 与颜色

至少记录：Shader/Native build、相关 smoke、输入资源、工作色彩空间、受影响材质/Pass，以及实际画面验证。性能和画面变化不能只凭截图主观描述。

### 8.5 Interop 与 Session

至少记录：create、first frame、steady-state、resize、reset、fallback、disable/re-enable、close，以及 HANDLE/lease/generation 失效证据。

### 8.6 Patch、Setup 与依赖

至少记录：目标 upstream revision、apply-check、reverse/fingerprint、Default/Experimental 变体、目标构建和 packaged artifact identity。

### 8.7 文档与仓库政策

记录链接、结构、模板行为、diff/whitespace 检查。通常不运行 GPU 或 Minecraft 验证，并明确 `NOT RUN` 原因。

## 9. 提交拆分

一个 commit 对应一个责任、生命周期、稳定契约或可独立验证 Stage。

应拆分：

- 修复与无关重构。
- ABI 契约与后续私有优化，除非两侧必须原子更新。
- Default 功能与未完成实验变体。
- 生产源修改与独立仓库政策。
- PBR 画面调整与 Denoiser 生命周期修复。
- 正式 patch 与探索性上游源码修改。
- Cycles 与 RayPortal 两个仓库的改动。

不应拆分：

- ABI Schema、生成布局、Java/Native 消费者和拒绝测试。
- 新资源 ID 与必须同时注册的资源。
- 状态转换与证明该转换的合同测试。
- 配置字段、持久化、UI metadata 和 Native mapping。

提交拆分不能制造无法构建或无法验证的中间历史。

## 10. 性能提交的额外要求

`perf` 正文必须说明：

- 基线与修改后使用相同 Scene、Backend、Device、尺寸和设置。
- 观测指标和采样窗口。
- 结果是否影响画面、sample、延迟、显存或 CPU/GPU 同步。
- 未测 Backend/设备。
- 性能改善来自哪个责任变化，而不只是百分比。

```text
perf(cycles): avoid atlas mesh-light rebuilds

Why:
- Performance traces showed unchanged atlas revisions rebuilding mesh lights
  during steady-state frames.

Changes:
- Reuse mesh-light state while atlas and scene revisions remain unchanged.

Validation:
- PASS `<focused trace>` — reduced rebuild count from <before> to <after> on
  the same scene, device, resolution, and sampling settings.
- PASS `<render smoke>` — preserved frame publication and scene content.

Compatibility / Risks:
- No ABI or settings changes; atlas revision changes still force a rebuild.

Commit-Level: M
```

不要在 commit message 中保留没有对应基线的“快 30%”。

## 11. 可见画面提交的额外要求

PBR、Color、HDR、Denoiser、Transparency、Camera 和 Shader 提交应说明：

- 预期改变的像素/材质范围。
- 必须保持不变的 fallback 或其他材质。
- 输入资源和工作色彩空间。
- Smoke/golden/数值阈值。
- 游戏内观察和已知主观部分。

不得用“看起来更好”替代能量、颜色、透明、法线或采样契约。

## 12. Revert 规范

Revert 仍使用统一的 type/scope 标题，并在正文说明原因、恢复行为和验证。
使用 `Reverts` footer 保存被撤销提交的完整 hash：

```text
revert(pbr): restore the previous glass transmission

Why:
- The change introduced a green transmission regression in the smoke scene.

Changes:
- Restore the material behavior from before the regressing change.

Validation:
- PASS `<focused PBR smoke>` — restored the previous texture-content baseline.

Reverts: <full commit hash>

Commit-Level: M
```

除非用户明确要求，不用 amend、rebase 或历史重写隐藏已经共享或具有调查价值的失败实验。

## 13. 反例

### 只有标题，没有证据

```text
fix(native): fix session reset
```

缺少触发条件、reset level、受影响资源、验证变体和已知红项。

### 完整命令失败却写成 PASS

```text
Validation:
- PASS `verifyProject` — most tests passed.
```

应写整体 `KNOWN RED`/`FAIL`，并单独列出实际通过子域。

### 混合不相干责任

```text
feat(render): improve renderer

Changes:
- Add an ABI field.
- Tune foliage color.
- Refactor settings UI.
- Upgrade DLSS.
```

这些变化无法独立回退或验证，应拆分。

### 用 limitation 掩盖未完成主体

```text
feat(interop): complete Vulkan zero-copy

Known limitations:
- Semaphore ownership and close are not implemented.
```

核心生命周期未完成，不能宣称 complete。

## 14. 提交前检查清单

获得用户提交授权后：

1. 检查 `git status`，确认每个 tracked/untracked 路径的所有者。
2. 排除 `.deps/`、`.tools/`、`build/`、临时 baseline、缓存和探索性 patch。
3. 按精确 path/hunk 暂存并审查 staged diff。
4. 运行 `git diff --cached --check`。
5. 确认 type/scope/summary 与 staged 责任一致。
6. 根据风险选择 Level S、M 或 H。
7. 核对 ABI、所有权、设置/资源、变体/Backend 和兼容性。
8. Validation 只包含本次实际执行项，且失败/跳过/未运行没有被隐藏。
9. 作者和提交者身份符合用户要求。
10. 提交后检查正文、文件清单、工作树和未跟踪实验内容。

## 15. 使用 `.gitmessage`

`.gitmessage` 的提示行全部以 `#` 开头。未填写时，Git 清理后得到空消息；填写后只有真实正文进入 commit。

若用户希望本地 Git 编辑器自动加载模板，可在仓库根目录明确执行：

```text
git config --local commit.template .gitmessage
```

该命令修改仓库本地 Git 配置，不应由无关任务自动执行。使用 `git commit -F` 或多个 `-m` 的自动化提交仍必须遵守同一语义。

## 16. 自动检查与安装

仓库提供无项目依赖的 Python 3 validator，作为本地 Hook 和 CI 的唯一语义实现。新 clone 或新开发环境执行：

```text
python scripts/install-git-policy.py
```

该命令只设置当前 clone 的 `core.hooksPath=.githooks` 和 `commit.template=.gitmessage`，不修改全局 Git 配置。之后每次 `git commit` 都会经过 `commit-msg` Hook；CI 使用相同脚本检查 push/PR 新增的非 merge 提交。

手动检查单条消息或提交范围：

```text
python scripts/validate-commit-message.py --message-file <message-file>
python scripts/validate-commit-message.py --range <base>..<head> --boundary 5ff245e
```

`--boundary` 之前的历史提交不会被追溯整改。`--no-verify` 只能绕过本地 Hook，不能绕过 CI；禁止为了通过检查而使用它。Hook 拒绝时应修正消息后重试，不得把失败提交改写成虚假的 PASS。

## 17. 未授权提交纪律

长时间任务、自动续跑或上下文切换都不等于提交授权。AI 只有在当前用户明确要求提交、或当前用户明确授权的工作包范围内才能执行 `git commit`。提交 Hook 只检查格式，不替代这条授权规则。

## 18. 最终标准

“详细”意味着未来调查者能从提交正文判断原因、责任、契约、验证矩阵和开放风险。

“整洁”意味着一个提交只有一个结果，正文顺序稳定，没有空 Section、日志倾倒、虚假 PASS 或与当前责任无关的信息。
