# 性能与 Cycles 画面核心阶段

状态：实施中  
起始提交：`6a12acd feat: add Cycles settings and pass diagnostics`  
目标平台：Minecraft 26.2、NeoForge 26.2.0.58、Blender Cycles 5.2、Windows Vulkan、RTX/OptiX

## 1. 阶段目标

本阶段先让性能问题可以被准确测量，再逐步替换当前临时显示路径，最终形成以下数据流：

```text
Minecraft Section 增量
  -> Cycles Scene / OptiX
  -> Cycles DisplayDriver（Scene Linear RGBA16F）
  -> Native latest-frame 环形缓冲
  -> Java FFM acquire/release
  -> Minecraft Vulkan RGBA16F 纹理
  -> OCIO Vulkan 显示变换
  -> Minecraft 主目标与 HUD
```

同时建立可扩展 Pass 缓存、交互/静止渐进采样、OptiX/OIDN 降噪、Blender AgX、ACES 2、蓝噪声和物理相机设置。Windows 真 HDR swapchain 与 CUDA/Vulkan 外部内存互操作是独立的高风险验证，不阻塞上述主路径。

## 2. 当前基线与瓶颈假设

### 2.1 已确认的实现事实

- Cycles 设备优先选择 OptiX，当前 RTX 设备能很快完成 1080p、32 samples 的路径追踪。
- Native `FrameStore` 在每次 OutputDriver Tile 更新时取回浮点 Pass，在 CPU 上逐像素执行曝光、sRGB、Gamma 与 RGBA8 量化。
- sRGB 与 Gamma 路径包含逐通道 `pow`；1080p 一帧最多涉及约 622 万个 RGB 通道值。
- `render_frame` 随后把完整 RGBA8 帧复制到 Java 管理的 FFM 缓冲。
- Java 在 generation 改变时使用 Minecraft Vulkan `writeToTexture`，仍会经过暂存缓冲和完整纹理复制。
- Section 更新在 Java 侧重新提取编译后网格，通过 FFM upsert/remove，再由 Native 提交到现有 Cycles Scene。
- 破坏/放置方块和新区块进入时，用户仍能感到约 0.1 秒级停顿；此前约 0.4 秒停顿已经通过增量 Section 流送明显缓解。

### 2.2 尚待数据证实的判断

目前表现与 CPU/内存传输受限高度一致：OptiX 很快达到目标 sample，随后 GPU 低负载，CPU 显示转换、整帧复制、Vulkan 上传或场景更新成为关键路径。但在加入分段遥测前，不能把全部卡顿都归因于某一个环节。

需要分别测量：

- Cycles 实际 sample 与目标 sample。
- Native Tile/Pass 读取与 CPU 显示转换时间。
- Native 到 Java 的复制字节数和时间。
- Java 到 Vulkan 的上传字节数和提交时间。
- 生产帧、上传帧、覆盖/丢弃帧和当前排队深度。
- Section 捕获、Java 缓存处理、FFM upsert/remove、Native commit 和 Cycles 场景重置时间。
- 相机、场景、设置分别导致的 accumulation/buffer/session reset 次数。

## 3. 调试指标契约

F10 叠加层采用“当前值 + 最近窗口统计”，不把目标值伪装成实际状态。

### 3.1 采样

- `Sample actual/target`：当前已完成的实际 sample / 当前交互或静止目标。
- `Sample state`：Interactive、Settling 或 Still。
- `Sample rate`：最近窗口内 sample/s；没有新样本时为 0，而不是沿用历史峰值。
- `Noise`：Cycles 可用时报告当前噪声估计；未启用自适应采样时明确显示 N/A。
- `Reset`：最近 reset 等级、原因与累计次数。

### 3.2 帧管线

- `Native display`：Cycles DisplayDriver 更新 scene-linear half4 的耗时（包含当前 OptiX 到主存的显示缓冲回读）。
- `Compatibility pull`：P7 前 half4 经查找表转换为 RGBA8 并复制到 FFM 的耗时。
- `Native copy`：Native 帧复制到 FFM 输出缓冲的耗时与字节数。
- `Vulkan upload`：Java 提交纹理上传的 CPU 时间、字节数和次数。
- `Frame produced/presented/dropped`：Cycles 生成、Minecraft 上传、latest-only 覆盖的帧数。
- `Frame age`：当前展示帧距其 Native 完成时刻的时间。
- `Resolution/format`：内部渲染尺寸、展示纹理尺寸和像素格式。

计时默认使用低开销单调时钟；展示最近一次、指数移动平均和最大值。计时不得为每个像素调用时钟，也不得在渲染热路径输出逐帧日志。

### 3.3 场景更新

