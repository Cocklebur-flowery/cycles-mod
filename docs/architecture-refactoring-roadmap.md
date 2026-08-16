# Cycles Renderer 职责拆分路线图

状态：`DONE`（2026-08-16）

建立日期：2026-08-16（Asia/Shanghai）

建立基线：`6e42ee7`

执行进度：C、B、E、R0、R0A、R0B、R1、R2 和 R5 已完成。本轮职责治理
与架构冻结已收口。

本文件规定稳定化门禁关闭后的生产代码职责拆分顺序。它描述治理边界、依赖、
验证和提交纪律，不替代当前源码、ABI schema、测试或
[`engineering-baseline.md`](engineering-baseline.md) 中的事实状态。

## 1. 目标与执行原则

目标不是把大文件机械切小，而是让每个组件拥有清楚的职责、生命周期、输入、
输出、同步域和验证边界。

执行原则：

1. 串行、单写者执行；一个阶段完成验证并提交后才进入下一阶段。
2. 一个提交只迁移一个职责，不混入功能修复、性能调整、画面变化或格式化。
3. 先建立特征测试，再移动生命周期代码。
4. 公共门面优先保持稳定，拆分发生在私有实现内部。
5. 文件大小只作为审计信号，不作为完成指标。
6. 若阶段需要改变稳定契约，立即停止并单独规划，不以“重构”名义继续。

新功能开发在本路线完成前继续冻结。

## 2. 当前热点与判定

以下规模只对应建立路线时的已检查基线；“初始判定”保留当时决策，当前处置以
第 8 节 R0 二次独立复核为准：

| 文件 | 行数 | 初始判定 |
| --- | ---: | --- |
| `native/src/cycles_engine.cpp` | 4,028 | 多生命周期核心，必须按私有组件拆分 |
| `src/main/java/dev/cyclesrenderer/nativebridge/NativeBridge.java` | 2,505 | 布局、绑定、marshalling、解码、session 与 DTO 混合 |
| `src/main/java/dev/cyclesrenderer/config/CyclesClientConfig.java` | 1,018 | 持久化、snapshot、选项目录与 Draft 混合 |
| `src/main/java/dev/cyclesrenderer/render/VulkanFrameInterop.java` | 917 | 当前生命周期基本统一，暂不机械拆分 |
| `native/include/cycles_bridge.h` | 871 | 单一稳定 C ABI，明确不拆 |
| `src/main/java/dev/cyclesrenderer/CyclesRendererMod.java` | 624 | 入口接线，随下游组件形成逐步减负 |

Native smoke 已按 contract、color、render、denoiser、scene lifecycle 与独立
scene-update 域建立独立 CTest 报告。R0A 又将 render 与 scene-lifecycle
的 suite 实现分别放入独立源文件；该测试物理职责尾项已收口。

## 3. 全程保持的稳定契约

除非另立明确的契约阶段并获得确认，本路线必须保持：

- ABI 版本、结构 size/alignment/offset、enum、flag、symbol 和拒绝行为。
- 配置 key、schema、默认值、范围、enum ID、保存位置和旧配置加载语义。
- Shader、resource、sampler、uniform、format 和 pipeline 顺序。
- Frame slot、byte stride、generation、lease、HANDLE 和 timeline semaphore 所有权。
- Scene、Camera、Settings revision 及其失效规则。
- Session create/reset/disable/re-enable/close 和 reset level。
- OptiX、OIDN、DLSS、CUDA、CPU 的选择与 fallback 策略。
- PBR、色彩、曝光、相机、DoF、HDR 和输出像素语义。

`.tmp-d3-baseline/` 与 `patches/cycles-v5.2-dlss-dof-guide.patch` 是明确排除的
D3 WIP，不得被任何阶段暂存、提交、验证或清理。

## 4. 总体执行顺序

