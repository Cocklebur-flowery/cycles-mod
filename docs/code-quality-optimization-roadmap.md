# Cycles Renderer 代码级质量优化路线图

状态：`Q0 / Q0C / Q1 / Q2 / Q3 / Q4a / Q4b DONE`（2026-08-17）

检查基线：`019d7ec`

本路线承接已经完成的职责与生命周期治理，继续处理函数内部控制流、共享策略、
硬编码、私有 API 和算法验证。它不以文件或函数行数作为整改指标，也不重新拆分
已经确认职责单一的稳定边界。

## 1. 范围与排除项

Q0 检查范围：

- `src/main/java`
- `native/src`
- `native/include`

Q0 明确排除：

- `.deps/`、`build/`、`run/`、生成源码和第三方 Cycles 源码。
- `src/test` 与 `native/tests` 的测试实现质量；它们只用于核对生产热点是否已有保护。
- `.tmp-d3-baseline/` 与 `patches/cycles-v5.2-dlss-dof-guide.patch` 两项 D3 WIP。
- ABI schema 扩展、功能开发、画面调整和性能调参。

Q0 是只读审计。生产源码、测试、ABI、资源和构建图均未修改。

## 2. 判定方法

第一轮用启发式扫描寻找以下信号：

- 大约不少于 80 行的函数。
- 嵌套深度、条件分支或布尔组合明显偏高的函数。
- 参数过多、重复状态失效、重复清理或多种退出路径。
- 把稳定数据表、ABI 映射或配置目录误报成长函数的情况。

启发式结果只生成候选，不直接生成整改结论。Q0 随后人工复核候选的职责、
生命周期、调用方向、失败路径和现有测试。Java record 构造器、C++ 宏、FFM layout、
连续 marshalling/decoding 和配置目录均按真实语义重新分类。

当前生产 Java/C++ 源码约 24,688 行；源码中未发现 `TODO`、`FIXME`、`HACK` 或
`XXX` 标记。这只能说明没有显式待办，不代表所有内部实现已经最优。

## 3. Q0 结论

Q0 没有发现新的职责或生命周期拆分红项。既有大型文件仍符合架构路线图的
`KEEP` 判定；后续只允许在文件内部按可命名阶段整理，除非新的功能需求证明出现了
第二职责或第二生命周期。

真正需要继续处理的不是最大文件，而是以下三类局部问题：

1. 字符串 ID 驱动的设置可见性规则缺少独立 characterization test。
2. 客户端 renderer controller 在多个关闭路径重复写入相同 bridge 失效状态。
3. 场景资源构建和部分 native 状态机虽职责单一，但函数内阶段较多；在重排前必须
   先补足契约或生命周期保护。

## 4. 人工复核结果

### 4.1 后续可治理热点

| 对象 | 约行数 | 判定 | 证据与前置条件 |
| --- | ---: | --- | --- |
| `CyclesSettingsList.isEnabled()` | 100 | `TEST-FIRST` | 约二十条基于 option ID、camera、denoiser、PBR 与 AE/AF 状态的可见性规则串在一个条件链中；规则属于单一 UI policy，但当前没有脱离 Minecraft UI 的测试边界。只有先锁定全部输入组合和默认行为，才允许抽成 package-private policy。 |
| `CyclesRendererController` 的 bridge 关闭路径 | 5 处 | `CLEANUP` | `nativeBridgeReady = false` 与 `appliedSettingsRevision = -1L` 在渲染失败、shutdown、初始设置失败、interop rebuild 和 disable 中重复。它们表达同一状态失效事实，适合先收口为私有状态转换；不得顺便改变 `NativeBridge.close()`、interop drain/close 或日志顺序。 |
| `CyclesRendererController.onRenderLevelAfterLevel()` | 148 | `REVIEW-AFTER-CLEANUP` | 依次协调 scene、camera、interop、CPU fallback、presentation、telemetry 和失败恢复，属于单一 frame orchestration。只有 Q1 收口重复状态后仍能证明私有阶段可独立命名且不制造参数搬运时，才做同文件函数整理。 |
| `SectionSceneManager.createResources()` | 187 | `TEST-FIRST` | 同一 scene-resource 生命周期内串联 atlas 发现、尺寸推导、PBR atlas、RGBA 像素复制和固定 material descriptor。阶段可命名，但 texture/material index、像素通道和 fallback 是稳定契约；当前没有直接的资源构建 characterization test。 |
| `SectionGeometryCollector.decode()` | 108 | `TEST-FIRST` | quad 解码、材质捕获和输出缓冲写入处于同一 capture 责任，但边界条件较多。未来整理前需锁定顶点顺序、颜色、UV、material flag 和空输入行为。 |

