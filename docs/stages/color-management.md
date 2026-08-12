# OCIO 色彩管理（P11）

状态：P11a-P11d 已完成自动验证；P11c/P11d 等待游戏内 shader 与画面验收
目标平台：Blender 5.2.0 / OpenColorIO 2.5 / Minecraft Vulkan sRGB SDR swapchain

## 1. 固定来源

色彩资产来自 Blender `v5.2.0` 的官方仓库提交 `fbe6228777e7d9afefcd61a413844e790ae75db7`，路径为 `release/datafiles/colormanagement`。`setup-cycles.ps1` 使用 partial clone 与 sparse checkout，只取得该目录，并将其复制到 `.deps/cycles-install/color/ocio`；Native 构建再部署到 `build/native/bin/color/ocio`。

构建和运行时不读取用户安装的 Blender，不读取全局 `OCIO` 环境变量，也不从系统目录选择另一份配置。这样 AgX、Khronos PBR Neutral 和 ACES 2.0 的定义与当前 Cycles 5.2 基线保持一致。

## 2. 显示数据流

```text
Cycles scene-linear RGBA16F
  -> EV（scene-linear）
  -> Blender 5.2 OCIO display/view processor
  -> 用户 Gamma（display-referred 调整）
  -> Minecraft sRGB SDR 主颜色目标
```

色彩变换只属于显示端。切换 View Transform、显示空间或 Gamma 不得清空 Cycles 累计采样、重建 Scene 或改变 Pass cache 中的 scene-linear 数据。

Minecraft 26.2 的图形封装目前不开放 3D 纹理，因此 P11 的 GPU LUT 使用扁平 2D 存储并在 shader 中恢复三维索引。LUT 必须由官方 OCIO processor 生成，不手写 AgX 或 ACES 近似曲线。

## 3. 子阶段

- P11a：固定 Blender 5.2 OCIO 资产来源、稀疏获取和运行时部署（已完成）。
- P11b：增加色彩处理器能力/错误诊断与可复现的 GPU LUT 数据契约（已完成）。
- P11c：Vulkan presenter 绑定扁平 LUT，使 AgX 和 Khronos PBR Neutral 真正生效（已实现，等待游戏内验收）。
- P11d：增加 ACES 2.0 SDR 查看变换，并明确 Rec.2020/PQ/HLG 与真 HDR swapchain 的边界（已完成自动验证，等待游戏内验收）。
- P17b1：把 Blender 5.2 OCIO 配置中的官方 AgX Looks 接入显示管线；默认使用 `AgX - Punchy`，并允许在 F9 独立切换（已完成原生构建与 LUT 基准验证，等待游戏内验收）。

## 4. 稳定边界

- 现有 View Transform ID `Standard=0`、`Raw=1`、`AgX=2`、`Khronos PBR Neutral=3` 不重新编号；新值只追加。
- Pass ID、RGBA16F 帧租约、F8/F9/F10 和 Minecraft swapchain 所有权保持不变。
- `Raw` 不应用曝光、OCIO 或 Gamma；非颜色调试 Pass 不进入 OCIO。
- 当前输出仍为 sRGB SDR。内部 scene-linear HDR 与 ACES 处理不能被描述为 Windows 真 HDR 输出。

## 5. P11d 工作空间与输出边界

- 当前固定 Blender 5.2 配置以 `scene_linear` role 接收 Cycles 帧；该配置对应的工作数据在本阶段明确标记为 `Linear Rec.709`。这不是 ACEScg，也不是 Linear Rec.2020。
- `ACES 2.0` 是追加的 View Transform ID `4`。它通过与 AgX 相同的官方 OCIO LUT 路径执行色调映射，最终仍写入 Minecraft 的 `sRGB SDR` 主颜色目标。
- “ACES 2.0 查看变换”不等于“Windows HDR 输出”。当前没有 HDR swapchain、HDR 显示元数据或 PQ/HLG 传递函数，F9/F10 会明确显示 `HDR swapchain inactive/false`。
- Rec.2020、PQ 与 HLG 只在后续 P13 完成 HDR swapchain、操作系统显示协商、交换链格式和传递函数验证后开放；在此之前不提供会产生虚假能力印象的工作空间或输出下拉项。
- 现有 ID `0..3` 保持不变，配置中新增枚举只追加到末尾；旧配置继续按原枚举名称解析。