```text
C  配置职责拆分
  -> B  NativeBridge 私有实现拆分
  -> E  cycles_engine.cpp 私有组件拆分
  -> R  后置热点复核与基线收口
```

| 阶段 | 状态 | 当前结果 |
| --- | --- | --- |
| C | `DONE` | 配置持久化/runtime snapshot、Draft、option model 与 catalog 已分离 |
| B | `DONE` | NativeBridge 公共门面稳定，layouts、symbols、marshalling、decoding 与 session ownership 已分离 |
| E | `DONE` | Engine 已收口为渲染协调器；默认/DLSS 自动门禁和 E6 Minecraft 生命周期验收通过 |
| R | `DONE` | R0 二次独立复核、R0A/R0B/R1/R2 与 R5 最终基线均已收口；R3 保留、R4 延后，没有机械扩张 |

配置阶段是低风险的拆分纪律验证，不替代两个主要核心文件。配置阶段完成后必须
立即进入 `NativeBridge`，不得无限扩张 UI 或配置功能。

## 5. C：配置职责拆分

### C1：配置特征测试

生产代码不变，只建立拆分保护。

锁定：

- Option ID 的唯一性、顺序、Category 和 ValueKind。
- 数值 minimum/maximum/step 与 enum choices。
- `options()` 的只读性。
- Draft 的 dirty、恢复原值、discard 与归一化。
- 默认 snapshot 的代表性稳定字段与 revision 语义。

验证：`compileJava test jar`。

建议提交：`test(config): characterize settings editor contracts`

### C2：抽离 Draft 编辑生命周期

从 `CyclesClientConfig` 移出 baseline、edited values、dirty tracking、discard 和
apply-result 计算。Draft 不直接拥有 NeoForge `SPEC.save()`；持久化层负责实际写入
与 revision 增长。

保持 F9 页面公开行为、关闭未保存编辑和应用后保存行为不变。

验证：C1 全部测试、`compileJava test jar`，必要时手工打开 F9 并执行
修改、放弃、应用和重开。

建议提交：`refactor(config): isolate settings draft lifecycle`

### C3：抽离选项目录与编辑器元数据

分离 Category、ValueKind、Option metadata、range、choices、translation key 和
显示顺序。NeoForge ConfigValue 的定义和 runtime snapshot 继续由持久化层拥有；
两者通过明确绑定连接。

完成形态：

```text
CyclesClientConfig
  - NeoForge SPEC / persistence
  - revision
  - runtime snapshot

Settings catalog / option model
  - editor metadata
  - normalization
  - ordering and choices

Settings draft
  - temporary edited state
  - dirty/discard/apply result
```

验证：C1 全部测试、配置 key/default/range 对照、`compileJava test jar`，旧有效
TOML 的加载验证在能够安全隔离用户配置时执行；否则明确记录 `NOT RUN`。

建议提交：`refactor(config): separate editor option metadata`

## 6. B：NativeBridge 私有实现拆分

`NativeBridge` 的公开静态门面、record 和调用语义在整个 B 阶段保持稳定。

### B0：门面、布局与编解码保护

盘点并测试公开方法、record、常量、FFM layout、symbol 和关键编解码。现有
Vulkan interop schema 保持最小生成原型；本阶段不顺便全量生成 ABI。

验证：`compileJava test jar`、默认/DLSS contract CTest。

建议提交：`test(abi): characterize the Java native facade`

### B1：抽离 FFM 布局事实

将 Camera、Settings、Scene resources、Frame/lease、Capabilities、Diagnostics、
Color、Pass 与 Vulkan interop layouts 移入单一 package-private 布局组件。

只移动声明和断言，不改变任何字节事实。

验证：默认与 DLSS 完整 `verifyProject`。

建议提交：`refactor(abi): isolate native memory layouts`

### B2：抽离 library 与 symbol 绑定

独立负责 DLL 定位、Arena 生命周期、symbol lookup、MethodHandle 构造和缺失
symbol 错误。不得承担 scene、camera、settings 或 diagnostics 语义。

