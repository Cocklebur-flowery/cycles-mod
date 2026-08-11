# 类型化 HDR Pass 缓存与按需注册表（P9）

状态：已实现，等待游戏内人工验收  
协议：C ABI v14
默认预算：256 MiB（允许 64–4096 MiB）

## 1. 目标与边界

本子阶段在 Native 端保存最近查看过的 scene-linear RGBA16F Pass，使 Pass 查看器切回旧通道时可以立即恢复上一张有效帧，同时让 Raw 与 Denoised 结果不会互相覆盖。

缓存键为：

```text
(稳定 Pass ID, Frame Variant)

Frame Variant = Raw | Denoised
```

当前 7 个稳定 Pass ID 保持不变：Combined、Depth、Normal、Diffuse Color、Emission、Roughness、Sample Count。只有实际生效降噪且活动 Pass 为 Combined 时，结果才标记为 Denoised；调试 Pass 始终按 Raw 缓存。

本阶段没有实现“所有 Pass 同时常驻 Cycles Render Buffer”。Native 注册表以 Combined 起步，第一次请求其他 Pass 时才把它加入注册 mask；注册表在后续 Session 重建间保持。Java 可查询描述符，不再从显示名称猜测 Pass 的分量数、语义或色彩管理规则。

当前每次切换活动 Pass 都会重建 Cycles Session。自动测试确认，同一 Session 内仅修改 Cycles Film display pass 会让复用路径停在 `0/1` sample，因此 P9b 选择正确性优先的保守路径；“已注册命中”表示注册表无需增长，不表示 Session 已被复用。缓存仍会先恢复旧帧，避免重建期间主动黑屏。原地切换必须在后续获得稳定的 Cycles buffer/display-driver 更新路径后才能启用。

## 2. 内存与更新策略

- 缓存内容是 Native `half4`，每像素 8 字节，不做 RGBA8 量化或显示变换。
- 默认预算 256 MiB，NeoForge 客户端配置键为 `passCacheMegabytes`。
- 使用 LRU 淘汰；同一键的新结果覆盖旧结果。
- 只在离开当前 Pass/Variant 时复制最新发布帧，不在每个渐进 sample 上额外复制整帧。
- 命中后把缓存复制到三槽帧环中的空闲槽并发布新 generation；它是显示更新，不计作 Cycles 新生产的 sample 帧。
- 预算只统计保留的缓存副本，不包含活动三槽帧环、Cycles Render Buffer、OptiX 设备内存或 Minecraft Vulkan 纹理。

典型内存量：1920×1080 RGBA16F 约 15.8 MiB，3840×2160 约 63.3 MiB。默认预算理论上可保留约 16 张 1080p 或 4 张 4K 缓存帧，实际数量取决于分辨率和 LRU 顺序。

## 3. 失效规则

| 变化 | 缓存行为 | 原因 |
| --- | --- | --- |
| 只切换活动 Pass | 保留，并缓存离开的当前帧 | Pass 查看器的主要命中路径 |
| Raw / Denoised Variant 变化 | 分键保存 | 防止降噪结果污染 Raw 调试数据 |
| 只修改缓存预算 | 保留，必要时按 LRU 淘汰 | 不影响像素内容 |
| 相机变化 | 全部失效 | 缓存对应旧视图 |
| 场景/Section 变化 | 全部失效 | 几何或材质已变化 |
| 分辨率、采样、光程、过滤、设备等渲染参数变化 | 全部失效 | 结果语义或尺寸变化 |
| 降噪模式、辅助输入或 GPU 路径变化 | 全部失效并重建 Session | Cycles Film/辅助 Pass 拓扑必须可靠重建 |
| Buffer/Session/Renderer 清空 | 全部失效 | 所属渲染上下文已失效 |

未来 P11 的纯显示变换（曝光、AgX/ACES、显示空间）应在 Pass cache 之后执行，因此不得使 HDR Pass 失效；当前设置分类仍以实际实现为准。

## 4. ABI、描述符与诊断字段

ABI v13 在 `CyclesBridgeDiagnostics` 尾部追加：

- `cached_raw_pass_mask` / `cached_denoised_pass_mask`：两种 Variant 已缓存的稳定 Pass 位掩码。
- `pass_cache_bytes` / `pass_cache_budget_bytes`：当前保留字节和预算。
- `pass_cache_entry_count`：当前 LRU 条目数。
- `pass_cache_eviction_count`：因预算发生的累计淘汰数。
- `pass_cache_hit_count`：成功恢复到帧环的累计命中数。
- `active_frame_variant`：当前展示结果是 Raw 还是 Denoised。

F10 显示活动 Variant、条目数、已用/预算 MiB、命中/淘汰次数，以及 Raw/Denoised mask。mask 用于确认 Combined 降噪结果和 Depth 等 Raw 调试结果是否真正分离。

ABI v14 新增固定 64 字节的只读 `CyclesBridgePassDescriptor` 和 `cycles_bridge_query_pass_descriptor`。描述符包含：

- 稳定 Pass ID、源分量数与显示分量数。
- 像素格式（当前均为 RGBA16F）。
- `color`、`depth`、`normal` 或 `scalar` 语义。
- 可显示、需色彩管理、可产生降噪结果、调试用途及 Raw/Denoised 缓存能力 flags。

`CyclesBridgeDiagnostics` 在 v13 的 288 字节尾部追加注册 mask、注册表增长导致的 Session 重建次数和已注册命中次数，固定为 304 字节。F10 同时显示活动 Pass 的实际 descriptor，便于发现请求 Pass 与实际帧元数据不一致。

## 5. 自动验证

`runNativeSmoke` 执行以下回归：

1. 查询全部 7 个 Pass descriptor，验证稳定 ID、分量、格式、语义和 flags。
2. 依次渲染全部 7 个 Raw Pass，再切回 Combined，验证注册 mask、注册表重建/命中计数、Raw mask、缓存预算和缓存命中。
3. 在 RTX/OptiX 可用时启用 OptiX 降噪，验证 Combined Denoised 与 Depth Raw 同时存在。
4. 验证缓存恢复只增加显示 generation，不被工作线程误判为新的 Cycles 渲染完成。
5. 继续执行 Section 更新/删除和动态分辨率测试，防止缓存破坏既有状态机。

游戏内仍需人工验证：F9/F10 切换 Pass 时旧帧是否立即恢复、场景或相机变化后是否不再展示陈旧缓存、不同分辨率下预算与淘汰计数是否合理。
