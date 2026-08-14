# Cycles Renderer 代码质量与架构审查

审查日期：2026-08-14

审查性质：基于当前工作树的静态审查快照

审查范围：`src/main/java`、`src/main/resources`、`native/src`、`native/include`、`native/tests`、Gradle/CMake 构建入口及相关文档

## 1. 边界与现状

- 真正的源码仓库是 `E:\MCservers\MClife_client\cycles-mod`；外层 `MClife_client` 不是 Git 仓库。
- `.deps/`、`.gradle/`、`.tools/`、`build/`、`bin/`、`run/`、启动器资源及其他整合包脚本不属于本次自有源码审查范围。
- 审查时工作树已有 19 个已跟踪文件被修改，并有 `native/src/realtime_section_mesh.*` 两个新增源码文件。这些均视为用户在途工作，本次未改动。
- 工作树还有一个未跟踪的 0 字节文件 `clear`，来源不明，本次未删除。
- 当前自有 Java/C++ 源码约 22,678 行。文件行数只用于提示进一步检查，不构成拆分条件；只要职责、生命周期和编译边界完全单一，大型文件可以保留。

## 2. 总体结论

项目还没有失去架构边界：Java 包职责、Java/native 分层、稳定 C ABI、防止结构体漂移的尺寸断言、CMake 目标以及 native 测试基础都存在，方向总体正确。

当前主要风险不是“目录完全无序”或文件行数本身，而是功能持续堆进少数编译单元，形成跨语言的多职责热点。`cycles_engine.cpp`、`NativeBridge.java`、`cycles_bridge_smoke.cpp` 经职责分析后确认混合了多个生命周期或编译职责，应按稳定边界分阶段治理；`CyclesRendererMod.java` 和旧场景捕获链还包含可证实的失活代码，应先清理再继续扩展。

建议质量评级：**结构基础尚可，局部职责债务高；在边界理顺前，停止向已确认的多职责热点追加新的独立生命周期。**

## 3. 文件职责审查基线

| 文件 | 快照行数 | 职责结论 |
| --- | ---: | --- |
| `native/src/cycles_engine.cpp` | 3,990 | 必须拆分；多职责核心热点 |
| `src/main/java/dev/cyclesrenderer/nativebridge/NativeBridge.java` | 2,441 | 必须拆分；ABI、绑定、状态、编解码、DTO 混合 |
| `native/tests/cycles_bridge_smoke.cpp` | 1,689 | 必须拆分；单一 `main` 覆盖过多独立场景 |
| `src/main/java/dev/cyclesrenderer/CyclesRendererMod.java` | 1,113 → 586 | 已删除 529 行零调用旧叠加实现；现有事件编排职责可继续审查 |
| `native/src/cycles_bridge.cpp` | 982 | 接近单一 ABI 边界职责，但校验与导出委托宜分离 |
| `src/main/java/dev/cyclesrenderer/config/CyclesClientConfig.java` | 926 | 必须拆分；配置定义、快照、编辑器元数据、Draft 混合 |
| `native/include/cycles_bridge.h` | 833 | 可保留；职责是单一稳定 C ABI，行数不是问题，禁止机械拆分 |
| `render/VulkanExternalBufferPrototype.java` | 787 | 高风险内聚类；暂可保留，新增能力前先抽离支持查询/分配细节 |
| `scene/SectionSceneManager.java` | 621 | 主职责基本内聚；资源/图集创建段可后续抽离 |
| `render/VulkanCapabilityProbe.java` | 611 | 同一 Vulkan 能力域，暂可保留并限制继续增长 |
| `CyclesDebugOverlay.java` | 610 | 单一诊断展示职责，暂可保留 |
| `scene/ClientRenderSnapshot.java` | 599 | 已删除；活动路径无调用的旧固定范围捕获实现 |
| `native/src/color_management.cpp` | 597 | 单一色彩管理职责，可保留 |
| `config/CyclesRenderSettings.java` | 594 | 稳定设置/枚举契约，体量可接受但应停止加入非契约逻辑 |
| `perf/FramePerformanceMonitor.java` | 519 | 性能采集职责基本内聚，继续增长前拆分分类/窗口策略 |
| `scene/DistantHorizonsSceneProvider.java` | 502 | 已删除；数据更新入口失活且近期不恢复 DH |

## 4. 按优先级排序的发现

### P0：禁止继续混入新职责的核心热点

#### 4.1 `cycles_engine.cpp` 已成为 native “上帝文件”