验证：默认与 DLSS 完整 `verifyProject`；确认首个底层加载错误仍被保留。

建议提交：`refactor(native): isolate library symbol binding`

### B3：按领域抽离 marshalling

严格按以下子阶段依次提交：

1. B3a Render settings marshalling。
2. B3b Scene/resources marshalling 与合法性校验。
3. B3c Camera/frame marshalling 与 frame lease。
4. B3d Vulkan interop descriptor/state marshalling。

每个子阶段只迁移一个领域，不修改 layout、范围或错误文本。

验证：每个子阶段至少 `compileJava test jar` 和对应 focused contract；B3b、B3c、
B3d 结束时运行默认与 DLSS 完整 `verifyProject`。

### B4：抽离 native 结果解码

依次抽离 capabilities、diagnostics、color LUT 和 pass descriptor 解码。公开 DTO
暂时保留在 `NativeBridge`，避免同时制造调用方迁移。

验证：默认与 DLSS 完整 `verifyProject`；diagnostic state/error 名称保持一致。

建议提交：`refactor(native): isolate bridge result decoding`

### B5：收口 BridgeState 与公共门面

最终 `BridgeState` 只拥有 renderer handle、session 调用串行化、close 和关联的
native session 生命周期；`NativeBridge` 只做稳定公共门面与错误边界。

里程碑验证：默认与 DLSS 完整 `verifyProject`，以及一次 Minecraft F8
启用、首帧、关闭、再启用和退出。

建议提交：`refactor(native): reduce the bridge to session orchestration`

## 7. E：cycles_engine.cpp 私有组件拆分

拆分只发生在 `CyclesEngine::Impl` 私有实现周围。`cycles_engine.h` 和 C ABI 不变。

### E1：FrameStore 与普通显示驱动

抽离 FrameSlot、pass cache、frame lease、generation、CPU copy/scale、diagnostics
和 `FrameDisplayDriver`。这是 native 核心的第一刀。

候选组件：`frame_store.h/.cpp`。

验证：默认与 DLSS 完整 `verifyProject`；frame publication、pass cache 和 lease
contract 必须实际执行。

建议提交：`refactor(native): isolate frame storage and leases`

### E2：Vulkan interop 显示驱动

抽离 interop snapshot、slot owner、slot state、timeline generation、
`VulkanInteropDisplayDriver` 与写槽释放。

候选组件：`vulkan_interop_display.h/.cpp`。

验证：默认与 DLSS 完整 `verifyProject`；Minecraft F8 里程碑验收 HANDLE、timeline、
disable/re-enable 与 close。

建议提交：`refactor(interop): isolate native display transport`

### E3：Cycles 场景构建

抽离 `MemoryImageLoader`、image 创建、atmosphere/background、完整 scene build 和
scene delta 应用。现有 `scene_update.*` 继续负责 Minecraft accumulator，不合并。

候选组件：`cycles_scene_builder.h/.cpp`。

验证：默认与 DLSS 完整 `verifyProject`；PBR 内容断言与 scene revision 必须保持。

建议提交：`refactor(scene): isolate Cycles scene construction`

### E4：相机校验与转换

抽离 camera 合法性、projection/panorama 比较、transform、BufferParams 和
DoF/pinhole 选择。

候选组件：`cycles_camera.h/.cpp`。

验证：默认与 DLSS render suite；perspective、全部 panorama subtype、shift、AF、
DoF 和 pass cache reset 断言保持。

建议提交：`refactor(camera): isolate native camera conversion`

### E5：Session、settings、denoiser 与 pass 配置

抽离 device policy、SessionParams、settings comparison/invalidation、sampling、
denoiser schedule、pass 注册和 scene settings。

候选组件：`cycles_session_config.h/.cpp`。

验证：默认与 DLSS 完整 `verifyProject`；配置变化对应的 reset level 不变。