### 4.2 高风险状态机：延后整理

| 对象 | 约行数 | 判定 | 保留理由 |
| --- | ---: | --- | --- |
| `CyclesEngine::Impl::worker_main()` | 275 | `DEFER` | 单一 worker 生命周期中协调 settings、reset、backend fallback、scene delta、camera revision 和 frame completion。重复的 session cancel/reset 确实存在，但本地状态彼此约束；在没有更细的状态转换测试前抽 helper 可能隐藏 revision 与清理顺序。 |
| `CyclesEngine::Impl::start_render()` | 148 | `KEEP` | reset/configure/prepare/session-start 阶段已经由 telemetry 明确命名，并共享 Cycles scene lock 所有权。长度来自可观测阶段和锁边界，当前拆分收益不足。 |
| `session_config::configure_scene_settings()` | 127 | `ALGORITHM-GATE` | Integrator、denoiser schedule、pass 和 Film 设置共同形成一次 session configuration。任何拆分或重排都可能改变降噪、采样和输出像素语义，必须进入后续算法阶段并使用双变体 smoke。 |
| `labpbr::build_material_graph()` | 208 | `ALGORITHM-GATE` | 构建一个完整材质图，已有 glass、height、metal、parallax 和 surface helper。剩余分支直接决定可见材质，不能作为普通清理移动。 |
| `scene_builder::apply_scene_delta()` | 104 | `ALGORITHM-GATE` | remove、reuse、update 与 create 的顺序决定 section slot 生命周期和 DLSS history reset。只有性能或正确性证据出现时才优化。 |

### 4.3 长但应保留的单一实现

| 对象 | 判定 | 原因 |
| --- | --- | --- |
| `valid_settings()` / `valid_scene_data()` | `KEEP` | 稳定 C ABI payload validation 表；拆成大量 helper 会削弱字段完整性审查。等待真实 ABI schema 阶段统一处理漂移风险。 |
| `NativeSettingsMarshaller.write()` | `KEEP` | 连续 Java-to-native ABI 写表，已有 contract test；不按长度拆散。 |
| `NativeDiagnosticsDecoder.decode()` | `KEEP` | 连续 native-to-Java ABI 读表，已有 contract test；下一次真实 ABI 变更前不迁移 schema。 |
| `SettingsCatalog.buildOptions()` | `KEEP` | 单一选项目录数据表，顺序、ID、范围和 translation key 都是稳定配置契约。 |
| `VulkanSharedAllocation.allocate()` | `KEEP` | 创建 buffer、memory 与两个 semaphore 的单一事务，局部 handle 和 `finally` 共同表达逆序回滚所有权。 |
| `VulkanFrameInterop.encodeCopy()` | `KEEP` | 单一 command-buffer copy/sync 提交，错误路径必须与 fence/queue 所有权保持在一起。 |
| `camera_adapter::configure_camera()` | `KEEP` | 单次相机转换算法；projection、DoF、viewplane 和 BufferParams 同属一个输出。 |
| `labpbr::connect_parallax_uv()` | `KEEP` | 单一 shader graph 算法；节点数量不是第二职责证据。 |
| `CyclesDebugOverlay.writeFixedCapabilities()` / `writePerformance()` | `KEEP` | `019d7ec` 后各自只格式化一个诊断分区；行数来自固定展示字段，`extract()` 已收口为状态采集和有序调度。 |

### 4.4 Q1 共享策略与硬编码复核

Q1 对生产 Java/C++ 的时间、容量、尺寸、资源/配置 ID、ABI offset、shader socket 和
Vulkan 数值做了全量启发式扫描，再按所有者与稳定契约人工复核。重复文本或数值本身
不是集中化依据，当前结论如下：

