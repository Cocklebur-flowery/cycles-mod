# C2 实时稳定性深度优化（已废弃）

状态：`ABANDONED`
日期：2026-08-17
适用范围：仅作为失败实验的历史记录，不代表当前实现、当前计划或可恢复的开发阶段。

## 结论

C2-1～C2-6 的联合实现已在实机验收中暴露重大控制流、会话生命周期和性能问题，
无法通过局部修补安全收口。该实现已整体回退，不得重新应用到生产源码，也不得把
当时的自动构建与 smoke 结果描述为功能通过。

当前生产基线是提交 `efad9d17a9033c4039c3106f6292114a9eba7480`，Java 与 Native
桥接 ABI 均为 43。C2 曾尝试的 ABI44、Active/Staging 双 Session、深度重投影、
512 三角形 micro-mesh、deadline controller、writer handoff 和附加 Cycles patch
均不属于当前产品行为。

## 废弃原因

1. Staging 开始时暂停 Active Session，同时阻止 Active 相机渲染，违反“Active 在
   场景更新期间持续产生新源帧”的核心契约。
2. promotion 路径在运行中的 Session 上替换 Vulkan display driver，实际所有权与
   文档声称的“启用同一个 driver”不一致，缺少可靠的 PathTrace/interop 生命周期证明。
3. Section 被拆为大量 512 三角形 micro-mesh，增加 Cycles Mesh/Object 与 BVH 工作量；
   创建路径还会重复写入新 slot，没有实现可独立验证的持久 BLAS/TLAS 后端。
4. 该阶段一次跨越 Java 调度、Native worker、C ABI、Vulkan interop、显示 shader、
   Cycles 上游 patch 和多种资源生命周期，失败边界无法隔离，超出可安全修复的阶段范围。

## 实机失败证据

2026-08-17 的 ABI44 实机样本运行约 6 分钟，性能日志记录约 16,715 个 Minecraft 帧，
但 Cycles 最多只产生 2 张源帧。场景请求已推进到 revision 161、383 个 Section，
`mesh_geometry` 峰值约 6.12 秒，场景到首帧时间约 7.86 秒。运行期间曾进入
`scene-staging`，随后长期无法维持有效的实时源帧供给。

该结果推翻了原文“C2-1～C2-6 已实现并通过自动验证”的结论。自动测试只证明部分
布局、编译和 isolated smoke 契约，未覆盖连续 mutation、双 Session GPU 竞争、
interop promotion 和持续帧生产的组合生命周期。

## 保留与禁止事项

- 失败实现保存在工作区外的 `cycles-mod-c2-abandoned-20260817-rollback` 归档中，仅供审计。
- 禁止直接或分段重新应用归档 patch；其中任一想法都必须作为新的独立方案重新论证。
- 禁止从归档复制 ABI44 数值、结构布局、shader、Session 状态机或 Cycles patch 到当前源码。
- 当前性能工作继续以 ABI43 和已提交的 V0 基线为准；A1 仍只允许执行已选定的单算法、
  单指标和正确性 oracle，不得夹带 C2 生命周期或架构改造。
- 若未来重新研究实时稳定性，必须先建立可在错误实现上失败的持续帧生产、Active 独立性、
  promotion rollback、interop writer ownership 和显存峰值测试，再提出新的分阶段计划。

## 当前验证要求

回退后的可运行产物必须从 ABI43 源码重新生成，不能继续使用 C2 留下的 ABI44 DLL、
Java classes 或 JAR。默认与 experimental DLSS 两种 `verifyProject` 均通过后，还必须
完成 Minecraft 进入世界、Cycles 首帧、移动、F8 关闭恢复原版、再次启用和正常退出验证。
