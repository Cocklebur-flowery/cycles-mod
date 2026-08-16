# Cycles Renderer 当前工程基线

状态：当前事实清单

检查日期：2026-08-16（Asia/Shanghai）

检查对象：C/B/E 职责治理、E6 Minecraft 生命周期验收、R0 独立热点复核
与 R0A smoke 文件收口（R0A 基于 `90538a9` 的最终工作树；实机证据对应
父提交 `976f15c` 的最终 E6 工作树）

本文件只描述上述提交附近的当前事实、验证证据和已知红项。它不是历史阶段
记录，也不替代源码、构建配置、ABI 断言或测试。ABI、稳定契约、验证入口或
已知红项变化后，应在同一阶段更新本文件的检查对象和结果。

历史实现过程仍保存在 `docs/stages/` 和
`docs/code-quality-review-2026-08-14.md`；其中的旧 ABI、旧文件规模和旧测试
结果不得当作当前事实。

## 1. 状态定义

| 状态 | 含义 |
| --- | --- |
| `PASS` | 已在本基线或明确注明的当前人工验收中实际通过 |
| `FAIL` | 已执行并得到可重复的失败结果 |
| `KNOWN RED` | 已知失败，允许继续调查，但不得被描述为通过 |
| `BLOCKED` | 因前置失败未能执行或无法得到独立结论 |
| `NOT RUN` | 本基线没有执行，不能推断结果 |
| `EXCLUDED WIP` | 不属于当前产品基线，不得进入验证或提交 |

编译成功、测试成功、客户端启动、F8 启用、首帧、持续渲染和退出是不同的
验证阶段，不能互相替代。

## 2. 当前产品事实

| 项目 | 当前事实 | 事实来源 |
| --- | --- | --- |
| 游戏与加载器 | Minecraft 26.2、NeoForge 26.2 客户端 MOD | `gradle.properties`、`build.gradle` |
| Java 编译目标 | Java 25 toolchain | `build.gradle` |
| Native 后端 | Blender Cycles 5.2 bridge；默认和 experimental DLSS 两种构建变体 | `build.gradle`、`native/CMakeLists.txt` |
| 当前 C ABI | ABI 43 | `NativeBridge.ABI_VERSION`、`cycles_bridge_abi_version()`、native build info |
| 设置结构 | `CyclesBridgeRenderSettings` 为 392 bytes | Java layout 检查、C++ `static_assert` |
| 诊断结构 | `CyclesBridgeDiagnostics` 为 672 bytes | Java layout 检查、C++ `static_assert` |
| Vulkan interop | buffer/state 均为 80 bytes；state 的 depth dimensions 位于尾部 | `cycles_bridge_vulkan_interop_state.json`、生成的 Java layout、生成的 C++ `static_assert` |
| Java 测试 | camera 自动曝光/对焦/射线/直方图与 LabPBR 资源测试 | `src/test/java` |
| 项目验证入口 | Gradle `verifyProject` 先执行 Java `build`，再运行所选 native 变体的全部 CTest | `build.gradle` |
| Native 测试入口 | CTest 注册 5 个独立 smoke 能力域与 `cyclesrenderer_scene_update` | `native/CMakeLists.txt` |
| Native smoke 结构 | 无参数入口保留完整顺序；CTest 通过 `--suite` 独立报告 contract、color、render、denoiser 与 scene-lifecycle；render 与 scene-lifecycle 已有各自的物理源文件 | `native/tests/cycles_bridge_smoke*.cpp` |

S5 仅将 `CyclesBridgeVulkanInteropState` 作为最小原型：单一 JSON schema 生成
Java `MemoryLayout`/offset 常量和 C++ 全字段 size/offset 断言。公开 C 头中的结构
声明保持不变，其他 ABI 结构仍由 C 头、C++ 断言、Java layout 和 smoke contract
多处人工维护。因此这不是全量 ABI 生成链，也没有改变 ABI 43 或结构布局。

## 3. 已收口的启动故障