| 对象 | 判定 | 原因 |
| --- | --- | --- |
| `CyclesRendererController` bridge 失效状态 | `Q1a CLEANUP` | 五个关闭路径表达同一私有状态转换；收口为 `invalidateNativeBridgeState()`，保持每处 `NativeBridge.close()`、interop drain/close、日志和条件顺序。 |
| Controller 2 秒 scene stats 间隔 | `Q1a CLEANUP` | 属于本类明确诊断策略，命名为带单位的 `STATS_LOG_INTERVAL_NANOS`；数值不变。 |
| `VulkanCapabilityProbe` surface format/color-space 数值 | `Q1b CLEANUP` | 同一类已经命名 RGBA16F 与 scRGB，却在候选判断和名称映射中重复其余 Vulkan 数值；独立阶段只做同值常量替换。 |
| 配置 ID 与 dependency option ID | `Q4a TEST-FIRST` | ID 是稳定 UI/config contract；先由可见性 characterization test 锁定规则，不在 Q1 搬移字符串。 |
| Java/C++ 的 3840×2160、ABI size/offset/flag | `KEEP` | 属于跨语言配置和 ABI contract，已有 range/layout/contract 检查；下一次真实 ABI 变化前不扩大生成器或批量迁移。 |
| AE/AF、GPU readback 的同值 50 ms | `KEEP` | 分属对焦采样和曝光 readback 两个独立生命周期，只是当前数值相同，不是共享策略。 |
| telemetry EMA、纳秒/微秒换算字面量 | `KEEP` | 各 owner 的局部数学实现清晰；提取公共工具会制造反向依赖而不减少 contract 漂移。 |
| shader socket 名、Mixin injection token、Cycles include 名 | `KEEP` | 是各自外部 API 的局部绑定符号，集中到项目常量不会产生共同所有者。 |
| Scene 内部 LabPBR texture namespace | `KEEP` | 三个 ID 在同一资源数组中定义；直接依赖 client entrypoint 的 `MOD_ID` 会反转 scene→entrypoint 依赖，当前局部显式值更清晰。 |

Q1a 与 Q1b 必须分开验证和提交。Q1a 不改变 bridge 是否关闭，只统一 Java 对关闭结果的
本地记账；Q1b 不改变 Vulkan 枚举数值或选择顺序。

Q1a 自动门禁在首轮全部通过：`compileJava test jar --rerun-tasks` 执行 10 个 task；
默认与 DLSS `verifyProject --rerun-tasks` 各执行 Java build 和 6 个 native CTest 域，
没有失败或 Skipped。用户随后使用 DLSS 客户端确认 F8 启用首帧、interop capacity
rebuild、关闭恢复原版、再次启用首帧与正常退出均无异常。现存 Java deprecation 与
Gradle 10 compatibility warning 未在 Q1a 混修，继续属于 Q5。

Q1b 只把现有 Vulkan surface format 与 color-space 数值命名为本类私有常量，数值、
候选顺序和展示名称均未改变。Java 门禁与默认完整门禁通过；首次 DLSS 完整门禁为
5/6，通过域之外仅 `cyclesrenderer_smoke_scene_lifecycle` 缺失一次 scene timing
telemetry（`commits=3; deltas=1; starts=66`）。紧随其后的单域定向测试在 24.08 秒内
通过。该 native CTest 不加载本次修改的 Java 类，因此保留为 Q5 的已知间歇红项，
不以定向复核通过覆盖首次失败证据。

### 4.5 Q2 私有帧阶段整理

Q2 将 `CyclesRendererController.onRenderLevelAfterLevel()` 中已经存在的两条呈现路径
分别命名为私有的 interop 轮询/呈现阶段和 CPU fallback 获取/呈现阶段。帧级回调仍负责
scene、camera、呈现路径选择、失败恢复和最终 marker；没有增加 DTO、公开 API 或第二套
状态。原有 marker、计时、轮询间隔、早返回、日志条件/文本与异常传播顺序保持不变。

自动门禁首次全部通过：`compileJava test jar --rerun-tasks` 执行 10 个 task，默认与
DLSS `verifyProject --rerun-tasks` 各通过全部 6 个 native CTest 域。由于本阶段修改每帧
renderer callback，用户随后完成 Minecraft F8 启用、首帧、关闭、再启用和退出流程，
确认运行正常。

### 4.6 Q3 内部 API、参数与 DTO 边界复核

Q3 对跨 package 方法、参数较多的方法、telemetry/result record、native marshalling
载体和当前仓库无调用的 public 方法做了调用方复核。没有发现应在本阶段修改的生产
边界：减少参数数量不能凌驾于所有权、兼容性和稳定契约。

