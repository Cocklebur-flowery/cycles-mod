# FP16 Minecraft 主渲染链路

状态：方案 A 的 A0–A4 已实现并通过自动验证；游戏内验证矩阵待执行
基线：Minecraft/NeoForge 26.2.0.58、Vulkan 1.2、Cycles 5.2、Windows scRGB

## 1. 目标

P18 已能协商 `RGBA16_SFLOAT + EXTENDED_SRGB_LINEAR_EXT` 交换链，但 Minecraft
主目标仍是 `RGBA8_UNORM`。因此当前链路只能把已经截断的 SDR 合成结果嵌入
scRGB，不能保留 Cycles 超过 SDR 白点的高光。

方案 A 将 HDR 请求下的 Minecraft 主目标、全屏帧图目标和后处理目标提升为
`RGBA16_FLOAT`，让超过 `1.0` 的显示参考值一直保留到最终 scRGB 转换。SDR
未请求时继续使用原有 RGBA8 路径。

## 2. 冻结的数据流与颜色语义

正式链路为：

```text
Cycles scene-linear RGBA16F
  -> OCIO HDR display transform（Rec.2100-PQ 作为显示变换中间编码）
  -> PQ 解码、Rec.2020 到扩展 sRGB 原色转换、纸白归一化
  -> signed extended-sRGB RGBA16F Minecraft 主目标
  -> Minecraft 全屏后处理、手持物、HUD 与 GUI 合成
  -> signed extended-sRGB 解码
  -> linear scRGB RGBA16F swapchain（1.0 = 80 nits）
```

Minecraft FP16 主目标是**显示参考的 signed extended-sRGB 合成缓冲**，不是
scene-linear 缓冲。这是稳定兼容契约：原版与模组 GUI shader、纹理及混合状态按
sRGB 数值工作；强行把同一目标改成 scene-linear 会要求改写所有原版、资源包和
模组 fragment shader，并改变既有 alpha blending 结果。

signed extended-sRGB 使用奇函数扩展传递函数：

```text
encode(x) = sign(x) * sRGB_OETF(abs(x))
decode(x) = sign(x) * sRGB_EOTF(abs(x))
```

这既允许正向 HDR 高光超过 `1.0`，也允许 scRGB 表示超出 sRGB 三角形的负分量。
最终输出不得在解码前用 `max(value, 0)` 截断负分量。

纸白沿用 P18 的 `cyclesrenderer.hdrPaperWhiteNits`，默认 200 nits。最终 scRGB
缩放为：

```text
scRGB = displayLinearRelativeToPaperWhite * paperWhiteNits / 80
```

HDR view 的目标峰值与纸白是不同参数。第一版 HDR 显示变换以 1000 nits view
为基线；不得把纸白值误当成峰值，也不得把 PQ 编码值直接写入线性 scRGB 交换链。

## 3. 启动与回退契约

`GameRenderer` 在 Minecraft 构造期间创建主目标；`VulkanGpuSurface.configure`
直到首帧才选择实际 surface pair。因此主目标创建时只能知道“是否请求 HDR”，
不能知道 scRGB 是否最终选中。

冻结规则：

1. 仅当 Vulkan 后端活动且早期 HDR 请求开启时，主链路选择 RGBA16F。
2. HDR 请求关闭时，主目标、帧图、后处理和 present 均保持现有 RGBA8 SDR 行为。
3. HDR 已请求但 surface 不支持 scRGB 时，主链路仍保持 RGBA16F；最终 present
   转换到原版 SDR surface，不能在启动后替换 `GameRenderer` 的 final 主目标。
4. surface pair、窗口大小或资源重载只允许重建目标与 pipeline variant，不允许
   改变本次进程的主链路格式模式。切换早期 HDR 请求仍需完整重启。
5. 任何 HDR 子阶段失败都必须回到可显示的 SDR 输出，不得影响 F8 原版世界回退。

## 4. Vulkan pipeline 附件格式算法

Minecraft `RenderPipeline` 把 `ColorTargetState.format` 编译进 Vulkan dynamic
rendering pipeline。原版默认值是 `RGBA8_UNORM`；把目标纹理单独改成 RGBA16F
会违反 `VkPipelineRenderingCreateInfoKHR::pColorAttachmentFormats` 与实际附件必须
一致的 Vulkan 契约。