| 项目 | 状态 | 证据 |
| --- | --- | --- |
| Minecraft 26.2 Mixin 启动崩溃 | `PASS` | `5830b16 fix(mixin): restore client startup on Minecraft 26.2` |
| backend 初始化原始异常被通用错误覆盖 | `PASS` | `3a4812b fix(native): preserve backend initialization errors` |
| DLSS runtime kernels 与开发安装树不同步 | `PASS` | `fca5bd6 fix(dev): synchronize DLSS runtime kernels`；用户确认原故障已修复 |

2026-08-16 13:40 至 13:45 的 DLSS 客户端实机运行使用父提交 `976f15c` 加 E6
最终两文件工作树。运行期间没有继续修改产品源码；用户在退出后确认客户端验证
正常。该运行是 E6 最终源码内容的实机证据，但不是一次“E6 提交后重新启动”的
证据。默认与 DLSS 两套完整 `verifyProject` 已在同一 E6 工作树执行并通过。两类
证据在本基线中分别记录，不互相冒充。

## 4. 2026-08-16 自动验证与生命周期复核

### 4.1 Java 与打包

命令：

```text
run-client.cmd verifyProject --rerun-tasks --console=plain
run-client.cmd verifyProject -PexperimentalDlss=true --rerun-tasks --console=plain
```

| 领域 | 状态 | 结果 |
| --- | --- | --- |
| `compileJava` | `PASS` | 重新执行成功 |
| Java tests | `PASS` | 当前 5 个测试类、15 个测试用例在两次入口执行中全部成功 |
| jar | `PASS` | 默认与 DLSS 入口均重新打包成功 |

非阻断警告：`SectionSceneManager` 使用或覆盖了 deprecated API；Gradle 报告
当前构建使用了将在 Gradle 10 不兼容的 deprecated features。本阶段只记录，
不进行无关清理。

### 4.2 默认 Native 变体

命令：

```text
run-client.cmd verifyProject --rerun-tasks --console=plain
```

| 领域 | 状态 | 结果 |
| --- | --- | --- |
| Native configure/build | `PASS` | Release DLL、smoke、scene-update 目标构建成功 |
| `cyclesrenderer_smoke_contract` | `PASS` | 4.72 秒完成 |
| `cyclesrenderer_smoke_color` | `PASS` | 4.62 秒完成 |
| `cyclesrenderer_smoke_render` | `PASS` | 17.96 秒完成；7 种 panorama 与 perspective restore 全部发布新帧 |
| `cyclesrenderer_smoke_denoiser` | `PASS` | 21.21 秒；OptiX 与 OIDN 均完成 Interactive Raw → Still Denoised |
| `cyclesrenderer_smoke_scene_lifecycle` | `PASS` | 21.37 秒；前置域全绿后实际执行，不再 Skipped |
| `cyclesrenderer_scene_update` | `PASS` | 5.28 秒；等待实际发布的 `scene_timing_revision` 追平请求 revision |

2026-08-16 修复确认 camera type 和 panorama subtype 都属于 Cycles session 拓扑。
二者变化现在触发 session reset，而不是只重置 accumulation。render suite 锁定每次
panorama subtype 切换的 reset level，并分别验证 topology reset 前的 pass cache 与
reset 后的 cache 失效；绿色纹理阈值、相机投影数学和各 panorama 参数均未改变。

根因是 transmission smoke 扩展把第二个初始平面三角形改成 WATER，却继续把这张
混合材质画面作为纯 CUTOUT 绿色纹理基准。修复仅恢复首帧两个三角形均使用 CUTOUT；
WATER 和玻璃材质仍在同一资源重置中创建并接受合法性校验。

### 4.3 Experimental DLSS Native 变体

本轮标准 DLSS 输出目录没有被客户端占用，直接从统一入口重新配置、重建并执行
全量 CTest：

```text
run-client.cmd verifyProject -PexperimentalDlss=true --rerun-tasks --console=plain
```