建议提交：`refactor(native): isolate session configuration`

### E6：收口 Engine Impl

状态：`DONE`。父提交 `976f15c` 加 E6 最终工作树通过默认与 DLSS 完整
`verifyProject`；用户完成 Minecraft DLSS 启用、首帧、持续移动、关闭、再启用、
世界重进与退出验收。最终提交同时更新当前工程基线。

完成后 `CyclesEngine::Impl` 只负责请求队列、worker、session create/rebuild/start、
Scene/Camera revision 协调、状态、首个错误和 reset/close。

如果剩余实现仍然较长，但只剩同一协调生命周期，则允许保留；不得为了数字继续拆。

里程碑验证：默认与 DLSS 完整 `verifyProject`，Minecraft F8 启用、持续移动、
关闭、再启用、世界重进与退出，并更新工程基线。

建议提交：`refactor(native): focus the engine on render orchestration`

## 8. 后置热点复核

三个主阶段完成后才重新判断：

- `CyclesRendererMod` 是否已能缩成纯事件接线。
- `VulkanFrameInterop` 是否确实存在 allocation 与 frame-copy 两套可分生命周期。
- `CyclesSettingsList` 是否仍为单一 F9 列表职责。
- 是否需要把 ABI schema 从单结构原型扩展到更多机械布局。

未观察到职责或生命周期分离证据时，不做机械拆分。

### R0：热点复核结果

状态：`DONE`。生产代码基线为 `198bca0`；在仅有文档差异的
`1c12163` 上又独立复核了结构、调用方、状态所有权、ABI 生成链和所有
不少于 500 行的生产源文件，结论如下：

| 对象 | 判定 | 证据与处置 |
| --- | --- | --- |
| `CyclesRendererMod` | `DONE` | R1 已将 renderer 运行状态机抽入 package-private `CyclesRendererController`；入口类由 624 行减至 171 行，只保留 NeoForge/key/config/reload 接线、三个公开静态门面与薄转发 |
| `VulkanFrameInterop` | `DONE` | R2 已将长寿命 allocation/HANDLE/native bind 抽入 431 行的 package-private `VulkanSharedAllocation`；原门面由 917 行减至 574 行并只协调逐帧 acquire/copy/fence/TextureTarget 与公开 telemetry |
| `vulkan_interop_display.h` | `DONE` | R0B 已删除向 engine 暴露的 7 个可变引用 getter；display driver 现只接收单一 `VulkanInteropBinding&` 边界，文件与状态所有权不变 |
| `cycles_bridge_smoke_render_scene.cpp` | `DONE` | R0A 已将 115 行 scene-lifecycle 函数体机械移入独立源文件；原文件现为 522 行且只定义 render suite |
| `CyclesSettingsList` | `KEEP` | 512 行均服务 F9 列表筛选、依赖可见性、控件构造、输入归一化与 narration；没有第二资源生命周期或反向依赖 |
| ABI schema | `DEFER` | 现有单结构原型和双变体 contract 全绿；其余结构含 pointer、array、float/double 与 padding，扩展当前仅支持 `uint32/uint64` 的生成器会成为独立高风险契约阶段 |

其余不少于 500 行的生产源文件均已复核，未发现需要立即物理拆分的第二
生命周期：

