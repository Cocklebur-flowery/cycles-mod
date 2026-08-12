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

## 2. 阶段起始基线与瓶颈假设

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
| P7 | `feat: present scene-linear rgba16f frames` | Vulkan RGBA16F 与最小显示 Shader | 已完成（P7a/P7b） |
| P8 | `feat: add progressive interaction states` | Interactive/Settling/Still 和动态分辨率 | 已完成（P8a/P8b） |
| P9 | `feat: add typed pass cache` | Pass 注册表、内存预算、raw/denoised 分离 | 已完成（P9a/P9b，待游戏内人工验收） |
| P10 | `feat: schedule cycles denoisers` | OptiX/OIDN 能力、调度与 OIDN 构建 | 已完成（P10a/P10b/P10c，待游戏内人工验收） |
| P11 | `feat: integrate ocio color management` | OCIO Vulkan、AgX、ACES 2、工作/显示空间 | P11a-P11d 已完成自动验证；P11c/P11d 待游戏内验收 |
| P12 | `feat: expose sampling and physical camera controls` | 原生蓝噪声、镜头、裁剪、景深 | P12a-P12d 已完成自动验证；待游戏内验收 |
| P13 | `spike: evaluate hdr and vulkan interop` | Windows HDR 与 CUDA/Vulkan 互操作报告 | 已完成；实现拆分为 P14-P17 |
| P14 | `feat: probe vulkan interop capabilities` | Vulkan/Cycles UUID、扩展与 HDR surface 只读探针 | 已完成自动验证；待游戏内 F10 验收 |
| P15 | `feat: prototype external vulkan buffer` | 固定尺寸外部 buffer、单缓冲握手与 Vulkan GPU copy | 已完成自动验证；待游戏内 F8/F10 验收 |
| P16 | `feat: synchronize vulkan interop ring` | 三槽生命周期与 CUDA/Vulkan 正式同步 | 待开始 |
| P17 | `feat: prototype hdr swapchain` | Windows HDR surface 与输出 shader 原型 | 待开始 |
| RT-P1 | `perf: publish incremental native scene updates` | Native commit 合并增量、工作线程确认、完整重建恢复 | 已完成自动验证；待游戏内 F10 对比 |

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

### P7a：直接上传 half-float

- Minecraft 实时帧改为消费 ABI v12 acquired view，不再调用 `render_frame` 的 RGBA8 兼容拷贝。
- Presenter 创建 `GpuFormat.RGBA16_FLOAT` 纹理并按每像素 8 字节上传；命令上传记录后立即释放 Native 槽位租约。
- RGBA8 转换仍供 native smoke 与旧调用者使用，但已离开游戏实时渲染循环。

### P7b：最小 GPU 显示变换

- NeoForge 注册 `cyclesrenderer:pipeline/present` 全屏 pipeline，输入为 scene-linear RGBA16F，输出到 Minecraft 主颜色目标。
- 32 字节 `CyclesDisplay` uniform 在设置 revision 或远裁剪变化时更新；曝光、Gamma、Raw/Standard 和调试 Pass 映射均在 GPU shader 执行。
- AgX、Khronos PBR Neutral 与 ACES 目前仍使用 Standard 的临时曲线；P11 将由打包的 OCIO GPU processor 替换这一占位行为。

### P8a：渐进采样状态机

- 相机变化后进入 `Interactive`；首个交互帧产生后，在 stationary delay 内报告 `Settling`，到期后进入 `Still` 并使用 still samples/time limit。
- 即使 interactive/still sample 数相同也会完成状态切换，保证静止阶段的时间限制与后续降噪调度仍能生效。
- F10 新增 settling 剩余毫秒数和状态切换计数；native smoke 主动移动相机并验证 `Interactive -> Settling -> Still`。

### P8b：可选动态分辨率

- 输出设置新增动态分辨率开关和交互分辨率百分比；默认关闭，保持现有画质和配置行为。开启后 `Interactive`/`Settling` 使用不高于基础输出百分比的交互比例，`Still` 恢复基础输出尺寸。
- 分辨率切换沿用同一套渐进状态机，新尺寸准备期间 Minecraft 继续展示上一张有效纹理，不主动清屏。两个字段占用设置结构原有保留位，`CyclesBridgeRenderSettings` 仍为 208 字节，ABI 版本保持 v12。
- F10 同时显示基础比例、交互比例与动态开关。Native smoke 在 320×180 视口中验证交互帧切换到 240×135，并在静止延迟后恢复 320×180，防止状态/尺寸振荡回归。

### P9a：类型化 HDR Pass 缓存

- ABI v13 在不改变 Scene/Section/FrameView 和 208 字节设置布局的前提下，增加 `passCacheMegabytes` 及缓存诊断；诊断结构由 240 字节追加到 288 字节。
- Native 使用 `(Pass ID, Raw/Denoised Variant)` 作为缓存键。只有实际降噪的 Combined 标记为 Denoised，Depth 等调试 Pass 始终保持 Raw，避免不同语义的帧互相覆盖。
- 缓存默认预算 256 MiB，使用 LRU 淘汰；只在离开当前 Pass 时复制最近发布帧，不为每个渐进 sample 制造额外整帧 CPU 拷贝。
- 相机、场景和真实渲染参数变化会清空缓存；只切换 Pass 或修改预算会保留缓存。缓存命中只发布显示 generation，不增加 Cycles produced-frame 计数。
- 帧槽记录实际 Pass、Variant 和 camera revision；缓存恢复、清空 generation 或取消边界的迟到帧不能冒充当前请求的新采样。降噪拓扑变化当前按 Session reset 处理，P10 再优化调度成本。
- F10 显示活动 Variant、条目/预算/命中/淘汰和 Raw/Denoised masks。完整数据模型、内存口径和失效矩阵见 [类型化 HDR Pass 缓存](pass-cache.md)。