| 领域 | 状态 | 结果 |
| --- | --- | --- |
| Native configure/build | `PASS` | 独立目录的 DLSS Release DLL、smoke、scene-update 目标构建成功 |
| `cyclesrenderer_smoke_contract` | `PASS` | 5.11 秒完成 |
| `cyclesrenderer_smoke_color` | `PASS` | 5.16 秒完成 |
| `cyclesrenderer_smoke_render` | `PASS` | 18.01 秒完成；7 种 panorama 与 perspective restore 全部发布新帧 |
| `cyclesrenderer_smoke_denoiser` | `PASS` | 23.24 秒；DLSS realtime、OptiX 与 OIDN 路径全部通过 |
| `cyclesrenderer_smoke_scene_lifecycle` | `PASS` | 23.51 秒；前置域全绿后实际执行，不再 Skipped |
| `cyclesrenderer_scene_update` | `PASS` | 5.70 秒；等待实际发布的 `scene_timing_revision` 追平请求 revision |

默认和 DLSS 均证明首次 Perspective→Panorama、6 次 subtype 变化及最终
Panorama→Perspective 会重建 session 并发布对应 camera revision。原 panorama
超时已关闭；denoiser 与 scene lifecycle 随后也在两种变体实际通过。`e3c8f1a`
上的默认与 DLSS 完整自动化验证没有已知红项；Minecraft 核心实机生命周期结果见
第 6 节。

### 4.4 S5 ABI schema 最小原型

执行命令：

```text
run-client.cmd compileJava --rerun-tasks --console=plain
run-client.cmd test jar --rerun-tasks --console=plain
run-client.cmd buildNative --rerun-tasks --console=plain
run-client.cmd buildNative -PexperimentalDlss=true --rerun-tasks --console=plain
ctest --test-dir build/native --build-config Release -R ^cyclesrenderer_smoke_contract$ --output-on-failure
ctest --test-dir build/native-dlss --build-config Release -R ^cyclesrenderer_smoke_contract$ --output-on-failure
```

| 领域 | 状态 | 结果 |
| --- | --- | --- |
| schema 生成 | `PASS` | 同一 CMake 生成器产出 Java layout/offset 常量和 C++ 全字段断言 |
| Java compile/test/jar | `PASS` | 生成任务接入 source set，编译、现有测试与打包成功 |
| 默认 Native build/contract | `PASS` | 生成断言参与 DLL 编译；contract CTest 通过 |
| DLSS Native build/contract | `PASS` | 生成断言参与 DLSS DLL 编译；contract CTest 通过 |
| ABI 兼容性 | `PASS` | ABI 仍为 43，interop state 仍为 80 bytes，字段顺序与 offset 未变 |
| S5 原型阶段的完整 `verifyProject` | `NOT RUN` | 当时的独立 `panorama 0` 红项会阻断完整 render suite；当前完整结果见 4.2 与 4.3 |

### 4.5 C/B/E 职责治理最终门禁

E6 最终工作树执行：

```text
./gradlew buildNative --no-daemon
./gradlew verifyProject --no-daemon
./gradlew -PexperimentalDlss=true verifyProject --no-daemon
```

| 领域 | 状态 | 结果 |
| --- | --- | --- |
| E6 focused native build | `PASS` | 默认 Release native 目标在 Vulkan binding 迁移后重新构建成功 |
| 默认完整门禁 | `PASS` | Java build 与 6 个 native/CTest 能力域全部通过 |
| DLSS 完整门禁 | `PASS` | Experimental DLSS 的 Java build 与 6 个 native/CTest 能力域全部通过 |
| ABI / 配置 / 资源 | `PASS` | E6 未修改 ABI 43、结构布局、配置键、shader、资源 ID 或第三方 patch |

C 阶段已将 Draft 生命周期、编辑器 option model 与 catalog 从配置持久化门面中
分离；B 阶段已将 layouts、symbol loading、各领域 marshalling、结果解码与 session
ownership 从公共 native facade 中分离；E 阶段已将 frame storage、display transport、
scene construction、camera conversion、session configuration 与 Vulkan binding 从
engine 协调器中分离。完整提交序列和职责边界见
[`architecture-refactoring-roadmap.md`](architecture-refactoring-roadmap.md)。

## 5. 分域测试结果

无参数 smoke 继续保留原有完整顺序：

```text
contract -> color -> render -> denoiser -> scene lifecycle
```

CTest 则为每个能力域启动独立进程。目标域自身失败返回 1；前置域失败返回 77，
由 CTest 显示为 `Skipped`。因此当前可以准确给出：