证据：文件包含约 5 个类、7 个结构体、42 个顶层函数；`CyclesEngine::Impl` 自身横跨约 1,587 行。其职责包括设备枚举、CUDA UUID、FrameStore、CPU DisplayDriver、Vulkan interop DisplayDriver、内存图像加载、材质/World/相机构建、降噪调度、Session 工作线程、诊断和请求合并。

推荐拆分边界：

1. `frame_store.*`：CPU 帧槽、Pass cache、租约。
2. `display_driver.*`：`FrameDisplayDriver`。
3. `vulkan_interop_display.*`：Win32 handle/timeline/槽所有权和 interop DisplayDriver。
4. `scene_builder.*`：内存图像、材质、World、Section scene 应用。
5. `camera_and_sampling.*`：相机变换、输出尺寸、积分器和降噪调度。
6. `cycles_engine.cpp`：只保留请求状态机、工作线程与以上组件的编排。

拆分时不得修改 `cycles_engine.h` 公共接口、C ABI、线程语义、Win32 HANDLE 所有权、timeline generation 规则或 Session reset 等级。

#### 4.2 `NativeBridge.java` 混合了五层职责

证据：文件前部定义全部 FFM `MemoryLayout`；中部提供静态 façade；`BridgeState` 负责动态符号绑定、renderer 生命周期和所有 native 调用；随后继续承担资源/设置/相机的手写偏移编解码；末尾定义大量公共 DTO 和诊断格式化逻辑。

推荐在保留 `NativeBridge` 现有公共入口的前提下拆成包内组件：

- `NativeLayouts`：布局、尺寸、偏移和 ABI 常量。
- `NativeSymbols`：library lookup、downcall handles、打开/关闭 renderer。
- `NativeMarshalling`：scene/settings/camera/diagnostics 编解码与校验。
- `NativeSession`：有状态调用及 frame lease 生命周期。
- `NativeBridge`：薄 façade；现有调用方不变。

不要同时手改 Java 偏移、C 结构和业务调用。每一步必须以 Java/C 尺寸断言、ABI mismatch 拒绝路径和 native smoke 为回归门。

#### 4.3 `cycles_bridge_smoke.cpp` 已不再是可定位失败的测试

单一 `main` 同时覆盖 ABI、HANDLE 所有权、非法配置、OCIO、LabPBR、Section 流送、全部全景相机、Pass cache、OptiX/OIDN/DLSS、帧租约和动态分辨率。任何早期失败都会阻断后续场景，难以判断独立能力的回归状态。

建议保留一个测试可执行文件，但将场景拆为多个测试源和命名函数，例如 `abi_contract_tests`、`interop_ownership_tests`、`color_tests`、`camera_tests`、`denoiser_tests`、`scene_streaming_tests`；共享等待/校验工具进入 `smoke_test_support.*`。

### P1：近期应完成的治理

#### 4.4 根事件编排类保留了整套零调用旧叠加层（本批已治理）

审查时，`CyclesRendererMod.extractDebugOverlay()` 已委托给 `CyclesDebugOverlay.extract()`；`extractLegacyDebugOverlay()` 及其四个专用 helper 没有调用，却占据约 529 行。它使根类从约 580 行膨胀到 1,113 行，并复制诊断格式化规则。

全局引用扫描没有发现调用或反射入口，本批已删除该私有旧实现及仅供它使用的 helper，活动的 `CyclesDebugOverlay` 委托和 API 保持不变。

#### 4.5 旧场景捕获链仍在主编译源集中（本批已治理）

审查时，`ClientRenderSnapshot` 除自身构造外没有活动调用；README 也明确称其为“过渡参考”。`DistantHorizonsSceneProvider.update()` 没有调用，根类只调用它的 `reset()`，因此 DH 数据路径实际不工作。

产品决策已明确为近期不恢复 DH。本批删除了 `ClientRenderSnapshot`、`DistantHorizonsSceneProvider` 及根生命周期中的无意义 reset；历史设计留在阶段规划文档与 Git 中，不再让失活实现充当主源码文档。未来若恢复兼容，应作为独立兼容单元接到当时的活动场景数据流，而不是恢复旧固定范围快照。

#### 4.6 `CyclesClientConfig` 将持久化契约与 UI 编辑模型绑死

该类同时维护 NeoForge `ModConfigSpec` 字段、不可变设置快照、revision/save、约 80 项 UI metadata、`ConfigOption`、`Category`、`ValueKind` 和可编辑 `Draft`。UI 直接依赖这些嵌套类型，使配置持久化层不能独立验证。

