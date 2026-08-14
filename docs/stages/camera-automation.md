# 相机自动控制：自动曝光与自动对焦

状态：核心链路已实现，等待游戏内视觉验收

记录日期：2026-08-14
对应阶段提交：`26ea4e8`、`31b5762`、`a0b3ce2`、`96d95d8`、`b0bc28d`、`dd04c7d`

## 1. 目标与边界

本阶段为 Minecraft 实时 Cycles 输出提供两套互相独立的相机自动控制：

- 自动曝光（AE）只调节最终显示变换前的 EV，不改灯光、材质、Cycles 积累或世界状态。
- 自动对焦（AF）从 Minecraft 碰撞场景测距，并把每帧有效焦距传给 Cycles 景深相机。
- 两者都支持锁定、时间平滑、死区、变化速率限制和可检索的运行时诊断。
- DLSS 继续允许使用。AF 焦距真实变化时只请求重置 DLSS 时序历史，不禁用 DLSS，也不切换降噪器。

当前明确不在本阶段范围内：

- 眼睛适应的局部曝光、分区 tone mapping 或逐像素曝光。
- 实体语义识别、人脸/生物优先、运动预测与遮挡后的目标跟踪。
- 通过 Cycles 深度 pass 回读对焦。当前使用 Minecraft 同帧方块碰撞场景，避免额外 GPU 回读延迟。
- 自动光圈、自动快门和运动模糊联动。当前只求解显示 EV 与焦距。

## 2. 数据流与职责

```text
Cycles Combined RGBA16F
  -> GPU 64x36 测光降采样
  -> 三槽异步 readback
  -> 工作色域亮度 + 256 桶 log2 直方图
  -> AE 目标值/时间控制器
  -> display shader 的 exposure EV

Minecraft CameraRenderState + 当前相机投影
  -> 屏幕中心或九点区域采样
  -> 投影逆映射（透视或七种全景）
  -> Minecraft block/fluid raycast
  -> 距离簇选择 + 加权中位数
  -> AF 时间控制器
  -> ABI 38 camera.focus_distance/flags
  -> Cycles Camera::focaldistance
```

职责划分：

- `camera/ExposureHistogram.java`：纯直方图统计。
- `camera/AutoExposureController.java`：纯 AE 目标求解与时间响应。
- `render/GpuExposureMeter.java`：GPU 降采样、异步回读和工作色域亮度。
- `render/AutomaticExposureStage.java`：Combined pass 门控与 AE 生命周期。
- `camera/CameraProjection.java`：与 Cycles 相机一致的屏幕坐标到射线方向映射。
- `camera/AutofocusRaycaster.java`：Minecraft 世界射线采样。
- `camera/AutofocusController.java`：目标簇选择与焦距时间响应。
- `camera/AutofocusStage.java`：OFF/SINGLE_SHOT/CONTINUOUS 状态机与 miss 策略。
- `CyclesRendererMod.java`：仅负责把相机帧、设置和两个阶段连接起来。

新增的算法/阶段文件均低于 500 行。既有大型入口和诊断文件只增加薄接线，没有继续堆入算法实现。

## 3. 自动曝光算法

### 3.1 HDR 测光输入

AE 只接受 `Combined` pass 的 scene-linear `RGBA16F` 输出。Depth、Normal、Albedo 等数据 pass 不具备可解释的曝光亮度，因此自动回到手动 `exposureEv`，也不会继续采样。

每 50 ms 最多发起一次测光：

- GPU shader 对输入做四点均值，输出固定 `64 x 36 RGBA16F`。
- 使用三个 `MAP_READ` buffer 轮转，回调完成后才在渲染线程读取。
- 没有空闲槽时丢弃本次 capture，不阻塞渲染线程。
- epoch 和 sequence 防止 reset 之前的迟到回调污染新状态。

该预算每次回读约 `64 * 36 * 8 = 18 KiB`，最高约 20 次/秒；它不回读完整渲染分辨率。

### 3.2 亮度与直方图

按当前 working space 选择亮度系数：

| 工作色域 | 亮度系数 R/G/B |
| --- | --- |
| Linear Rec.709 | 0.2126 / 0.7152 / 0.0722 |
| Linear Rec.2020 | 0.2627 / 0.6780 / 0.0593 |
| ACEScg | 0.27222872 / 0.67408174 / 0.05368952 |

正有限亮度转换到 `log2(Y)`，进入 `[-16, +16]` 范围的 256 桶直方图。默认剔除最低 2% 与最高 2%，在剩余区间计算加权 log 均值，同时保留高光百分位。

测光模式：