| 测试领域 | 状态 | 原因 |
| --- | --- | --- |
| ABI/bridge contract | `PASS` | 默认与 DLSS 独立 CTest 均通过 |
| color contract | `PASS` | 默认与 DLSS 独立 CTest 均通过 |
| frame publication / generation | `PASS` | 两种变体均收到 2 个 `FRAME_UPDATED` 帧，generation 与 Combined pass 正常 |
| initial scene textured content / render | `PASS` | 默认与 DLSS 均通过原绿色主导像素断言；阈值未变 |
| camera shift / autofocus / DoF / pass viewer | `PASS` | 两种聚焦 render 运行均到达并通过这些场景 |
| panorama | `PASS` | 默认与 DLSS 的 7 种 subtype 及 perspective restore 均发布新帧；每次拓扑变化均为 session reset |
| denoiser | `PASS` | 默认与 DLSS 均完成 OptiX/OIDN 的 Interactive Raw → Still Denoised；DLSS 变体同时通过 realtime DLSS |
| scene lifecycle / dynamic resolution | `PASS` | 默认与 DLSS 均在前置域全绿后实际执行完成，不再返回 skip 77 |
| scene-update contract | `PASS` | 默认与 DLSS 独立 CTest 均通过 |

S3 已消除“一个 CTest 红项令后续领域完全无报告”的问题。S4 又将初始场景的帧到达
与内容验收拆成连续断言。PBR 复核保持该内容断言原样，并修正了测试场景材质职责；
panorama lifecycle 修复后 render 域在两种变体全绿。原 denoiser 红项是 smoke 用
500 ms 墙钟延迟同时验收 Interactive Raw 和触发 Still 的阶段竞争：Session 重建较慢
时，测试会在调用 Still 等待 helper 之前失败。测试现先以允许范围内的最长延迟锁定
Raw，再显式请求零延迟 Still，并继续要求真实 Denoised 新帧。生产 ABI、调度和发布
实现未修改。

## 6. 游戏内验证状态

| 场景 | 状态 | 说明 |
| --- | --- | --- |
| 原 backend/DLSS kernel 故障 | `PASS` | 用户确认修复 |
| E6 最终工作树启动 | `PASS` | `runDLSSclientExp.bat` 使用父提交 `976f15c` 加 E6 最终两文件工作树启动；两次 Native ABI 43 / OptiX 16x16 自检成功 |
| F8 启用并产生真实世界首帧 | `PASS` | 实机确认；两次日志均从 scene staging 推进到跳过 vanilla world FrameGraph |
| F8 关闭并恢复原版 | `PASS` | 两次 suspend 均记录 native bridge kept warm，随后恢复 vanilla world FrameGraph |
| F8 再次启用 | `PASS` | 第二次 ABI 自检、scene build 与 active FrameGraph 接管成功，无 renderer failure |
| 持续移动与新区块更新 | `PASS` | 用户实机确认；两段 telemetry 分别推进到 interop generation 1487 与 1069，并持续提交 scene revision |
| 世界退出并重进 | `PASS` | 用户确认完整矩阵正常；日志同时证明最终 suspend、世界保存、客户端与 FML 正常关闭 |
| resize / 动态分辨率实机 | `NOT RUN` | 默认与 DLSS native lifecycle suite 已通过，但本次未单独记录 Minecraft 窗口 resize |
| Physical / Post-process DoF | `NOT RUN` | 本基线未执行 |
| SDR / HDR / screenshot fallback | `NOT RUN` | 本基线未执行完整矩阵 |
| 默认持续移动稳定性 | `NOT RUN` | 本次实机使用 experimental DLSS 变体，不能外推默认 artifact |
| DLSS 持续移动稳定性 | `PASS` | revision 与 interop generation 持续发布；未出现 renderer fallback/failed，最终正常退出 |

这些项目必须通过实际客户端验证关闭，不能由 Java、Native 编译或 320x180
smoke ready frame 推断。

## 7. 当前架构热点与保护边界

三个原始多职责热点已经完成职责治理：

