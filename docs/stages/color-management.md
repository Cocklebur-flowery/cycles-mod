# OCIO 色彩管理（P11）

状态：P11a 已完成自动验证，P11b 待开始
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
- P11b：增加色彩处理器能力/错误诊断与可复现的 GPU LUT 数据契约。
- P11c：Vulkan presenter 绑定扁平 LUT，使 AgX 和 Khronos PBR Neutral 真正生效。
- P11d：增加 ACES 2.0 SDR 查看变换，并明确 Rec.2020/PQ/HLG 与真 HDR swapchain 的边界。

## 4. 稳定边界

- 现有 View Transform ID `Standard=0`、`Raw=1`、`AgX=2`、`Khronos PBR Neutral=3` 不重新编号；新值只追加。
- Pass ID、RGBA16F 帧租约、F8/F9/F10 和 Minecraft swapchain 所有权保持不变。
- `Raw` 不应用曝光、OCIO 或 Gamma；非颜色调试 Pass 不进入 OCIO。
- 当前输出仍为 sRGB SDR。内部 scene-linear HDR 与 ACES 处理不能被描述为 Windows 真 HDR 输出。

## 5. 验证

- `setup-cycles.ps1` 校验 Blender tag 对应提交和 `config.ocio`。
- 构建目录必须存在 `color/ocio/config.ocio` 及其 LUT；不得依赖 Blender 安装目录。
- P11b 起用 OCIO CPU processor 固定测试点作为基准；P11c 的 GPU 输出以这些点和游戏内高光梯度进行比对。
- 每个代码子阶段执行 `runNativeSmoke`、`build`、diff 检查并独立提交。
