# Cycles Renderer 代码级质量优化路线图

状态：`Q0 / Q0C DONE`（2026-08-17）

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

## 5. 后续阶段顺序

Q0C 依据二次独立复核补齐当前事实同步、测试可靠性和算法前实机基线；后续仍按
小阶段串行执行，不因路线确认而放宽稳定契约或停止条件：

```text
Q0   函数与控制流热点审计                         DONE
Q0C  Q0 文档与当前工程基线同步                    DONE
  -> Q1  共享策略、硬编码和重复状态失效审计       PENDING
  -> Q2  私有函数与内部实现整理                   PENDING
  -> Q3  内部 API、参数与 DTO 边界复核             PENDING
  -> Q4a 设置可见性 characterization test         PENDING
  -> Q4b geometry decode characterization test    PENDING
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