- `native/src/cycles_engine.cpp` 当前约 1,720 行，只保留请求队列、worker、session
  create/rebuild/start、revision 协调、状态、首个错误和 reset/close。`FrameStore`、
  display transport/binding、scene builder、camera adapter 与 session configuration 已有
  独立私有组件。它仍然较长，但剩余代码属于同一渲染协调生命周期，不按数字继续拆。
- `src/main/java/dev/cyclesrenderer/nativebridge/NativeBridge.java` 当前约 867 行，保留
  稳定公共门面、公开 DTO 和错误边界。layouts、symbols、settings/scene/frame/interop
  marshalling、capabilities/diagnostics/color/pass decoding 与 session ownership 已有
  package-private 组件。
- `src/main/java/dev/cyclesrenderer/config/CyclesClientConfig.java` 当前约 564 行，保留
  NeoForge SPEC/persistence、revision 与 runtime snapshot。`SettingsDraft`、
  `SettingsOption` 和 `SettingsCatalog` 分别拥有编辑生命周期、选项模型与目录。

这些文件仍受热点保护：不得重新吸收已经抽离的职责。R0 二次独立复核确认：

- `CyclesRendererMod` 仍混合 NeoForge 入口接线与 renderer 运行状态机，R1 抽离
  package-private controller；保持 `ensureNativeBridgeReady()`、
  `isExperimentalRendererEnabled()`、`shouldReplaceVanillaWorld()` 三个公开静态门面，
  以及 MOD/资源/key ID、Logger category、事件 priority/顺序与关闭顺序。
- `VulkanFrameInterop` 存在 allocation/native bind 与逐帧 copy/fence/target 两套生命周期，
  R2 抽离 allocation；frame copy 继续由原门面协调。公开方法、telemetry 字段、
  native 8 B/px color-only 兼容、Java 12 B/px color+depth 分配、3-slot 策略/上限、
  HANDLE/timeline/release 与 drain-before-unbind/close 顺序均为稳定契约。
- R0A 已将 scene-lifecycle suite 移入
  `cycles_bridge_smoke_scene_lifecycle.cpp`；`cycles_bridge_smoke_render_scene.cpp` 现只定义
  render suite。独立 CTest 报告、skip 77、场景与 ABI 语义未变。
- `VulkanInteropBinding` 仍向 engine 暴露 7 个可变引用 getter；R0B 将其收口
  为单一 binding/shared-state 边界，不改线程、mutex、HANDLE、timeline 或关闭语义。
- `CyclesSettingsList` 保持单一 F9 列表职责，不因 512 行拆分。
- ABI schema 扩展延后到下一次真实 ABI 变更前的独立契约阶段，不在纯重构中批量迁移。

当前所有不少于 500 行的生产源文件都已复核。除上述实证边界外，
`cycles_engine.cpp`、`cycles_bridge.cpp/.h`、`NativeBridge.java`、`frame_store.h`、
`SectionSceneManager.java`、`CyclesDebugOverlay.java`、`CyclesRenderSettings.java`、
`VulkanCapabilityProbe.java`、`color_management.cpp`、`CyclesClientConfig.java`、
`CyclesFramePresenter.java`、`CyclesSettingsList.java` 和 `cycles_session_config.h` 均保留；
它们的长度不构成第二生命周期的证据。`CyclesDebugOverlay.extract()` 可在未来
做函数级整理，但不属于本轮架构拆分。

完整处置顺序和稳定契约见路线图 R0-R5。

`native/include/cycles_bridge.h` 虽然较大，但当前职责是单一稳定 C ABI；禁止
为了行数机械拆分或改变布局。

## 8. 明确排除的 WIP

以下路径不是当前产品基线，不得被验证脚本、暂存或提交自动包含：

- `.tmp-d3-baseline/`：D3 只读调查临时 Cycles baseline。
- `patches/cycles-v5.2-dlss-dof-guide.patch`：过时且不完整的 D3 guide patch。

`.deps/` 下的第三方/安装/构建树也不是产品源码。正式行为必须能从固定上游、
受控 patch 和 `scripts/setup-cycles.ps1` 重建。

## 9. 下一阶段门禁

按串行顺序执行：