推荐保持 TOML key、默认值、范围和 enum native ID 完全不变，只把编辑器 metadata/Draft 移到 `client` 包或专门的 `config.editor` 包；`CyclesClientConfig` 保留 spec、snapshot、save/revision。

#### 4.7 Gradle 验证入口漏跑一个已注册的 native 测试（首批已治理）

CMake 定义并注册了 `cyclesrenderer_scene_update_test`；此前 `buildNative` 通过目标依赖只保证它被编译，Gradle `runNativeSmoke` 仅执行 `cyclesrenderer_smoke.exe`。首批治理已新增 `runNativeTests`，通过 CTest 执行全部已注册测试，并保留单独 smoke 入口。

当前文档已经区分“已编译”和“已执行”；后续新增 Native 测试必须注册进 CTest，统一入口不再手工维护测试文件列表。

#### 4.8 ABI 文档落后于实现（首批当前事实已同步）

当前 Java 与 native 实现都声明 ABI v36，设置结构为 368 字节，诊断结构为 624 字节。首批治理已将 README 的当前 ABI、结构尺寸和验证入口同步到实现；历史版本说明继续留在各阶段文档中。

建议 ABI 事实只维护一个权威清单，并在 ABI 升级提交中强制同步 README/阶段文档。不要把 README 中的旧版本说明当作当前实现来源。

### P2：结构方向与维护性

#### 4.9 Java 依赖存在两个小型反向边

- `render.CyclesRenderPipelines` 为获取 `MOD_ID` 反向依赖根编排类 `CyclesRendererMod`。
- `render.VulkanCapabilityProbe` 与 `VulkanExternalBufferPrototype` 直接依赖 `mixin` 包中的 accessor。

前者可用无行为的模块常量消除；后者应至少通过 render/platform 边界接口集中类型转换，避免核心渲染逻辑把 mixin 实现包当普通 API。当前规模下不需要新建大型抽象框架。

#### 4.10 Java 侧没有测试源集

当前没有发现 `src/test` Java 测试。配置 snapshot/metadata、纯枚举映射、白平衡、部分诊断格式化和尺寸/范围计算可在不启动 Minecraft 的情况下做小型测试。优先测试稳定契约和纯函数，不要为了覆盖率模拟整个客户端。

#### 4.11 `Prototype` 命名与实际关键路径不一致

`VulkanExternalBufferPrototype` 已承载正式的三槽 Vulkan/CUDA 互操作、资源分配、拷贝、同步和 telemetry，不再只是原型。重命名会影响多文件但不影响序列化；应在独立、低风险阶段处理，不与互操作行为修改混做。

## 5. 应保留的良好边界

- Java 包已经按 `client/config/mixin/nativebridge/perf/render/scene` 分域，不建议为了形式上的“整洁”建立新模块或程序集。
- `cycles_bridge.h` 是单一稳定 C ABI。它虽超过 800 行，但不应按行数拆散或改变布局。
- `CyclesEngine` 的 PImpl 公共头较薄，是正确方向；拆分应发生在私有实现内部。
- `cycles_bridge.cpp` 中 native `static_assert` 与 Java `byteSize()` 检查目前一致，是跨语言安全网。
- `.deps/`、构建输出和运行目录已被 Git 忽略；不要把第三方 Cycles 源码或生成物纳入自有代码统计。
- `scene_update` 已形成独立、小型、可测试的数据合并组件；新逻辑应优先沿用这种模式。
- 当前在途的 `realtime_section_mesh.*` 将固定拓扑 Section 槽从 `cycles_engine.cpp` 抽离，是符合治理方向的拆分。

## 6. 推荐执行顺序

每个阶段单独规划、单独验证，不要一次重写 Java 与 native 两侧。

1. **无行为清理**：旧 snapshot/DH 链和旧 debug overlay 已删除；确认无来源的 `clear` 后再处理。
2. **补齐验证入口**：让两个 native 测试都能从一个明确 Gradle 命令执行；同步 ABI 文档。
3. **拆 Java 配置职责**：迁移 editor metadata/Draft，保持 TOML 和 public façade。
4. **拆 Java native bridge 内部**：先 layouts/marshalling，再 symbols/session；每步保持 façade。
5. **拆 native engine 私有组件**：先 FrameStore/DisplayDriver，再 Vulkan interop，再 scene builder；最后缩小 `Impl`。
6. **拆 smoke 场景**：在生产边界稳定后按能力域拆测试源，避免重构与测试重排同时失去基线。
7. **消除小型反向依赖并补纯 Java 测试**。