A1 采用按实际 render pass 附件格式特化的 variant cache：

```text
key = (original RenderPipeline identity, ordered actual color attachment formats)
value = immutable RenderPipeline variant
```

规则：

- 只在 Vulkan HDR FP16 模式且实际附件格式与声明不同时创建 variant；
- 保留 shader、defines、bind-group layouts、vertex bindings、blend/write mask、
  depth/stencil、cull、topology 和 stencil test；
- 只替换非空颜色附件的 `GpuFormat`，空附件保持 `VK_FORMAT_UNDEFINED`；
- SDR RGBA8 pass 返回原 pipeline，不增加缓存或编译成本；
- variant 生命周期跟随 Vulkan pipeline cache，资源重载后允许重新生成；
- 不能通过全局修改 `ColorTargetState.DEFAULT` 实现，否则 RGBA8 离屏目标会失配；
- 不能继续向已接近 800 行的 Vulkan 资源所有者堆入该职责。

## 5. 需要提升的目标

HDR FP16 模式至少覆盖：

- `MainTarget` 颜色附件；
- `LevelRenderer` 的 screen-size transparency targets；
- `PostChain` 内部、持久和全屏中间目标。

实体描边、字体图集、普通纹理和 GUI item atlas 等专用 SDR 资源可以继续使用
RGBA8。它们渲染到 FP16 主目标时，由 A1 根据实际目标选择 pipeline variant；
采样纹理格式不需要跟随颜色附件格式变化。

## 6. 显示变换与合成边界

P18 当前强制使用 sRGB OCIO view，再把 SDR 结果解码到 scRGB。A3 必须改为：

1. scRGB surface 实际选中时，为 Cycles beauty 使用现有 Rec.2100-PQ OCIO
   HDR view；普通 AgX/ACES 选择映射到对应 1000-nit HDR view。
2. shader 将 PQ 解码为绝对显示线性值，再把 Rec.2020 转到扩展 sRGB 原色。
3. 以 paper white 归一化后做 signed extended-sRGB 编码，写入 FP16 主目标。
4. GUI 白色 `1.0` 继续代表 paper white；HDR 高光可超过 `1.0`。
5. 最终输出 shader 做 signed 解码和 `paperWhite / 80` 缩放，写入线性 scRGB。
6. 非 beauty 调试 Pass 保持其既有可视化语义，不将深度、法线或 ID 数据误当 PQ。

资源包后处理若主动 clamp 到 `[0, 1]`，仍会自行丢失高光。格式提升只能防止存储
截断，不能纠正第三方 shader 内部的显式 clamp；该情况必须在诊断中可区分。

## 7. PNG 截图与捕获

原版 `Screenshot.takeScreenshot` 按纹理 `blockSize` 分配 buffer，却始终用
`getInt` 把每个像素解释为 RGBA8。直接读取 RGBA16F 会把两个 half-float 通道的
字节误认为完整像素。

A4 已实现以下策略：

- 普通 Minecraft 截图仍输出兼容的 SDR PNG；
- 捕获前用 GPU shader 把 signed extended-sRGB FP16 主目标转换为 RGBA8 sRGB；
- UI/paper white 应保持原亮度，超过 SDR 范围的高光使用明确、可测试的肩部压缩；
- 自动世界截图、普通 F2 截图和 downscale 路径都必须经过同一转换；
- Tracy 的 RGBA8 捕获目标继续保留，但其 blit pipeline 必须与实际附件格式匹配；
- HDR EXR/JXL 截图不在本阶段内，不能把 SDR PNG 宣称为 HDR 文件。

屏幕 SDR 回退和截图共用 `sdr_output.fsh`。对 SDR 色域内、所有分量均未超过
paper white 的非负像素，传递函数为恒等映射；仅当线性峰值超过 `1.0` 时，按
`mappedPeak = peak / (peak + 0.05)` 等比缩放 RGB，保留高光色相并避免逐通道硬裁剪。
截图转换发生在原版 GPU readback 和 downscale 之前，因此 F2、自动世界截图与
非 1 倍 downscale 不会解释 RGBA16F 原始字节。