| 对象（当前行数） | 判定 | 保留理由 |
| --- | --- | --- |
| `cycles_engine.cpp` (1,714) | `KEEP` | E 阶段后只保留渲染协调生命周期 |
| `cycles_bridge.cpp` (1,024) | `KEEP` | 稳定 C ABI 边界与 payload validation |
| `cycles_bridge.h` (871) | `KEEP` | 单一稳定 C ABI 声明 |
| `NativeBridge.java` (867) | `KEEP` | 稳定 Java facade、公开 DTO 与错误边界 |
| `frame_store.h` (701) | `KEEP` | frame store 与薄 display adapter 共用同一 frame publication/lease 生命周期 |
| `SectionSceneManager.java` (664) | `KEEP` | resource reset 与 delta streaming 受同一 scene origin/full reset 约束 |
| `CyclesDebugOverlay.java` (655) | `KEEP` | 单一诊断展示职责；较长 `extract()` 属于后续函数级整理，不是架构拆分理由 |
| `CyclesRenderSettings.java` (652) | `KEEP` | 稳定 settings record/enums 契约 |
| `VulkanCapabilityProbe.java` (611) | `KEEP` | 单一 Vulkan capability/bootstrap 职责 |
| `color_management.cpp` (597) | `KEEP` | 单一 OCIO/color-management runtime |
| `CyclesClientConfig.java` (564) | `KEEP` | C 阶段后的 persistence/runtime snapshot 门面 |
| `CyclesFramePresenter.java` (545) | `KEEP` | 单一 presentation 生命周期；AE/DoF 已分离 |
| `cycles_session_config.h` (500) | `KEEP` | 单一 session configuration 职责 |

`NativeBridgeContractTest.java`（683 行）虽覆盖多个 bridge 主题，但它的上位职责是
单一 bridge characterization；`cycles_bridge_smoke_support.cpp`（577 行）只提供 smoke
helper。两者当前均保留。

### R0A：拆分 Native smoke 的 scene-lifecycle 源文件

状态：`DONE`。`run_scene_lifecycle_scenarios` 已移入独立的
`native/tests/cycles_bridge_smoke_scene_lifecycle.cpp`；移动前后函数体哈希一致。
默认与 DLSS 完整 `verifyProject` 均通过，render 与 scene-lifecycle CTest
都实际执行且无 Skipped。

将 `run_scene_lifecycle_scenarios` 移入新文件
`native/tests/cycles_bridge_smoke_scene_lifecycle.cpp`，原文件只保留 render suite，
并更新 `native/CMakeLists.txt`。不改 suite 名称、执行顺序、skip 77、公共 helper、
场景数据、ABI 或生产行为。

验证：默认与 DLSS 完整 `verifyProject`，确认 render 与 scene-lifecycle CTest
均实际执行且无 Skipped。

建议提交：`refactor(test): isolate scene lifecycle smoke scenarios`

### R0B：收口 native Vulkan display binding API

状态：`DONE`。Engine 构造 display driver 时现只传入一个
`VulkanInteropBinding&`；driver 通过明确 friend 边界绑定原有 state、slots、mutex、
condition variable、stopping 和 camera revision，不重组任何同步状态。默认与
DLSS 完整 `verifyProject` 均通过。用户完成 Minecraft DLSS F8 启用、首帧、
持续更新、关闭、再启用和退出验收；日志记录两次 FrameGraph 接管、两次
恢复原版与正常关闭，没有 renderer fallback/failed。

保留 `VulkanInteropBinding` 对 mutex、condition variable、slot/state 和 camera revision 的
唯一所有权，但将 engine 构造 display driver 时传递的 7 个可变引用收口为
单一 binding/shared-state 边界。不改 mutex 域、唤醒时机、slot 所有权、HANDLE、timeline、
configured/produced camera revision 或关闭顺序，也不拆分
`vulkan_interop_display.h`。

验证：默认与 DLSS 完整 `verifyProject`；Minecraft DLSS 执行 F8 启用、
首帧、关闭、再启用和退出，核对 interop generation/timeline 持续推进。

建议提交：`refactor(interop): encapsulate native display binding state`

### R1：抽离客户端渲染控制器

状态：`DONE`。package-private `CyclesRendererController` 现独占 renderer 运行状态、
settings apply/rebuild、scene/camera/frame 调度、性能计数与 shutdown；
`CyclesRendererMod` 由 624 行减至 171 行并只保留入口职责。Java 构建与测试、默认和
DLSS 完整 `verifyProject` 均通过。用户随后完成 F9/F10 与 Minecraft DLSS F8 启用、
首帧、移动、关闭、再启用和退出验收；日志记录两次 FrameGraph 接管、两次恢复原版与
正常关闭，没有 native frame failure 或 renderer fallback。