## 6. 验证

- `setup-cycles.ps1` 校验 Blender tag 对应提交和 `config.ocio`。
- 构建目录必须存在 `color/ocio/config.ocio` 及其 LUT；不得依赖 Blender 安装目录。
- P11b 起用 OCIO CPU processor 固定测试点作为基准；P11c 的 GPU 输出以这些点和游戏内高光梯度进行比对。
- 每个代码子阶段执行 `runNativeSmoke`、`build`、diff 检查并独立提交。

## 7. P11b 原生 LUT 契约

- ABI v16 新增 `CyclesBridgeColorLutDescriptor` 与 `cycles_bridge_query_color_lut`。第一次只查询描述符，第二次由调用方提供缓冲区；缓冲区过小时明确返回 `BUFFER_TOO_SMALL`。
- LUT 固定为 `64 x 64 x 64`、RGBA32F，二维尺寸为 `4096 x 64`。红色轴在每个蓝色切片内连续，绿色轴映射到二维 Y，shader 在 P11c 中执行三线性插值。4096 宽度不超过 Vulkan 对二维纹理尺寸的最低保证。
- scene-linear 输入使用固定 log2 shaper：范围 `[-10, 16]`，epsilon 为 `2^-10`。这覆盖零到约 65536 的 HDR 通道值；负值在显示端钳制为零，不会改变 scene-linear 帧缓存。
- 原生能力结构保留 64 字节大小，并把原保留槽定义为色彩变换掩码、LUT 边长、像素格式和配置状态。`cycles_bridge_write_color_management_info` 返回实际配置路径、状态、显示设备、边长和错误原因。
- LUT 按视图惰性生成并缓存。AgX、Khronos PBR Neutral 与 ACES 2.0 都调用固定 Blender 配置中的官方 OCIO CPU processor，不使用手写近似曲线。
- P17b1 将缓存键扩展为 `View Transform + Look`。Look 只对 AgX 生效；非 AgX 查看变换会强制使用 `None`，避免把配置中仅为 AgX 定义的 Look 错套到其他处理器。Look 位于 scene-linear 输入与 display/view processor 之间，仍由同一份 Blender 5.2 OCIO 配置执行。
- Java FFM 已同步 ABI v16，可取得能力掩码、配置状态、原生配置说明和只读 RGBA32F LUT。F10 显示当前 View Transform 是否受支持、OCIO 状态、LUT 规格和实际配置路径。

## 8. P11c Vulkan 显示绑定

- Presenter 在第一次选择 AgX、Khronos PBR Neutral 或 ACES 2.0 时，通过 FFM 取得对应 RGBA32F LUT，创建 `4096 x 64` 的 Vulkan sampled texture 并上传一次。Standard/Raw 使用 1 x 1 占位纹理，避免不需要 OCIO 时生成约 4 MiB LUT。
- Shader 先在 scene-linear 中应用 EV，再使用描述符中的 log2 shaper；扁平纹理通过 8 次 `texelFetch` 恢复三线性 3D 插值。OCIO 输出之后才应用用户 Gamma。
- LUT 只在 View Transform 第一次选择或切换到另一高级变换时构建和上传；相机移动、新 sample、场景更新和 Pass cache 不会重新上传 LUT，也不会因显示变换清空 Cycles 累积。
- F10 增加 LUT 构建加上传的 last/EMA/max、次数、当前 View ID 和累计 MiB。该耗时是切换色彩变换的一次性成本，不参与逐帧热点判断。
- 自动验证覆盖 Java 编译、资源打包、Native OCIO 基准与完整构建。当前环境没有独立 GLSL 编译器，因此 pipeline layout、shader 编译及游戏内高光梯度仍需下一次客户端启动验收。