| 对象 | 判定 | 原因 |
| --- | --- | --- |
| `CyclesDebugOverlay.extract()` | `KEEP` | 只有一个 controller 调用方，但参数明确暴露 presenter、autofocus、interop、scene 与 requested/accepted settings 等不同 owner；把它们塞入一个大 `Inputs` record 只会形成参数包并隐藏依赖。 |
| `CyclesDebugOverlay.RuntimeStats` | `KEEP` | 九个字段全部属于 controller 的同一组 bridge/camera/frame-delivery 运行统计；该不可变快照避免 overlay 反向依赖 controller。 |
| Q2 两个私有 presentation 阶段 | `KEEP` | 三至四个参数分别是该帧的 target、settings、camera 与 scene update；创建 DTO 不减少所有者，也不会改善生命周期。 |
| `SectionSceneManager.UpdateResult` 与各 Telemetry record | `KEEP` | 是跨 package 的不可变观察快照；调用方消费多个字段，直接返回 owner 内部对象反而会泄漏可变状态。 |
| `SectionGeometrySnapshot` 与 `NativeBridge` records | `KEEP` | 是 Java/native marshalling、ABI 或测试契约；字段数量来源于线性 payload，不按普通 DTO 清理。 |
| `NativeSceneMarshaller.*Segments` | `KEEP` | 聚合一次 Arena 生命周期内共同返回的 memory segment，表达明确的 native call ownership。 |
| `FoliageSolidifier` 输入/结果 records | `KEEP` | 是单一几何算法的显式数组和中间数据；未来修改须经过 Q4b characterization 或算法门禁。 |
| 当前仓库无调用的 public facade / Presenter overload | `DEFER` | 删除会缩小公开二进制表面；没有兼容性或弃用策略时，不能仅凭仓库内无调用判定安全。 |

本阶段仅更新审计文档，没有修改源码、API、ABI、资源、配置或构建图，因此不重复运行
刚在 Q2 首次通过的 Java/default/DLSS 门禁。Q4a 将从测试侧建立设置可见性 seam，不以
Q3 的参数数量审计为理由提前改动配置契约。

### 4.7 Q4a 设置可见性 characterization boundary

Q4a 将 `CyclesSettingsList` 中约百行的 option dependency 条件链迁入 package-private
`SettingsVisibilityPolicy`。policy 只接收 option ID 与值 lookup，不依赖 Minecraft
Widget、NeoForge 配置生命周期或持久化对象；列表仍拥有 draft、控件刷新和 choice
filtering。设置 ID、判断顺序和启用语义保持不变。

新增 5 个 focused tests：四组锁定 AE/AF、adaptive sampling、动态分辨率、相机投影、
DoF、安全区、denoiser、PBR 与白平衡组合；一组遍历真实 option catalog，并让未知依赖
ID 直接失败。测试同时保留既有 `camera.fisheyeLens` 只依据 panorama type 的行为，
不在 characterization 阶段顺手修正规则。

focused test 在接入 UI 前和接线后均通过；`compileJava test jar --rerun-tasks` 执行
10 个 task，默认 `verifyProject --rerun-tasks` 通过全部 6 个 native CTest 域。首次
DLSS 完整门禁为 5/6，仅已知的 `cyclesrenderer_smoke_scene_lifecycle` 再次缺失一次
scene timing telemetry（`commits=3; deltas=1; starts=67`）；紧随其后的单域有界复核
在 24.00 秒内通过。该 native 测试不加载本阶段 Java UI 类，首次失败证据继续归 Q5，
不以复核通过覆盖。逐阶段 F9 人工抽查按当前串行执行约定合并到 V0 实机矩阵，不作为
Q4a 自动化提交的阻塞项。

### 4.8 Q4b compiled geometry decode characterization boundary

Q4b 将 `SectionGeometryCollector` 内纯 buffer 解码、quad normal、triangle/material 写入和
后续 overlay/foliage 处理迁入 package-private `SectionGeometryDecoder`。Collector 仍拥有
Minecraft compile hook、level 校验、capture telemetry、pending queue 与 `MeshData`/vertex
format 适配；新 decoder 不拥有这些生命周期，也没有扩大公开 API。

新增 4 个 focused tests，锁定单 quad 的顶点顺序、颜色、UV、法线、三角形 winding 与
material index；锁定多层输入顺序和非零 buffer position；锁定退化 quad 的向上 fallback
normal；锁定空层输入生成 metadata 完整的空 snapshot。`SectionGeometrySnapshot` 的 stride、
material 数值、payload 数组和 sequence/origin 语义均未改变。

