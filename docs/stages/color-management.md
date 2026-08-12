# OCIO 色彩管理（P11）

状态：P11a-P11d、P17b1-P17c 已实现；P17c 等待游戏内工作空间切换验收
目标平台：Blender 5.2.0 / OpenColorIO 2.5 / Minecraft Vulkan sRGB SDR swapchain

## 1. 固定来源

色彩资产来自 Blender `v5.2.0` 的官方仓库提交 `fbe6228777e7d9afefcd61a413844e790ae75db7`，路径为 `release/datafiles/colormanagement`。`setup-cycles.ps1` 使用 partial clone 与 sparse checkout，只取得该目录，并将其复制到 `.deps/cycles-install/color/ocio`；Native 构建再部署到 `build/native/bin/color/ocio`。

构建和运行时不读取用户安装的 Blender，不读取全局 `OCIO` 环境变量，也不从系统目录选择另一份配置。这样 AgX、Khronos PBR Neutral 和 ACES 2.0 的定义与当前 Cycles 5.2 基线保持一致。

## 2. 显示数据流

```text
Minecraft 纹理/tint（解码为 Linear Rec.709）
  -> 选定的 Cycles scene working space
  -> Cycles scene-linear RGBA16F
  -> 白平衡（scene-linear，使用对应工作空间矩阵）
  -> EV（scene-linear）
  -> Blender 5.2 OCIO display/view processor
  -> 用户 Gamma（display-referred 调整）
  -> Minecraft sRGB SDR 主颜色目标
```

View Transform、Look、EV、Gamma 和白平衡属于显示端，不改变 Pass cache 中的 scene-linear 数据。工作空间属于场景契约；切换工作空间必须重建 Cycles Session，使着色、顶点 tint、白平衡和 OCIO 输入 role 同时迁移，不能只给设置项换标签。

Minecraft 26.2 的图形封装目前不开放 3D 纹理，因此 P11 的 GPU LUT 使用扁平 2D 存储并在 shader 中恢复三维索引。LUT 必须由官方 OCIO processor 生成，不手写 AgX 或 ACES 近似曲线。

## 3. 子阶段

- P11a：固定 Blender 5.2 OCIO 资产来源、稀疏获取和运行时部署（已完成）。
- P11b：增加色彩处理器能力/错误诊断与可复现的 GPU LUT 数据契约（已完成）。
- P11c：Vulkan presenter 绑定扁平 LUT，使 AgX 和 Khronos PBR Neutral 真正生效（已实现，等待游戏内验收）。
- P11d：增加 ACES 2.0 SDR 查看变换，并明确 Rec.2020/PQ/HLG 与真 HDR swapchain 的边界（已完成自动验证，等待游戏内验收）。
- P17b1：把 Blender 5.2 OCIO 配置中的官方 AgX Looks 接入显示管线；默认使用 `AgX - Punchy`，并允许在 F9 独立切换（已完成原生构建与 LUT 基准验证，等待游戏内验收）。
- P17b2：加入 Blender 风格白平衡；默认关闭，开启后提供色温和色调参数，并在 scene-linear 中执行（已完成）。
- P17c：加入真实 `Linear Rec.709`、`Linear Rec.2020`、`ACEScg` 场景工作空间，统一 Cycles Session、顶点 tint、白平衡和 OCIO LUT 输入（已完成原生验证，等待游戏内验收）。

## 4. 稳定边界

- 现有 View Transform ID `Standard=0`、`Raw=1`、`AgX=2`、`Khronos PBR Neutral=3` 不重新编号；新值只追加。
- Pass ID、RGBA16F 帧租约、F8/F9/F10 和 Minecraft swapchain 所有权保持不变。
- `Raw` 不应用曝光、OCIO 或 Gamma；非颜色调试 Pass 不进入 OCIO。
- 当前输出仍为 sRGB SDR。内部 scene-linear HDR 与 ACES 处理不能被描述为 Windows 真 HDR 输出。
- Working Space ID 固定为 `Linear Rec.709=0`、`Linear Rec.2020=1`、`ACEScg=2`；只允许在末尾追加，不能重新编号。

## 5. P17c 工作空间与输出边界

- F9 可选择 `Linear Rec.709`、`Linear Rec.2020` 或 `ACEScg`。Native 从固定 Blender 5.2 配置取得对应颜色空间，并在建立 Cycles Session 前把 OCIO `scene_linear` role 指向所选空间。
- Minecraft atlas 与顶点 tint 仍以 sRGB/Linear Rec.709 为来源；P17c 在进入 Cycles 场景时使用 Cycles RGB/XYZ 矩阵转换到目标工作空间。白平衡也使用所选空间的 RGB/XYZ 矩阵，避免把 Rec.709 系数误用于 ACEScg。
- OCIO LUT 缓存键为 `Working Space + View Transform + Look`，描述符携带实际 Working Space ID。除 Raw 外，Standard 也使用官方 OCIO processor，因而 Wide Gamut Standard 不会被误当作 Rec.709 直接编码。
- 切换工作空间触发 Session reset；切换 View Transform、Look、EV、Gamma 或白平衡仍只是显示端更新。F9 路径和 F10 固定能力区都显示当前工作空间。
- `ACES 2.0` 是追加的 View Transform ID `4`。它通过与 AgX 相同的官方 OCIO LUT 路径执行色调映射，最终仍写入 Minecraft 的 `sRGB SDR` 主颜色目标。
- “ACES 2.0 查看变换”不等于“Windows HDR 输出”。当前没有 HDR swapchain、HDR 显示元数据或 PQ/HLG 传递函数，F9/F10 会明确显示 `HDR swapchain inactive/false`。
- `Linear Rec.2020` 场景工作空间不等于 Rec.2020 显示输出。PQ、HLG 和 Rec.2020 swapchain 仍只能在 P13 完成操作系统显示协商、交换链格式和传递函数验证后开放。
- 现有 ID `0..3` 保持不变，配置中新增枚举只追加到末尾；旧配置继续按原枚举名称解析。