- `AVERAGE`：所有有效像素等权。
- `CENTER_WEIGHTED`：权重为 `1 + strength * exp(-2 * radius^2)`。
- `HIGHLIGHT_PRIORITY`：保持全画面统计，并取消额外高光余量，使高光限制更严格。

### 3.3 EV 目标

中灰目标使用约 18% 灰：

```text
keyEV       = log2(0.18) - meanLogLuminance
highlightEV = log2(highlightOutput) - highlightLogLuminance
targetEV    = clamp(min(keyEV, highlightEV + headroom) + compensation,
                    minimumEV, maximumEV)
```

其中手动 `exposureEv` 在自动模式下解释为曝光补偿，而不是被忽略。

### 3.4 时间响应

初始化时直接采用第一个有效目标。之后：

- 变亮和变暗使用独立时间常数。
- 指数响应 `alpha = 1 - exp(-dt / seconds)`，与帧率无关。
- `deadbandEv` 抑制测光噪声引起的小幅闪烁。
- `maximumEvPerSecond` 限制单次最大 EV 步进。
- locked 状态继续测量并更新 target，但冻结 current，便于解锁后观察目标。

AE 只改变 display uniform。配置修订仍会经过 native bridge，但 native 比较会清零 revision/debug-only 字段；自动化设置本身不在 native settings ABI 中，所以该更新是 `RESET_NONE`，不会清空 Cycles 积累或 DLSS 历史。

## 4. 自动对焦算法

### 4.1 模式与采样节奏

- `OFF`：使用手动 `focusDistance`。
- `SINGLE_SHOT`：取得一次有效状态后保持，切换模式或 reset 才重新测量。
- `CONTINUOUS`：最多 20 Hz 重测；渲染帧之间复用平滑后的焦距。
- locked：已有状态时冻结焦距；未初始化时允许先得到一个有效状态。

景深关闭时 AF 自动 reset 并回到手动焦距，避免无意义的射线和 camera revision。

### 4.2 屏幕采样

`CENTER` 使用中心一条射线。`AREA` 使用九点图案：中心、上下左右和四个对角点。

- 中心权重 1.0，并标记为 primary。
- 十字点权重 0.75。
- 对角点权重 0.5。
- `areaRadius` 控制九点在屏幕空间的展开半径。

中心目标会在后续簇评分中获得 4 倍 primary 加权，使区域模式能抗小遮挡，又不会轻易丢失准星主体。

### 4.3 投影一致性

射线不是用固定透视 FOV 近似，而是复用与 Cycles 相同的投影参数：

- 透视：Minecraft FOV 或物理镜头 focal length/sensor width，包含 camera shift。
- 全景：等距圆柱、等角立方体面、镜像球、鱼眼等距、鱼眼等立体角、鱼眼镜头多项式、中心圆柱。
- 鱼眼/镜像球有效圆外的采样点直接忽略。

透视相机需要的是沿光轴的焦平面距离，因此使用 `rayDistance * max(0, -localDirection.z)`；全景相机没有单一焦平面，使用径向距离。

### 4.4 世界测距和目标选择

射线调用 Minecraft `ClientLevel.clip`：

- 方块模式为 `OUTLINE`，匹配玩家可见/可选中的方块几何。
- 可配置是否把流体纳入命中。
- 最大距离由 `maximumDistance` 限制。
- 没有命中的射线不伪造远平面样本。

有效距离按升序排列，并在 `log2(distance)` 中按 `clusterGapStops` 分簇。选择总权重最高的簇，再取簇内加权中位数。这样可避免平均值落在前景与背景之间，也能抑制单条异常远射线。

miss 策略：

- `HOLD_LAST`：保留最后有效焦距；尚未初始化时使用手动距离。
- `MANUAL_DISTANCE`：使用手动焦距。
- `FAR_LIMIT`：使用 AF 最大距离。

### 4.5 焦距时间响应

AF 在 `log2(distance)` 中平滑，单位自然对应焦距“档位”：

```text
alpha = 1 - exp(-dt / responseSeconds)
step  = clamp((targetLog2 - currentLog2) * alpha,
              -maximumStopsPerSecond * dt,
              +maximumStopsPerSecond * dt)
currentDistance = 2 ^ (currentLog2 + step)
```

绝对死区 `deadbandDistance` 与相对死区 `deadbandRatio` 任一满足即保持当前焦距，避免远近尺度不同导致的抖动。

## 5. Native ABI 与 DLSS 契约

AF 阶段把 `CyclesBridgeCamera` 原来的两个保留字段正式定义为：

| 偏移 | 类型 | 字段 |
| --- | --- | --- |
| 72 | float | `focus_distance` |
| 76 | uint32 | `flags` |