## 8. 性能预算

RGBA8 为 4 bytes/pixel，RGBA16F 为 8 bytes/pixel。每个全屏目标的额外显存约为：

| 分辨率 | 单目标增量 |
| --- | ---: |
| 1920×1080 | 7.9 MiB |
| 2560×1440 | 14.1 MiB |
| 3840×2160 | 31.6 MiB |

主目标加五个透明帧图目标在 4K 下理论增量约 190 MiB，后处理持久目标另计。
颜色附件读写带宽也近似翻倍。诊断必须记录 FP16 模式、目标尺寸、pipeline variant
数量和最终转换耗时，不能只观察交换链格式。

## 9. 分阶段所有权与提交

| 阶段 | 内容 | 独立提交 |
| --- | --- | --- |
| A0 | 本设计、回退与验证矩阵 | `f78eb0d docs(hdr): define FP16 main-target contract` |
| A1 | Vulkan render-pass 实际附件格式特化 | `b1dd84c feat(hdr): specialize Vulkan pipelines for FP16 targets` |
| A2 | 主目标、Level framegraph、PostChain RGBA16F | `88a12b9 feat(hdr): promote Minecraft color targets to FP16` |
| A3 | OCIO HDR view、PQ/scRGB/extended-sRGB 显示变换 | `9c4df29 feat(hdr): preserve HDR through Minecraft composition` |
| A4 | SDR fallback、PNG capture、遥测与文档收口 | `fix(hdr): complete SDR fallback and capture paths` |

每个新 Java 类保持单一职责并低于 500 行；不向超过 800 行的现有类追加新职责。
`CyclesFramePresenter` 已超过 500 行，A3 应抽出 HDR 显示策略而不是继续堆逻辑。

## 10. 验证矩阵与停止条件

自动验证：

- `compileJava`、Java tests、Mixin JSON；
- HDR policy、pipeline variant key/复制语义、传递函数边界和截图 tone-map 静态端点校验；
- staged diff 不包含 native ABI、DLSS 或性能线程所有权文件。

2026-08-14 收口结果：

- `compileJava --rerun-tasks`、`test` 与 `jar` 通过；
- `javap` 确认三个 FP16 目标构造器注入点和截图纹理替换点均唯一命中；
- Mixin JSON 可解析，构建 JAR 包含全部新 mixin、策略类和 shader；
- `CyclesDisplay` 的七个 std140 槽与 112-byte Java uniform buffer 一致；
- PQ 0/80/100/200/1000/10000-nit 端点往返与 scRGB `nits / 80` 标度通过；
- SDR 压缩在 `peak > 1` 区间单调且有界，SDR 恒等区保持不变；
- 未修改 native ABI 或 DLSS，DLSS 仍允许使用且未被强制回退。

游戏内验证：

1. HDR 请求关闭：主目标 RGBA8，P18 转换不活动，SDR 行为与基线一致。
2. HDR 请求开启且 scRGB 可用：主目标和全屏中间目标 RGBA16F，surface 为
   `RGBA16_SFLOAT/extended-sRGB-linear`，Vulkan validation 无 attachment-format VUID。
3. HDR 请求开启但 scRGB 不可用：主目标 RGBA16F，最终 SDR 正常显示且无双重 gamma。
4. 在最终转换前证明主目标存在大于 `1.0` 的 beauty 高光；不能只以交换链格式判定。
5. 验证 GUI、菜单 blur、手持物、实体描边、窗口缩放、全屏、Alt-Tab、资源重载。
6. 验证普通截图、自动世界截图、Tracy capture 与 downscale。
7. 分别验证 CPU upload 和 Vulkan/CUDA interop；DLSS 保持可用且不被强制回退。

出现以下情况必须停止当前子阶段：

- pipeline 声明格式与实际附件仍不一致；
- HDR 请求关闭时创建了 FP16 主目标或额外转换资源；
- scRGB 不可用导致黑屏，而不是回退 SDR；
- GUI 白点、alpha blending 或资源包 pipeline 无法保持可解释语义；
- 截图读取 RGBA16F 原始字节或产生损坏 PNG；
- 需要修改 native ABI、DLSS 或其他线程所有权文件才能继续。