1. `S2 DONE`：Gradle 单一 `verifyProject` 聚合入口已经建立并在默认、DLSS
   两种变体实际执行；总结果因已知 native smoke 红项准确失败。
2. `S3 DONE`：smoke suite 已按能力域独立 setup、执行和 CTest 报告；前置失败
   使用 skip 77 与目标域失败区分。
3. `S4 DONE`：initial-section 的 frame publication / wait contract 已准确重定义；
   两种 native 变体均证明帧发布正常，内容断言保持不变并独立报告。
4. `PBR BUG DONE`：默认与 DLSS 均通过未放宽的绿色纹理内容断言；修复隔离了
   CUTOUT 颜色基准与 WATER/玻璃材质覆盖场景。
5. `S5 DONE`：经用户明确允许与 panorama 红项解耦，已为
   `CyclesBridgeVulkanInteropState` 建立单结构 schema 原型；ABI 版本、公开 C 头和
   80-byte 布局均未改变。
6. `PANORAMA BUG DONE`：camera type 与 panorama subtype 变化均按 session 拓扑
   重建；默认与 DLSS render suite 全部通过。
7. `DENOISER TEST RACE DONE`：默认与 DLSS 均以确定性的 Raw/Still 两阶段协议通过
   OptiX/OIDN；DLSS realtime 路径也通过。生产 ABI 与 renderer 实现未修改。
8. `SCENE LIFECYCLE DONE`：默认与 DLSS 的 scene-lifecycle / dynamic resolution
   已在前置域全绿后实际执行通过，不再显示 Skipped。
9. `MINECRAFT CORE LIFECYCLE DONE`：与 `e3c8f1a` 内容相同的 DLSS 工作树已完成
   F8 启用首帧、持续移动、关闭、再启用、世界重进与退出验收；revision 153 最终
   发布且没有 fallback/failed。窗口 resize、DoF 与 HDR 仍按第 6 节保持独立
   `NOT RUN`，不得被本结论覆盖。
10. `FINAL HEAD AUTOMATION DONE`：提交后默认与 DLSS 两套完整 `verifyProject`
    均重新执行，Java build 和 6 个 native 域全部通过，没有 Skipped 或已知红项。
11. `ARCHITECTURE REFACTOR UNBLOCKED`：可以开始低风险、串行的责任治理。第一步
    只读核对残留 `Prototype` 类型的真实职责与调用方；确认命名边界后再做纯命名
    阶段。随后为 `CyclesClientConfig` 建立持久化、runtime snapshot 与 editor model
    的特征测试和拆分计划。`NativeBridge` 与 `cycles_engine.cpp` 继续后置。
12. `CONFIG RESPONSIBILITY DONE`：Draft、option model 与 catalog 已从持久化和
    runtime snapshot 门面中分离，配置 key、默认值、范围和 enum ID 保持。
13. `NATIVE BRIDGE RESPONSIBILITY DONE`：公开门面保持稳定；layouts、symbols、
    marshalling、decoding 与 native session ownership 已分离并通过双变体门禁。
14. `ENGINE RESPONSIBILITY DONE`：frame、interop、scene、camera、session config 和
    Vulkan binding 已分离；E6 双变体自动化与 Minecraft DLSS 生命周期验收通过。
15. `R0 INDEPENDENT REVIEW DONE`：已复核全部不少于 500 行的生产
    源文件、测试大文件、入口 public API、interop 字节/slot/所有权和 ABI schema
    能力；修正了首次 R0 的遗漏与阶段顺序。
16. `R0A DONE`：scene-lifecycle suite 已移入独立源文件；函数体机械移动，
    默认与 DLSS 完整门禁均通过，render/scene-lifecycle 无 Skipped。
17. `R0B NEXT`：将 native display driver 所需的 7 个可变引用收口为单一
    binding/shared-state 边界，并完成双变体与 Minecraft interop 生命周期验收。
18. `R1/R2 QUEUED`：R0B 结束后，先抽离客户端 renderer controller，
    再单独抽离 Vulkan allocation；每阶段执行双变体门禁和对应 Minecraft 里程碑。

新功能开发继续冻结；当前只允许按路线图 R0B、R1、R2、R5 串行收口。