在性能阶段 `5839586` 成为新 HEAD 后，focused test、`compileJava`、完整 Java tests 与 jar
重新执行并通过。Q4b 初次默认 `verifyProject` 通过 6/6；初次 DLSS 完整门禁在 contract 与
color 域通过后，于 `smoke_render` 无新增输出地运行约 707 秒，因此被有界终止并保留为
Q5 的门禁可靠性红项。该 native executable 不加载本阶段 Java decoder，不能作为 Q4b
characterization 的通过或失败证据；性能提交自身随后记录了当前 HEAD 的 default/DLSS
focused native 4/4 通过。逐阶段 Minecraft geometry 抽查合并到 V0。

## 5. 后续阶段顺序

Q0C 依据二次独立复核补齐当前事实同步、测试可靠性和算法前实机基线；后续仍按
小阶段串行执行，不因路线确认而放宽稳定契约或停止条件：

```text
Q0   函数与控制流热点审计                         DONE
Q0C  Q0 文档与当前工程基线同步                    DONE
  -> Q1a Controller 状态失效与局部策略             DONE
  -> Q1b Vulkan surface format/color-space 常量     DONE
  -> Q2  私有函数与内部实现整理                   DONE
  -> Q3  内部 API、参数与 DTO 边界复核             DONE
  -> Q4a 设置可见性 characterization test         DONE
  -> Q4b geometry decode characterization test    DONE
  -> Q4c scene resource characterization boundary PENDING
  -> Q5  编译警告与双变体门禁可靠性复核           PENDING
  -> V0  算法修改前实机基线                       PENDING
  -> A0  单一算法、单一指标与正确性 oracle 选择    PENDING
  -> A1  经独立确认后的单算法优化                  PENDING
```

Q1 应先确认哪些数值或字符串是共享策略，哪些只是清晰的局部字面量，并优先处理
controller 的重复 bridge 失效状态。Q2 才根据 Q0/Q1 证据整理私有函数；不得为了缩短
函数引入无所有权的 `Utils`、大参数对象或第二套状态。Q4a、Q4b 和 Q4c 分别处理
设置策略、compiled geometry 与 scene resource 三种不同测试边界，不合并为一个大提交。

Q5 必须核对 `SectionSceneManager` deprecated API、Gradle 10 compatibility 警告和
DLSS scene-lifecycle 曾出现的首轮失败；在确认失败域与复现条件前，不把复跑成功写成
“从未失败”。V0 只关闭或明确保留当前工程基线中的 `NOT RUN` 实机矩阵；发现产品 bug
时另立修复阶段，不带病进入 A0。

ABI schema 不在 Q1-Q4 队列中。下一次真实 ABI 新增或字段迁移前，另立契约阶段定义
pointer、array、float/double、padding 和 alignment 语义，并一次只迁移一个结构。

## 6. 每阶段门禁

- 纯 Java 私有整理：focused test、`compileJava test jar`、`git diff --check`。
- renderer callback 或 F8/F10 行为：再运行默认/DLSS `verifyProject` 和对应 Minecraft
  启用、首帧、关闭、再启用、退出或 overlay 实机流程。
- native 私有状态机：默认/DLSS focused CTest 后再运行完整 `verifyProject`；失败域必须
  独立记录，不能用复跑抹去首次证据。
- 设置可见性、geometry decode 与 scene resource：先建立可在错误实现上失败的
  characterization test，再允许整理生产实现；测试 seam 不得扩大公开 API。
- 编译与门禁可靠性：使用 `--warning-mode all` 定位当前 warning，并对不稳定域做有界
  重复运行；不能通过无限复跑获得绿色结论。
- 算法前实机基线：明确 default/DLSS、动态分辨率、DoF 与 SDR/HDR/screenshot 的
  已测和未测边界；A1 只接受 A0 选出的一个算法、一个指标和一个正确性 oracle。
- PBR、denoiser、camera、scene delta 或其他可见算法：必须先有数值/内容断言，再进行
  同场景实机对照；不能以“看起来更好”作为唯一结论。

Q0 自身不修改运行行为，因此不运行 Java/native 构建或 Minecraft。文档阶段只检查
路径、当前事实、diff 和工作区边界。
