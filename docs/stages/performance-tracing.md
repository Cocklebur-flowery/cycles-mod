# 实时性能卡顿追踪（PERF-P1.1）

状态：已实现，待游戏内数据验收
适用范围：Minecraft 26.2、NeoForge 26.2.0.58、Cycles 实时渲染路径

## 1. 目的

F10 的 last/EMA/max 适合观察趋势，但不能回答某一次卡顿前后发生了什么。PERF-P1
增加低开销事件追踪器，用同一个 Minecraft frame ID 关联主线程、后台 Section、Native/Cycles
诊断和 Vulkan 队列时间。

检测器仅在 F8 Cycles 实时渲染启用期间工作。正常帧保存在固定内存环形缓冲中，不进行逐帧
字符串格式化或磁盘写入；达到卡顿阈值后，才把触发前后的快照交给守护线程写入 JSON Lines。

## 2. 捕获策略

- 固定保存最近 512 帧，不随运行时间增长。
- 绝对阈值：任一关键 CPU 帧段达到 20 ms。
- 自适应阈值：至少有 16 个普通样本后，达到近期中位数的 2 倍且不少于 12 ms。
- Flip 间隔使用独立中位数，只在达到自身常态的 2 倍时触发；因此正常 30/60 FPS 不会被误报。
- 每次事件默认保存触发前 120 帧和触发后 30 帧；连续卡顿会合并，单个窗口最多 300 帧。
- 后置窗口结束后，最多再等待 64 帧让非阻塞 GPU 查询返回；结果齐全会立即写出，超时也不会
  阻塞或等待 GPU fence。每帧的 `gpu_expected/gpu_complete` 可区分“禁用查询”和“结果未返回”。
- Section/Native 大诊断每 15 帧采样一次，触发帧强制采样；日志中的 `context_frame`
  指明上下文实际采样帧。
- 写入队列有界。磁盘过慢时丢弃整个旧捕获窗口并报告计数，不反压渲染线程。

日志位置：

```text
run/logs/cyclesrenderer-performance-YYYYMMDD-HHmmss-SSS.jsonl
```

只有捕获到卡顿事件时才创建文件。F8 关闭或游戏退出时，尚未达到后置帧数的事件也会被写出。

## 3. CPU 字段

| JSON 字段 | 含义 |
| --- | --- |
| `engine_frame` | Minecraft 从 update 前到最终屏幕 blit 入队后的 CPU 时间，不含最终 submit/present |
| `engine_outside_render` | `engine_frame - render_event`，用于暴露 update/extract/最终 blit 等渲染事件外开销 |
| `render_event` | NeoForge RenderFrame Pre 到 Post，即 `GameRenderer.render` |
| `render_outside_cycles` | `render_event - cycles_callback`，用于判断卡顿是否来自原版/其他 Mod 渲染 |
| `submit_present` | RenderFrame Post 到 FlipFrame，包括最终 blit、Vulkan submit 和 surface present |
| `cycles_callback` | Cycles Mod 的 AfterLevel 回调总时间 |
| `scene_update` | Section 筛选、FFI upsert/remove/commit |
| `camera_ffi` | 相机更新桥调用 |
| `interop_poll` | 外部缓冲完成检查、帧获取及 copy 命令提交 |
| `frame_acquire` | 兼容路径 Native frame acquire |
| `frame_upload` | 兼容路径 Java 到 Vulkan 纹理上传命令记录 |
| `display_submit` | Cycles 全屏显示 Pass 的 CPU 命令记录 |
| `display_color_lut` | 颜色 LUT 选择、首次构建与上传 |
| `display_uniforms` | 显示 uniform 缓存检查、构建与写入 |
| `display_render_pass` | 显示 render pass 创建、绑定、draw 与关闭 |
| `gpu_query_frame` | 查询池初始化/结果轮询/槽位选择及帧首尾 timestamp 写入的 CPU 总开销 |
| `gpu_marker_cycles` | Cycles 窗口 timestamp 写入的 CPU 自耗时 |
| `gpu_marker_interop` | 互操作窗口 timestamp 写入的 CPU 自耗时 |
| `gpu_marker_display` | 显示窗口 timestamp 写入的 CPU 自耗时 |
| `diagnostics` | 低频上下文快照本身的 CPU 开销 |

`flip_interval_us` 单独记录相邻 FlipFrame 间隔，可显示用户观察到的画面停顿，但它可能包含
VSync、surface acquire 或帧率限制等待，因此不直接作为 CPU 阶段归因。日志同时给出
`flip_baseline_median_us`；`flip_adaptive` 触发但 CPU/GPU 各段都低时，优先调查 acquire、帧限制、
操作系统调度或其他进程争用。