新增 package-private `CyclesRendererController`，迁移 renderer 运行状态、启用/关闭、
settings apply/rebuild、scene/camera/frame 调度、性能计数和 shutdown。`CyclesRendererMod`
只保留 MOD/资源 ID、key 注册、config screen/reload 和 NeoForge 事件到 controller 的
薄转发。保持现有公开静态门面 `ensureNativeBridgeReady()`、
`isExperimentalRendererEnabled()` 和 `shouldReplaceVanillaWorld()` 的签名与语义；即使前两者
当前没有模块外的已知调用方，也不借重构删除公开 API。

稳定契约：`MOD_ID`、Logger category、资源/翻译/key ID、F8/F9/F10 映射、
NeoForge 事件 priority 与顺序、config/reload 语义、FrameGraph 接管条件、日志文本、
config revision、interop rebuild、fallback 和关闭顺序全部不变。

验证：`compileJava test jar`、默认与 DLSS 完整 `verifyProject`；随后执行一次 Minecraft
DLSS F8 启用、首帧、关闭、再启用和退出。

建议提交：`refactor(runtime): isolate the client renderer controller`

### R2：抽离 Vulkan interop allocation

状态：`DONE`。package-private `VulkanSharedAllocation` 现独占 capability validation、
VkBuffer/VkDeviceMemory、ready/release timeline semaphore、Win32 HANDLE 导出与转交、
native bind/unbind 和 allocation close；`VulkanFrameInterop` 保留逐帧 copy 生命周期与
原有全部公开 API。Java 构建与测试、默认和 DLSS 完整 `verifyProject` 均通过。用户随后
完成 `480x270` 到 `960x540` capacity rebuild、异形窗口 resize、F8 关闭、再启用、
首帧和退出验收；日志证明重建和两轮关闭/接管后正常关闭，没有 Cycles failure/fallback。

从 `VulkanFrameInterop` 抽出单一 package-private allocation 组件，独占 VkBuffer、
VkDeviceMemory、ready/release timeline semaphore、Win32 HANDLE 导出、capability validation、
native bind/unbind 和 allocation close。原门面继续独占逐帧 frame acquire、copy command、
fence、TextureTarget、generation 与 copy telemetry，并负责两个组件的调用顺序。

原门面的所有公开方法（initialize/telemetry、buffer/allocation/capacity、poll/frame/depth/
generation/copy telemetry、drain/close）以及公开 `Telemetry`/`CopyTelemetry` 字段与语义
保持兼容。

稳定契约：native 仍接受最小 8 B/px 的 color-only slot，Java 仍以 12 B/px
分配 color+depth slot；Java 策略和 native 上限都是 3 slots；RGBA16F/R32F、
HANDLE 所有权转移、timeline value、frame release、capacity rebuild、drain-before-unbind
与 close 顺序全部不变。本阶段不再继续拆 copy path。

验证：`compileJava test jar`、默认与 DLSS 完整 `verifyProject`；Minecraft DLSS 执行
F8 启用/关闭/再启用、输出分辨率扩大触发 capacity rebuild、窗口 resize 和退出。

建议提交：`refactor(interop): isolate Vulkan shared allocation`

### R3：保留 F9 列表整体

`CyclesSettingsList` 维持现状。未来只有在 visibility/choice policy 被第二个 UI 消费，
或需要不启动 Minecraft UI 的独立策略测试时，才考虑抽离；行数本身不是理由。

### R4：延后 ABI schema 扩展

不在纯架构收口中扩大 generator。下一次 ABI 新增或字段迁移前，先为目标结构定义
pointer、array、float/double、padding 和 alignment 的 schema 语义，再以单结构阶段
证明 Java layout、C++ assert、C header 和 contract smoke 同步；不得直接批量迁移。