## 6. 验证

- `setup-cycles.ps1` 校验 Blender tag 对应提交和 `config.ocio`。
- 构建目录必须存在 `color/ocio/config.ocio` 及其 LUT；不得依赖 Blender 安装目录。
- P11b 起用 OCIO CPU processor 固定测试点作为基准；P11c 的 GPU 输出以这些点和游戏内高光梯度进行比对。
- P17c 已通过 ABI/LUT diff 检查和 Native Release 编译。完整 smoke 已通过 P17c 的 ABI、Rec.2020/ACEScg LUT 与渲染路径，随后在既有动态分辨率断言处失败（期望 `240x135`、实际 `0x0`），因此不能把整个 smoke 记为通过。
- JDK 25 已可用；Gradle 离线编译在解析 NeoForge/Minecraft、Guava、Gson 等未缓存依赖前失败，尚未执行到 Java 编译。游戏内切换与 Vulkan shader 验收仍需客户端手测。

## 7. P11b 原生 LUT 契约

- ABI v16 新增 `CyclesBridgeColorLutDescriptor` 与 `cycles_bridge_query_color_lut`。P17c 将 ABI 升至 v31，在不改变结构体字节大小的前提下，把设置与 LUT 描述符的保留槽定义为 Working Space ID，并把 LUT 查询签名扩展为 `View + Look + Working Space`。第一次只查询描述符，第二次由调用方提供缓冲区；缓冲区过小时明确返回 `BUFFER_TOO_SMALL`。
- LUT 固定为 `64 x 64 x 64`、RGBA32F，二维尺寸为 `4096 x 64`。红色轴在每个蓝色切片内连续，绿色轴映射到二维 Y，shader 在 P11c 中执行三线性插值。4096 宽度不超过 Vulkan 对二维纹理尺寸的最低保证。
- scene-linear 输入使用固定 log2 shaper：范围 `[-10, 16]`，epsilon 为 `2^-10`。这覆盖零到约 65536 的 HDR 通道值；负值在显示端钳制为零，不会改变 scene-linear 帧缓存。
- 原生能力结构保留 64 字节大小，并把原保留槽定义为色彩变换掩码、LUT 边长、像素格式和配置状态。`cycles_bridge_write_color_management_info` 返回实际配置路径、状态、显示设备、边长和错误原因。
- LUT 按工作空间、视图和 Look 惰性生成并缓存。Standard、AgX、Khronos PBR Neutral 与 ACES 2.0 都调用固定 Blender 配置中的官方 OCIO CPU processor，不使用手写近似曲线；Raw 明确旁路 LUT。
- P17b1 将缓存键扩展为 `View Transform + Look`。Look 只对 AgX 生效；非 AgX 查看变换会强制使用 `None`，避免把配置中仅为 AgX 定义的 Look 错套到其他处理器。Look 位于 scene-linear 输入与 display/view processor 之间，仍由同一份 Blender 5.2 OCIO 配置执行。
- Java FFM 已同步 ABI v31，可取得能力掩码、配置状态、原生配置说明和只读 RGBA32F LUT。F10 显示当前 Working Space、View Transform 支持状态、OCIO 状态、LUT 规格和实际配置路径。

## 8. P11c Vulkan 显示绑定

- Presenter 在第一次选择非 Raw 查看路径时，通过 FFM 取得对应 RGBA32F LUT，创建 `4096 x 64` 的 Vulkan sampled texture 并上传一次。Raw 使用 1 x 1 占位纹理并旁路 OCIO。
- Shader 先在 scene-linear 中应用 EV，再使用描述符中的 log2 shaper；扁平纹理通过 8 次 `texelFetch` 恢复三线性 3D 插值。OCIO 输出之后才应用用户 Gamma。
- LUT 只在 View Transform 第一次选择或切换到另一高级变换时构建和上传；相机移动、新 sample、场景更新和 Pass cache 不会重新上传 LUT，也不会因显示变换清空 Cycles 累积。
- F10 增加 LUT 构建加上传的 last/EMA/max、次数、当前 View ID 和累计 MiB。该耗时是切换色彩变换的一次性成本，不参与逐帧热点判断。
- 自动验证覆盖 Java 编译、资源打包、Native OCIO 基准与完整构建。当前环境没有独立 GLSL 编译器，因此 pipeline layout、shader 编译及游戏内高光梯度仍需下一次客户端启动验收。
