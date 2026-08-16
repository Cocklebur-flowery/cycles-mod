# Cycles Renderer 职责拆分路线图

状态：当前架构治理执行路线

建立日期：2026-08-16（Asia/Shanghai）

建立基线：`6e42ee7`

执行进度：C、B、E 已完成；当前进入 R 后置热点复核与基线收口。

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

以下规模只对应建立路线时的已检查基线：

| 文件 | 行数 | 当前判定 |
| --- | ---: | --- |
| `native/src/cycles_engine.cpp` | 4,028 | 多生命周期核心，必须按私有组件拆分 |
| `src/main/java/dev/cyclesrenderer/nativebridge/NativeBridge.java` | 2,505 | 布局、绑定、marshalling、解码、session 与 DTO 混合 |
| `src/main/java/dev/cyclesrenderer/config/CyclesClientConfig.java` | 1,018 | 持久化、snapshot、选项目录与 Draft 混合 |
| `src/main/java/dev/cyclesrenderer/render/VulkanFrameInterop.java` | 917 | 当前生命周期基本统一，暂不机械拆分 |
| `native/include/cycles_bridge.h` | 871 | 单一稳定 C ABI，明确不拆 |
| `src/main/java/dev/cyclesrenderer/CyclesRendererMod.java` | 624 | 入口接线，随下游组件形成逐步减负 |

Native smoke 已按 contract、color、render、denoiser、scene lifecycle 与独立
scene-update 域拆分，不再属于本路线的生产代码拆分目标。

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
| R | `NEXT` | 只读复核剩余热点和 ABI schema 漂移风险，按证据决定是否继续拆分 |

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