### R5：最终基线收口

状态：`DONE`。在 R2 提交 `214852e` 上重新枚举了全部生产源码、测试大文件、
package 依赖、构建入口、ABI 生成边界、残留标记和工作区。当前共有 17 个不少于
500 行的生产源文件；除已经完成的 R0A/R0B/R1/R2 边界外，其余文件仍只拥有单一职责、
单一生命周期或稳定 ABI/facade 边界，没有新的拆分证据。`CyclesRendererController`
与 `VulkanSharedAllocation` 均为 package-private 且各只有一个生产调用方。

最终 HEAD 上默认和 DLSS 两套 `verifyProject --rerun-tasks` 均完整执行 Java build 与
6 个 native CTest 域，全部通过且没有 Skipped/Known Red。Minecraft DLSS 已覆盖核心
生命周期与 R2 capacity rebuild/异形窗口 resize。默认 Minecraft、CPU fallback、
dynamic resolution、Physical/Post-process DoF 和 SDR/HDR/screenshot 完整矩阵仍明确为
`NOT RUN`；D3 两项继续为 `EXCLUDED WIP`，不因路线完成转为产品基线。

源码中已没有残留的 `Prototype` 类型或文件名；`gradle.properties` 的
`mod_name=Cycles Renderer Prototype` 是当前实验性发布身份并与 README 描述一致，
不是职责拆分红项。未来若修改该名称，必须作为独立 packaging metadata 契约阶段处理。

R0A/R0B/R1/R2 完成自动化与实机里程碑后，重新核对所有超过 500 行的生产源码、依赖方向、
剩余红项和明确未测矩阵。若没有新的多职责证据，更新工程基线并结束本轮架构冻结。

## 9. 每阶段提交与验证纪律

每个阶段必须：

1. 开始前检查 HEAD、工作树和所有权。
2. 只暂存本阶段精确路径或 hunk。
3. 运行与风险相称的 focused gate。
4. 检查实际 diff 与 `git diff --check`。
5. 使用仓库 Level M 或 Level H 提交正文记录 Why、Changes、Validation、Contracts、
   Variants、Risks、Runtime evidence 和 Known limitations。
6. 提交后核对作者、正文、文件清单与剩余工作树。
7. 更新本路线的阶段状态，或在最终里程碑更新当前工程基线。

完整命令失败时不得写 PASS。独立域通过、失败、Known Red、Blocked 和 Not Run 必须
分别记录。

## 10. 强制停止条件

出现以下任一情况必须停止当前拆分：

- 需要修改 ABI、配置键、资源 ID、序列化或第三方 patch。
- 需要改变线程、mutex、HANDLE、semaphore、lease、generation 或 reset 语义。
- 特征测试在纯迁移后失败。
- Default 与 DLSS 结果不一致且无法证明是既有问题。
- 实机出现启动、首帧、F8 fallback、关闭或重进回归。
- 实际职责边界与本路线假设不符。
- D3 WIP 或未知外部差异与目标文件重叠。

停止后先建立独立 bug/契约阶段，不扩大当前提交。

## 11. 路线完成定义

本路线完成不以总行数为准，而以以下事实为准：

- 配置持久化、runtime snapshot、editor metadata 和 Draft 生命周期可分别命名和测试。
- `NativeBridge` 公共门面稳定，布局、绑定、marshalling、解码与 session ownership
  具有独立组件和验证边界。
- `cycles_engine.cpp` 不再拥有 frame store、Vulkan transport、scene construction、
  camera conversion 和 session configuration 的具体实现。
- Engine Impl 只保留渲染协调生命周期。
- 默认与 DLSS 自动门禁无新增红项，关键 native 里程碑完成实机生命周期验收。
- 当前工程基线与实际 HEAD、验证结果和未测项一致。