- `Section pending/changed/removed/resident`。
- `Capture`：Minecraft Section 网格复制时间。
- `Scene Java`：缓存筛选和 FFM 数据准备时间。
- `Native upsert/remove`：桥调用耗时。
- `Scene commit`：Native 发布请求与 Cycles 场景同步耗时。
- `Scene revision` 与最近更新原因。

方块更新热点先只测量与定位。本阶段不会为了追求数字而改变 Section 数据格式、坐标系或 DH Provider 契约。后续优化必须以遥测显示的主耗时为依据。

## 4. 子阶段与提交边界

每个子阶段自动运行适用验证，检查 diff 后立即创建独立本地提交；游戏内人工验证不作为提交前置条件。验证发现的修复使用新提交，不 amend 已有提交。

| 子阶段 | 预期提交 | 主要交付物 | 状态 |
| --- | --- | --- | --- |
| P0 | `docs: record performance and image pipeline stage` | 本文档、指标字典、瓶颈假设 | 已完成（`7afa984`） |
| P1 | `feat: report actual render sampling` | ABI v7、实际/目标 sample、sample state/rate、F10 | 已完成（本提交） |
| P2 | `perf: add frame pipeline telemetry` | Native convert/copy、Java/Vulkan upload、帧计数 | 已完成（本提交） |
| P3 | `perf: trace section update latency` | 捕获/upsert/commit/队列与卡顿热点 | 已完成（Java `89d5c0d`，Native 本提交） |
| P4 | `perf: throttle display frame delivery` | 上传限频、latest-only、保留上一有效帧 | 已完成（本提交） |
| P5 | `perf: adopt cycles half-float display driver` | Cycles DisplayDriver、RGBA16F、移除热路径 CPU `pow` | 已完成（本提交） |
| P6 | `perf: add acquired frame ring buffers` | Native 三缓冲、FFM acquire/release | 已完成（本提交） |
| P7 | `feat: present scene-linear rgba16f frames` | Vulkan RGBA16F 与最小显示 Shader | 待开始 |
| P8 | `feat: add progressive interaction states` | Interactive/Settling/Still 和动态分辨率 | 待开始 |
| P9 | `feat: add typed pass cache` | Pass 注册表、内存预算、raw/denoised 分离 | 待开始 |
| P10 | `feat: schedule cycles denoisers` | OptiX/OIDN 能力、调度与 OIDN 构建 | 待开始 |
| P11 | `feat: integrate ocio color management` | OCIO Vulkan、AgX、ACES 2、工作/显示空间 | 待开始 |
| P12 | `feat: expose sampling and physical camera controls` | 原生蓝噪声、镜头、裁剪、景深 | 待开始 |
| P13 | `spike: evaluate hdr and vulkan interop` | Windows HDR 与 CUDA/Vulkan 互操作报告 | 待开始 |

## 5. 稳定契约和保持不动的范围

- ABI 从 v6 升级时保留 Scene resources、Section、Vertex、Triangle 和纹理布局；新增诊断字段只追加，不复用旧字段含义。
- Pass ID 只能追加，不重新编号；配置键保留并提供兼容默认值。
- Minecraft 到 Cycles 的坐标变换和纹理方向保持当前已验证结果。
- F8/F9/F10、原版回退、GUI/HUD 叠加和现有 DH 可选 Provider 保持可用。
- 本阶段不实现 Voxy、PBR/LabPBR/MTR 材质桥扩展、实体/流体/天气和 DLSS。
- 真 HDR 不伪装为“ACES/Rec.2020 已完成”：当前 Minecraft swapchain 仍是 8-bit sRGB，只有内部 HDR 与 SDR 显示映射完成后才能单独评估 HDR 输出。

## 6. 自动验证和人工里程碑

每个代码提交至少执行：

1. `gradlew.bat runNativeSmoke --console=plain`（涉及 Native 时）。
2. `gradlew.bat build --console=plain`。
3. ABI 尺寸/版本、枚举范围和诊断字段断言。
4. 检查实际 diff、未跟踪文件和意外生成物。

P1 至 P4 完成后进行一次 1080p 游戏人工里程碑：观察实际 sample、各段耗时、方块更新峰值、帧覆盖数、黑帧和输入响应。P5 至 P7 完成后再次测试 1080p/4K 内部尺寸，确认 CPU `pow` 热点消失且旧帧在新帧准备期间持续显示。

性能验收不设置脱离硬件和场景的固定 FPS 门槛。第一目标是让瓶颈可见、消除重复全帧 CPU 工作、避免黑帧和长同步停顿；帧率目标在得到真实遥测后冻结。

## 7. 实施记录

### P1：实际采样状态

