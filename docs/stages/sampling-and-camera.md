# Cycles 采样模式与物理相机（P12）

状态：P12a-P12d 已完成自动验证；待游戏内人工验收
目标平台：Blender Cycles 5.2 / Minecraft 26.2 相机 / OptiX

## 1. 目标与保持不变的默认行为

P12 把 Cycles 已有的采样序列、裁剪和景深能力接入现有 F9 设置与 F10 诊断。Minecraft 仍拥有玩家相机的位置、朝向和默认视场角；不开启物理镜头覆盖时，画面构图必须与原版投影矩阵一致。

本阶段不实现运动模糊、全景/鱼眼、自动对焦射线、色差、镜头畸变或基于实体的焦点跟踪。它们需要额外的游戏状态、运动时间语义或后处理契约，不能伪装成一个孤立滑块。

## 2. 子阶段与提交边界

| 子阶段 | 交付物 | 验证重点 |
| --- | --- | --- |
| P12a | 本文档、参数单位、失效规则与测试矩阵 | 文档和当前实现一致 |
| P12b | Cycles 原生采样模式：Sobol Burley、Tabulated Sobol、Blue Noise Pure/First/Round | ABI、枚举值、实际 Integrator 值、F9/F10 |
| P12c | Minecraft 投影跟随、近/远裁剪设置 | 默认构图不变、裁剪值合法、相机 reset |
| P12d | 物理镜头覆盖与景深：焦距、传感器、焦点距离、f-stop、叶片、旋转、比例 | FOV 换算、光圈半径、关闭景深等价旧行为 |

每个子阶段自动运行适用的 Java 构建、Native smoke、完整构建和 diff 检查，然后立即独立提交。游戏内问题用后续修复提交处理，不 amend。

## 3. 采样模式契约

公开值直接映射 Cycles 5.2 `SamplingPattern`，不得自行实现“伪蓝噪声”：

| ID | 设置值 | Cycles 值 | 用途 |
| ---: | --- | --- | --- |
| 0 | `SOBOL_BURLEY` | `SAMPLING_PATTERN_SOBOL_BURLEY` | 低内存 Sobol-Burley |
| 1 | `TABULATED_SOBOL` | `SAMPLING_PATTERN_TABULATED_SOBOL` | 旧版默认与稳定回归 |
| 2 | `BLUE_NOISE_PURE` | `SAMPLING_PATTERN_BLUE_NOISE_PURE` | 固定目标 sample 的纯蓝噪声序列 |
| 3 | `BLUE_NOISE_FIRST` | `SAMPLING_PATTERN_BLUE_NOISE_FIRST` | 优先改善第一 sample 的视口反馈 |
| 4 | `BLUE_NOISE_ROUND` | `SAMPLING_PATTERN_BLUE_NOISE_ROUND` | 将非二次幂 sample 数向上取整序列 |

`SAMPLING_PATTERN_AUTOMATIC` 明确标记为“不得进入 kernel”，因此本阶段不直接传入该值。设置默认采用 `BLUE_NOISE_FIRST`，同时保留 `TABULATED_SOBOL` 供旧画面回归。Seed 仍交给 Cycles；蓝噪声模式下由 Cycles 官方实现执行 hash/scramble。

修改采样模式或 seed 会使累计 sample 失效，但不得重建 Minecraft Scene、重新捕获 Section 或清空不相关的 Vulkan/OCIO 资源。F10 必须同时显示配置模式与 Native 实际模式，避免只显示 UI 目标值。

## 4. 投影与裁剪契约

- 默认 `MINECRAFT_FOV`：继续从每帧 `projectionMatrix.m11()` 反算垂直 FOV，包含原版 FOV、冲刺、药水和其他合法相机效果。
- 可选 `PHYSICAL_LENS`：用焦距与传感器尺寸计算透视 FOV；该模式明确覆盖 Minecraft FOV，但不改变位置和朝向。
- 裁剪起点单位为方块/米，默认 `0.05`，与当前 Native 常量一致。
- 裁剪终点默认跟随 `CameraRenderState.depthFar`；只有显式启用覆盖时才使用用户值，且最终值必须大于 near clip。
- Shift X/Y 只改变 Cycles view plane，不反向修改 Minecraft HUD、准星或交互射线，因此 P12 不开放它们，避免画面中心与选取中心不一致。

FOV、裁剪、镜头或景深变化属于 Camera/Buffer reset，不属于 Scene reset。它们会重新开始累计采样，但不能触发 Section 重新上传。

## 5. 物理镜头与景深单位