## 7. 验证矩阵

后续每个治理阶段至少执行与风险相称的检查：

| 改动类型 | 必需验证 |
| --- | --- |
| 仅删除零调用 Java 私有代码 | `compileJava`、全局引用扫描、`git diff --check` |
| 配置拆分 | `compileJava`、配置 key/default/range 对照、F9 页面手测、旧 TOML 加载 |
| NativeBridge 内部拆分 | `compileJava`、ABI/布局断言、renderer probe、完整 native smoke |
| `cycles_engine.cpp` 私有拆分 | MSVC `/W4` 构建、两个 native 测试、OptiX/CUDA/CPU 至少适用后端 smoke |
| Vulkan interop 拆分 | HANDLE 所有权失败路径、timeline 三槽协议、Minecraft 实机启动/退出/重建 |
| C ABI 任何变化 | 明确 ABI 升级、Java/C 同步尺寸和偏移、旧 DLL 拒绝路径、README 同步 |

## 8. 首批项目级治理与验证结果

- 新增 Gradle `runNativeTests` 入口，通过 CTest 执行 CMake 当前注册的全部 Native 测试；原有 `runNativeSmoke` 保持不变。
- `run-client.cmd help --task runNativeTests --console=plain` 通过，Gradle 将其识别为 `verification` 组的 `Exec` 任务。
- 首次执行 `run-client.cmd runNativeTests --console=plain` 时，`cyclesrenderer_scene_update` 通过；`cyclesrenderer_native_smoke` 在当时新增的 camera-shift 场景超时失败。失败期间 Native 持续报告 `state=rendering`、`resolution=0x0`，最终 shift 为 `0/0`。这是该次运行的原始证据，不能回溯改写。
- 相机阶段提交 `feee09c`、`6e269c2`、`06541e6`、`ae4e63b` 后，camera shift 应用、恢复、checksum 变化、诊断回读及随后 Pass 1–6/Combined 恢复均已通过；默认与 DLSS native smoke 目标构建、`compileJava` 和 scene-update 也通过。当前默认 OptiX smoke 的剩余超时已移动到既有 `Perspective → panorama 0` 增量切换，后续应归类为 panorama Session 生命周期问题，不再归类为 camera-shift。
- 本次运行中 `configureNative` 与 `buildNative` 均为 `UP-TO-DATE`，因此不能表述为完成了一次干净的 Native 重编译。
- 未运行 Minecraft 实机，也未验证真实 Vulkan/CUDA timeline 图像复制。
- 首批除 `build.gradle`、`README.md` 和本审查留档外，未修改、删除、移动或格式化业务源码、资源、依赖与生成物。

第二批低风险生命周期清理：

- 按“近期不做 DH”的产品决策，删除零活动调用的 `ClientRenderSnapshot` 与 `DistantHorizonsSceneProvider`，并移除根事件编排类中的 import 和两次无意义 reset。
- 删除 `CyclesRendererMod` 中 529 行零调用旧诊断实现及专用 helper，继续使用活动的 `CyclesDebugOverlay` 委托；根类由 1,115 行降至 586 行（按本批工作树计）。
- 保留并同步历史 DH 设计说明，但明确其不是当前实现；README 不再把失活骨架列为活动入口。
- `run-client.cmd compileJava --console=plain` 通过；全局源码引用扫描未发现删除类型的残留引用。
- 未运行 Minecraft 实机；本批不改变活动 Section 流送、相机、配置、Java/native ABI 或资源。

## 9. 后续审查门槛

- 不设置按行数强制拆分的门槛；无论文件大小，都以职责、生命周期、依赖方向、编译边界、命名可读性和测试边界判断质量。
- 大型但职责完全单一的稳定协议、数据表、绑定声明或内聚实现可以保留；小文件若跨层、混合生命周期或制造反向依赖，同样必须治理。
- 禁止继续向 `cycles_engine.cpp`、`NativeBridge.java`、`cycles_bridge_smoke.cpp` 混入新的独立职责，除非同一变更先完成对应边界抽离。
- 项目级审查先检查目录层级、文件名、生命周期所有者、编译链和 API/契约方向；随后再进入函数、内部实现、硬编码与统一常量管理。
- 每次 ABI、序列化、配置 key、枚举 ID、HANDLE 所有权或 timeline 协议变化都必须单独审查。
- 审查统计必须排除 `.deps/`、`build/`、`run/`、`bin/` 与第三方/生成代码。