- ABI v7 在保留 v6 诊断字段和 16 字节 reserved 区域后，追加目标 sample、采样状态和 sample/s。
- 原有 `sample_count` 修正为 Cycles `Progress.get_current_sample()` 报告的实际完成值；目标值不再写入该字段。
- 首次相机/场景变更标记为 Interactive，达到静止延迟后切换为 Still；Settling ID 已保留给后续渐进策略。
- F10 和 F9 状态摘要使用 `actual/target`，不再只显示配置目标。

### P2：帧管线遥测

- ABI v8 在 v7 字段之后追加 Native Pass/显示转换与帧复制的 last/EMA/max 微秒值。
- Native 统计成功产生的显示更新、复制到 FFM 的帧、复制字节和 generation 未变化的轮询；帧龄在查询时计算。
- Java 统计整个 NativeBridge 帧调用的 CPU 时间，以及 Minecraft Vulkan `writeToTexture` 入队的 CPU 时间、上传次数/字节和 generation 间隙。
- 这些数值描述 CPU 提交与内存搬运，不冒充 GPU 执行时间；GPU 时间需要后续 Vulkan timestamp query 才能确认。

### P3a：Java Section 更新遥测

- `SectionGeometryCollector` 以原子计数记录编译网格 decode/copy 的 last/EMA/max、捕获数、等待数和同 Section 覆盖数。
- `SectionSceneManager` 记录每个渲染帧的场景筛选总时间，以及 FFM upsert/remove/commit 各自的 last/EMA/max。
- F10 将这些值与帧管线值同时显示；方块更新卡顿时可先判断峰值发生在 Minecraft 网格捕获、Java/FFM，还是后续 Native/Cycles 场景应用。

### P3b：Native/Cycles 场景更新遥测

- ABI v9 追加 Native `commit_scene` 整 Section 映射快照复制、Cycles `apply_scene_delta` 和 `session.reset/start` 三段计时与计数。
- `commit_scene` 在调用线程复制常驻 Section 映射，理论成本会随视距和 Section 数增长；现在可将 Java FFI commit 与 Native commit 两行对照，判断它是否就是 0.1 秒主线程热点。
- scene delta 和 render start 在 Cycles worker 上执行，可能通过 CPU 竞争造成帧时间尖峰，但不会被误记为主线程 FFI 时间。

### P4：相机与成品帧交付解耦

- ABI v10 新增轻量 `update_camera`；Minecraft 仍在每个世界渲染帧投递最新相机，因此 120 Hz 以上输入不会被成品帧上传频率截断。
- RGBA8 成品帧拉取与 Vulkan 上传临时限制为最高 120 Hz，并始终读取最新 generation；超过交付预算的中间帧不形成复制队列。
- Section reset/commit 不再销毁当前展示纹理。新 Cycles 帧准备期间继续复用上一有效纹理，避免场景更新主动制造黑帧。
- F10 分别报告相机队列和成品帧拉取的 last/EMA/max、调用/跳过次数。120 Hz 是替换 RGBA8 临时路径前的过渡常量，后续由渐进采样策略配置化。

### P5：Cycles half-float DisplayDriver

- ABI v11 用 Cycles 官方交互 `DisplayDriver` 取代离线 `OutputDriver`，Native 帧源现在直接保存 scene-linear `half4`/RGBA16F。
- OptiX 的显示 Pass 由 Cycles 写入 half buffer，不再先读 float Pass、分配临时 float 数组，再在每个像素上执行曝光、sRGB、Gamma `pow` 和 RGBA8 量化。
- 为保持 v10 `render_frame` 和当前 Minecraft RGBA8 上传路径可运行，Native 在被拉取时使用按 half 位模式预计算的 65,536 项查找表兼容转换；`pow` 只在设置变化时构建查找表，不在逐帧逐像素热路径执行。
- `CyclesBridgeDiagnostics.frame_pixel_format` 和 F10 明确区分内部 `RGBA16_FLOAT` 与临时拉取 `RGBA8_UNORM`。P6/P7 完成后兼容转换才会从实时显示路径完全移除。

### P6：Acquired-frame 三槽环

- ABI v12 新增 72 字节 `CyclesBridgeFrameView`、`acquire_frame` 和 `release_frame`。View 返回只读 RGBA16F 指针、字节数、generation、sample、格式和不可伪造的槽位 token。
- Native 维护 3 个独立 half4 槽位；当前发布槽与读者仍持有的槽位都不会被 Cycles 选为下一写入目标。没有空闲槽时丢弃本次显示更新，不阻塞渲染线程等待 Java。
- Java FFM 将 Native 指针重新解释为受限 `ByteBuffer`，`AcquiredFrame.close()` 先使 Java view 失效，再释放 Native token。消费者必须使用 try-with-resources，且不得跨 `NativeBridge.close()` 保存租约。
- F10 新增 active/peak lease、槽位数和因槽位繁忙而丢弃的显示更新；Native smoke 验证 updated/unchanged acquire、RGBA16F 字节数、release 和租约遥测。