每帧还记录 `gc_count_delta`、`gc_time_ms_delta` 和 `heap_used_bytes`。如果整帧很高而 Cycles
各段都低，先检查这一帧是否发生 JVM GC；未发生 GC 时再调查 Minecraft update/extract、驱动或
其他线程争用。

`client_tick_id/client_tick_us` 记录最近一次完整 Minecraft ClientTick 的 CPU 时间。多个渲染帧可能
引用同一个 tick ID，这是 20 TPS tick 与显示帧率不同造成的正常现象。方块破坏/放置时如果该值
升高而 `scene_update` 不高，热点位于 Minecraft tick、区块 rebuild 调度或其他 tick 监听器中。

## 4. GPU 字段与边界

GPU 字段来自 Minecraft Vulkan `GpuQueryPool`，使用 availability bit 非阻塞轮询。检测器不会调用
`getTimestampNow()`，也不会为读取结果提交 command buffer 或等待 fence。

| JSON 字段 | 含义 |
| --- | --- |
| `vulkan_render` | RenderFrame Pre/Post 之间 Vulkan 队列工作窗口 |
| `cycles_window` | Cycles AfterLevel 回调所记录的 Vulkan 队列窗口 |
| `interop_window` | 外部 CUDA/Vulkan buffer copy 前后的 Vulkan 队列窗口 |
| `display_pass` | Cycles 显示 Pass 前后的 Vulkan 队列窗口 |

查询结果通常延迟数帧可用。PERF-P1.1 会延迟捕获写出，直到窗口内查询齐全或经过 64 帧宽限，
因此触发帧的 GPU 数据通常不再因写出过早而是 `null`。查询池拥塞时仍会丢弃本次 GPU 采样，
不覆盖尚未完成的槽位；这种帧会显示 `gpu_expected=false`。

这里的数值是两个 timestamp 在 Vulkan queue 时间线上的间隔，不是着色器单元的纯 busy time。
特别是 Interop 主动拆分 submission 时，窗口会包含 semaphore/队列等待；这正适合发现互操作导致的
帧延迟，但不能直接解释为 GPU 核心满载。

这些字段只表示 Vulkan graphics queue。CUDA/OptiX 是另一条 GPU 队列，PERF-P1 通过
Native scene revision、frame generation、reset/device/geometry/BVH wall-time 和互操作等待进行
关联，不把这些 wall-time 标成纯 GPU kernel 时间。精确 OptiX kernel 时间需要 CUDA event 或
CUPTI，是独立的 PERF-P2 工作。

为了确认 timestamp query 本身是否触发 Vulkan command buffer/驱动抖动，可进行 A/B 测试：

```text
-Dcyclesrenderer.performance.gpuQueries=false
```

也可在启动环境中设置 `CYCLESRENDERER_PERF_GPU_QUERIES=false`。默认值为 `true`；系统属性优先于
环境变量。关闭后 CPU、GC、上下文和 JSON 捕获仍正常工作，metadata 的
`gpu_queries_enabled=false`，所有帧的 `gpu_expected=false`。

## 5. 日志读取

文件第一行是 `metadata`，随后每个卡顿窗口包含一行 `capture` 和多行 `frame`。建议先找到
`trigger != "none"` 的帧，再比较：

1. `engine_frame`、`render_event` 与 `submit_present`，判断 CPU 卡在更新/渲染还是提交呈现。
2. `cycles_callback` 与其中的 `scene_update`、`camera_ffi`、`interop_poll`、`frame_upload`。
3. 同一帧的 Vulkan GPU 时间；GPU 时间不高但 CPU 高，优先看 CPU/锁/FFI。
4. `context` 的 revision、Section pending、`scene_timing_revision`、scene queue、reset wait、
   device/geometry/BVH；revision 相同才表示这些 Native timing 属于同一次场景更新。
5. 前 120 帧中队列和 revision 是否持续累积，以区分偶发重建和“越来越卡”的追赶问题。

所有 `*_us` 使用微秒；不可用的阶段写为 JSON `null`，不会用零伪装未采样结果。

## 6. 保持不变的契约

- 不修改 Native ABI、Section/Vertex/Pass 数据布局和 Cycles 固定源码。
- 不修改 F8/F9/F10 行为、原版回退、DLSS、颜色管理或打包流程。
- 不新增依赖，不启用同步 GPU 查询，不在渲染线程写性能日志。