结构总大小仍为 80 字节；AF 独立阶段 ABI 为 38。`CYCLES_BRIDGE_CAMERA_FOCUS_DISTANCE_VALID` 为 bit 0。未设置该位时 native 必须使用 settings 中的手动焦距，保证旧调用语义明确。

native 校验：

- 拒绝未知 flag。
- 有效 override 必须有限并位于 `[0.01, 1,000,000]`。
- camera 去重同时比较 flag 与有效焦距，焦距不变不会制造新 camera revision。
- diagnostics 返回实际送入 Cycles 的焦距，而不是静态 settings 值。

DLSS experimental 构建中，只有 `Camera::focaldistance` 真实变化超过容差时才调用 `request_dlss_history_reset()`。这与景深改变造成运动向量/重建历史失效的事实一致；不会禁用 DLSS、不会回退到 OptiX/OIDN，也不会因相同焦距重复 reset。

后续 PBR 设置扩展把共享最终 ABI 提升到 39；这不改变上述 AF 字段偏移和语义。

## 6. 配置与诊断

配置入口位于现有 Cycles 设置界面的 Color 与 Camera 分类。依赖关系会隐藏无效项：

- AE 未开启时隐藏其调参项。
- AF 为 OFF 时隐藏 AF 调参项。
- 景深未开启时隐藏焦距/光圈及自动对焦相关项。
- 非全景相机不会显示全景专用参数。

debug overlay 的 `[ CAMERA AUTOMATION ]` 区域报告：

- AE enabled/locked/initialized、metering、current/target/manual EV。
- measurement/capture/drop/pending readback 数。
- AF configured/effective mode、locked、initialized/single-shot 状态。
- current/target/native focus，以及 accepted measurement/ray 数。

这些数值是判断“算法没有命中”“GPU readback 堵塞”“控制器被锁定”还是“native 未接受焦距”的第一检索入口。

## 7. 验证记录

已执行：

- JDK 25 `gradlew test`：12 项 AE/AF/投影单元测试通过。
- 原生 `gradlew buildNative`：ABI 39 组合工作树编译成功。
- native smoke：相机 shift restore 后设置 3.0m AF override，成功得到新 Combined frame；diagnostics 精确返回 3.0m。
- native smoke 第二次运行：AF 状态清理后七种 panorama 均继续产生帧。
- `cyclesrenderer_scene_update`：通过。

该轮全量 CTest 最终失败点位于并行 PBR 开发中的 raw-pass cache 断言，不在 AE/AF 路径；AF、全景和 scene-update 的分段断言均已先行通过。

尚未执行：

- Minecraft 客户端内真实暗室/日光切换视觉验收。
- 真实 gameplay 下连续近远对焦、流体、单次锁焦和所有全景类型的视觉验收。
- DLSS experimental 二进制的本阶段重新编译与运行时重建质量检查。
- GPU 驱动对 `exposure_meter.fsh` 的游戏内实际编译；Gradle resource packaging 已覆盖，但不能替代驱动运行。

## 8. 手动验收清单

1. 开启 Combined、景深和 debug overlay；先保持 AE/AF 关闭，确认输出与手动设置一致。
2. 开启 AE，在室内与日光间移动；确认 target 先变化，current 按 brighten/darken 常数平滑追随，画面无明显闪烁。
3. 锁定 AE；确认 current 冻结而 target/measurement 仍更新。切换到非 Combined pass，确认回到手动 EV。
4. 开启 AF continuous，对准近处方块再转向远景；确认 current/target/native 焦距收敛且无前后跳动。
5. 对比 CENTER 与 AREA；用细小前景遮挡中心，确认区域簇选择符合预期。
6. 验证 SINGLE_SHOT 与 locked；移动镜头后焦距应保持，切换模式后可重新采样。
7. 对准天空测试三种 miss 策略；再开启 includeFluids 对准水面。
8. 逐一切换七种 panorama，确认射线命中与视觉焦点合理。
9. DLSS 模式下重复近远对焦；确认 DLSS 保持启用，焦距变化后无明显旧景深历史残影。
10. 观察 dropped/pending；正常负载下偶发 drop 允许，但不能持续满槽或导致渲染线程停顿。

## 9. 后续可扩展方向

- 从实体包围盒或准星目标引入可选语义权重，但应保持纯 block-ray 路径作为可靠回退。
- 为移动目标加入短时跟踪和速度预测，并把“目标切换”与“同目标移动”分开限速。
- 在直接 HDR/scRGB Cycles 输出成熟后，重新定义 `highlightOutput` 的物理显示目标；当前 HDR 阶段仍嵌入已合成的 SDR 输出。
- 若未来使用 Cycles depth pass 对焦，应设计延迟补偿和 generation 对齐，不能直接用陈旧深度覆盖同帧 Minecraft 射线结果。