### P9b：Pass 描述与按需注册表

- ABI v14 新增固定 64 字节的 `CyclesBridgePassDescriptor` 查询；Java/F10 读取 Native 声明的源分量、RGBA16F 显示格式、语义和缓存/色彩管理 flags，不再硬编码推断。
- Native Pass registry 以 Combined 起步，第一次访问其他 Pass 时增长；注册 mask 跨 Session 重建保留，诊断记录注册增长重建和已注册命中。
- Cycles 5.2 的当前 DisplayDriver 路径在同一 Session 原地切换 Film display pass 会停在 `0/1` sample。为保证输出正确，当前每次 Pass 切换仍重建 Session；Pass cache 在等待期间提供旧帧。该限制已写入稳定运行时契约，后续优化不得绕过回归测试。
- Native smoke 查询全部描述符、遍历 7 个 Pass、切回已注册 Combined，并继续覆盖 OptiX、Raw/Denoised cache、Section 增删和动态分辨率。

### P10a：交互/静止降噪调度

- 降噪器选择不再意味着每个渲染请求都实际运行：Interactive Combined 明确关闭 Cycles denoising 并发布 Raw，Settling 沿用该帧，Still Combined 才按配置启用 OptiX/OIDN 并发布 Denoised。
- 调试 Pass 始终保持 Raw；`effective_denoiser` 只报告当前渲染实际启用的降噪器，不把配置选择或设备能力冒充成生效状态。
- Cycles 在 sampling state 切换时更新 Integrator denoise topology；OptiX Smoke 已验证同一 Renderer 中 `Interactive Raw -> Still Denoised`、Combined Denoised/Depth Raw 分键缓存及后续状态机回归。
- 调度契约、OIDN 构建边界和人工验证见 [Cycles 降噪调度](denoising.md)。

### P10b：降噪调度诊断

- ABI v15 在诊断结构尾部追加 selected/effective 后端、是否实际调度、调度原因、有效起始 sample 与累计 run/skip 渲染请求。
- `effective_denoiser` 只表示当前渲染真正执行的后端；Interactive/Settling、调试 Pass 和不可用配置不会再被误报为正在降噪。
- F10 同时显示选择值、实际值、原因和计数，便于区分“设置已选中”“设备支持”和“Still 帧已执行”三类状态。

### P10c：OpenImageDenoise 2.5 构建与部署

- 固定 Cycles 构建改为 `WITH_CYCLES_OPENIMAGEDENOISE=ON`，依赖获取清单纳入本地 `openimagedenoise` 目录；不从系统目录安装或寻找另一份 OIDN。
- Native 链接 OIDN 导入库，只复制主库、core、CPU 和 CUDA 四个 DLL；不部署本阶段不会使用的 HIP/SYCL 插件。
- RTX 5080 native smoke 在同一完整回归中分别验证 OptiX 与 OIDN 的 `Interactive Raw -> Still Denoised`，并继续覆盖 Pass cache、Section 增删和动态分辨率。

### RT-P1：Native 增量场景提交

- `SceneUpdateAccumulator` 在独立 `scene_update` 模块中维护完整 resident Section 真值和未确认 mutation；`cycles_engine.cpp` 只负责把不可变 update 应用到工作线程快照与 Cycles 节点。
- `commit_scene` 不再复制全部常驻 Section 映射。正常提交只复制尚未由 Cycles 工作线程确认的 Section upsert/remove，成本由 `O(resident sections)` 改为 `O(unacknowledged changed sections)`；Java 调用和 C ABI v26 保持不变。
- 每个 Section mutation 带单调 sequence。工作线程确认旧 update 时，只清除 sequence 仍匹配的 pending 项，因此后到的同 Section 更新或删除不会被旧确认误删；多个未处理 commit 会自然合并为最终状态。
- scene reset 和旧整场景 upload 启动新的 epoch，并把下一次发布标记为 full replacement。设备回退、Session 重建或资源变化仍从工作线程完整快照恢复，不依赖已经确认并释放的 mutation。
- Native 独立测试覆盖同 Section 覆盖、删除、晚到确认、跨 epoch 确认和完整替换；公开 ABI 集成测试覆盖 `upsert -> commit -> remove -> commit -> upsert -> commit` 不等待工作线程的快速序列，以及最终删除和 Section 诊断计数。
- 自动验证通过 `buildNative`、`cyclesrenderer_scene_update` CTest 和 Gradle `build`。完整 Native smoke 在本次首次运行通过；后续两次复跑被既有 OptiX/OIDN 异步帧 variant 断言波动阻断，未修改不属于 RT-P1 的降噪逻辑。

游戏内验收使用相同世界、视距和 Section 常驻数，对比优化前后的 F10 `Scene commit last/EMA/max`：单方块修改时 commit 应不再随 resident Section 数明显增长。`Scene delta` 仍包含 Cycles Mesh/BVH 更新，本阶段不会降低该项，也不会改变 Section 捕获或 Java FFM upsert 时间。