- `focalLengthMm`：焦距，毫米。
- `sensorWidthMm`：横向传感器尺寸，毫米；结合当前渲染宽高比换算垂直 FOV。
- `focusDistanceBlocks`：从相机沿视线方向的焦平面距离，单位方块；项目约定 `1 block = 1 meter`。
- `fStop`：光圈值。传给 Cycles 的 aperture radius 使用官方 Hydra 同一公式：`focalLengthMeters / (2 * fStop)`。
- `apertureBlades`：`0` 表示圆形，`3..16` 表示叶片数；`1` 和 `2` 不合法。
- `apertureRotationDegrees`：UI 使用度，Native/Cycles 使用弧度。
- `apertureRatio`：`1.0` 为圆形，允许用于变形光圈；必须大于零。

景深默认关闭，关闭时 `aperturesize=0`，从而保持当前针孔相机结果。焦距/传感器仅在物理镜头覆盖开启时改变构图；开启景深但继续使用 Minecraft FOV 时，焦距只用于计算物理光圈半径，不覆盖原版 FOV。

## 6. 诊断与测试矩阵

F10 至少报告：

- configured/effective sampling pattern、seed；
- projection source、vertical FOV（度）、near/far；
- DOF enabled、focus distance、f-stop、aperture radius、blades/rotation/ratio；
- Camera revision 与 reset 原因，确认设置变化没有升级为 Scene reset。

Native smoke 依次覆盖：

1. 五种采样模式均可应用并能产生帧，非法 ID 被拒绝。
2. `BLUE_NOISE_FIRST` 与非零 seed 成为 Native 实际模式。
3. 默认 Minecraft FOV + `near=0.05` + camera far 保持旧画面路径。
4. 物理焦距/传感器 FOV 换算为有限值，near/far 顺序正确。
5. 景深关闭时 aperture radius 为零；开启时焦距、f-stop、叶片与比例到达 Cycles Camera。
6. 只改相机参数增加 Camera revision，不增加 Scene revision/Section 上传计数。

游戏内人工验收使用同一固定位置截图，比较原版、默认 Cycles、物理镜头和景深四种状态；同时观察 F10 actual sample 是否在相机稳定后继续累计。

## 7. P12b 实施记录

- ABI v17 将 208 字节设置结构的首个保留槽定义为 `sampling_pattern`，其余布局和总大小不变；诊断结构只在尾部追加 Native 实际模式。
- F9 可选择五种 Cycles 5.2 原生模式并显示 seed；F10 显示 `configured/native`，用于发现配置未生效或枚举映射错误。
- 默认值为 `BLUE_NOISE_FIRST`。模式/seed 变化只触发 accumulation reset；Scene、Section、Pass ID 和 OCIO 资源契约保持不变。
- Native smoke 遍历 ID `0..4`、拒绝 ID `5`，并在实际 OptiX 出帧后断言 Native 诊断仍为 `BLUE_NOISE_FIRST`。

## 8. P12c 实施记录

- ABI v18 将设置结构的两个保留槽定义为 `camera_clip_near` 与 `camera_clip_far`，总大小仍为 208 字节。`camera_clip_far=0` 明确表示跟随 Minecraft 每帧提供的 `depthFar`。
- Near 默认 `0.05 block`，保持此前 Native 常量；显式 far override 与 Minecraft far 都会被约束为至少 `near + 0.001`，避免非法裁剪体导致崩溃。
- Native 诊断追加最终生效的 near/far；F10 同时显示配置值和生效值，因此可以确认 `0 = Minecraft` 实际解析到了多远。
- 裁剪变化通过现有 settings accumulation reset 生效，不触发 Scene reset 或 Section 重传。Native smoke 使用 `0.125/50` 出帧并断言最终值。

## 9. P12d 实施记录

- ABI v19 将物理投影、焦距、传感器宽度、景深、焦点距离、f-stop、光圈叶片/旋转/比例写入 232 字节设置结构；376 字节诊断结构返回 Native 最终使用的垂直 FOV 与光圈参数。
- `PHYSICAL_LENS` 按当前宽高比由横向传感器换算垂直 FOV；`MINECRAFT_FOV` 保持原版投影。固定物理投影和固定 far override 时，Minecraft FOV/depthFar 的无关变化不会误触发累计重置。
- 景深关闭时光圈半径为零；开启时使用 `focalLengthMeters / (2 * fStop)`，并将 UI 角度转换为 Cycles 弧度。
- Native smoke 在实际 OptiX 出帧中验证 18 mm / 36 mm 物理投影、8 block 焦距、f/4、六叶片、15 度与 1.2 光圈比例。
- NeoForge 客户端配置公开投影来源、焦距、传感器、景深、焦距平面、f-stop、圆形/多叶片光圈、旋转和比例。圆形光圈写入 Native 值 `0`，多边形光圈只允许 `3..16`，因此配置文件不会产生非法的 1/2 叶片状态。
- F9 显示投影、焦距/传感器和景深摘要；F10 显示 configured/native 投影、最终垂直 FOV、有效景深、焦点距离与光圈半径，便于区分 UI 目标值和 Cycles 实际值。
