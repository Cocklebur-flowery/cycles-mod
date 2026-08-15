# Cycles Renderer 当前工程基线

状态：当前事实清单

检查日期：2026-08-15（Asia/Shanghai）

检查对象：PBR 绿色纹理门禁复核（产品基线 `355ff77`）

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
| Vulkan interop | buffer/state 均为 80 bytes；state 的 depth dimensions 位于尾部 | Java layout 检查、C++ `static_assert` |
| Java 测试 | camera 自动曝光/对焦/射线/直方图与 LabPBR 资源测试 | `src/test/java` |
| 项目验证入口 | Gradle `verifyProject` 先执行 Java `build`，再运行所选 native 变体的全部 CTest | `build.gradle` |
| Native 测试入口 | CTest 注册 5 个独立 smoke 能力域与 `cyclesrenderer_scene_update` | `native/CMakeLists.txt` |
| Native smoke 结构 | 无参数入口保留完整顺序；CTest 通过 `--suite` 独立报告 contract、color、render、denoiser 与 scene-lifecycle | `native/tests/cycles_bridge_smoke*.cpp` |

跨语言 ABI 目前仍由 C 头结构、C++ size/offset 断言、Java `MemoryLayout` 和
smoke contract 多处人工维护。断言能够发现部分漂移，但尚不存在单一 schema
生成链。

## 3. 已收口的启动故障

| 项目 | 状态 | 证据 |
| --- | --- | --- |
| Minecraft 26.2 Mixin 启动崩溃 | `PASS` | `5830b16 fix(mixin): restore client startup on Minecraft 26.2` |
| backend 初始化原始异常被通用错误覆盖 | `PASS` | `3a4812b fix(native): preserve backend initialization errors` |
| DLSS runtime kernels 与开发安装树不同步 | `PASS` | `fca5bd6 fix(dev): synchronize DLSS runtime kernels`；用户确认原故障已修复 |

`run/logs/latest.log` 对应的客户端在 18:06 启动，而最后两个修复提交在 18:07
形成。该日志可以证明 Native bridge 曾进入 OptiX/scene staging，但不能作为
最终产品修复基线的一次干净、提交后实机验收。

## 4. 2026-08-14 自动验证与 2026-08-15 聚焦复核

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
| `cyclesrenderer_smoke_contract` | `PASS` | 4.58 秒完成 |
| `cyclesrenderer_smoke_color` | `PASS` | 4.51 秒完成 |
| `cyclesrenderer_smoke_render` | `FAIL` / `KNOWN RED` | 绿色纹理断言通过；随后在 `panorama 0` 帧发布等待超时 |
| `cyclesrenderer_smoke_denoiser` | `BLOCKED` / `NOT RUN` | 本次聚焦 render 复核未独立执行 |
| `cyclesrenderer_smoke_scene_lifecycle` | `BLOCKED` / `NOT RUN` | 本次聚焦 render 复核未独立执行 |
| `cyclesrenderer_scene_update` | `PASS` | 5.20 秒完成 |

2026-08-15 聚焦复核确认绿色断言已通过，render suite 继续执行 camera shift、
autofocus、DoF 和 pass viewer 后，约 142 秒在独立的 `panorama 0` 等待处超时。
绿色断言阈值没有删除或放宽。

根因是 transmission smoke 扩展把第二个初始平面三角形改成 WATER，却继续把这张
混合材质画面作为纯 CUTOUT 绿色纹理基准。修复仅恢复首帧两个三角形均使用 CUTOUT；
WATER 和玻璃材质仍在同一资源重置中创建并接受合法性校验。

### 4.3 Experimental DLSS Native 变体

命令：

```text
run-client.cmd verifyProject -PexperimentalDlss=true --rerun-tasks --console=plain
```

| 领域 | 状态 | 结果 |
| --- | --- | --- |
| Native configure/build | `PASS` | DLSS Release DLL、smoke、scene-update 目标构建成功 |
| `cyclesrenderer_smoke_contract` | `PASS` | 4.93 秒完成 |
| `cyclesrenderer_smoke_color` | `PASS` | 5.10 秒完成 |
| `cyclesrenderer_smoke_render` | `FAIL` / `KNOWN RED` | 绿色纹理断言通过；随后在 `panorama 0` 帧发布等待超时 |
| `cyclesrenderer_smoke_denoiser` | `BLOCKED` / `NOT RUN` | 本次聚焦 render 复核未独立执行 |
| `cyclesrenderer_smoke_scene_lifecycle` | `BLOCKED` / `NOT RUN` | 本次聚焦 render 复核未独立执行 |
| `cyclesrenderer_scene_update` | `PASS` | 5.84 秒完成 |

2026-08-15 DLSS 聚焦复核得到相同结果：绿色断言通过，后续相机、DoF 与 pass
viewer 场景均执行，约 145 秒在独立的 `panorama 0` 等待处超时。因此绿色纹理
门禁已在默认和 DLSS 两种 native 变体关闭；全景超时是下一项独立红项。

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
| panorama | `KNOWN RED` | 默认与 DLSS 均在 `panorama 0` 帧发布等待超时 |
| denoiser | `BLOCKED` / `NOT RUN` | 本次未越过 panorama 前置，也未独立复跑 |
| scene lifecycle / dynamic resolution | `BLOCKED` / `NOT RUN` | 本次未越过 panorama 前置，也未独立复跑 |
| scene-update contract | `PASS` | 默认与 DLSS 独立 CTest 均通过 |

S3 已消除“一个 CTest 红项令后续领域完全无报告”的问题。S4 又将初始场景的帧到达
与内容验收拆成连续断言。PBR 复核保持该内容断言原样，并修正了测试场景材质职责；
当前最早的 render 红项已推进到 `panorama 0` 帧发布超时。

## 6. 游戏内验证状态

| 场景 | 状态 | 说明 |
| --- | --- | --- |
| 原 backend/DLSS kernel 故障 | `PASS` | 用户确认修复 |
| 最终 HEAD 干净启动 | `NOT RUN` | 尚无一次明确在最终提交之后启动的留档 |
| F8 启用并产生真实世界首帧 | `NOT RUN` | 最新日志跨越修复提交时间，不能用于最终验收 |
| F8 关闭并恢复原版 | `NOT RUN` | 本基线未执行 |
| F8 再次启用 | `NOT RUN` | 本基线未执行 |
| resize / 动态分辨率 | `NOT RUN` | 本基线未执行 |
| Physical / Post-process DoF | `NOT RUN` | 本基线未执行 |
| SDR / HDR / screenshot fallback | `NOT RUN` | 本基线未执行完整矩阵 |
| 默认 / DLSS 持续移动稳定性 | `NOT RUN` | 本基线未执行 |

这些项目必须通过实际客户端验证关闭，不能由 Java、Native 编译或 320x180
smoke ready frame 推断。

## 7. 当前架构热点与保护边界

下列文件不是因为行数本身被判定为问题，而是已经跨越多个职责或生命周期：

- `native/src/cycles_engine.cpp`：session、worker、frame store、display driver、
  Vulkan interop、scene/material/camera 构建和设置失效编排集中。
- `src/main/java/dev/cyclesrenderer/nativebridge/NativeBridge.java`：ABI 常量、
  layouts、symbols、marshalling、session state、DTO 与诊断解码集中。
- `src/main/java/dev/cyclesrenderer/config/CyclesClientConfig.java`：持久化、
  runtime snapshot 与 editor model 边界仍需治理。

在完成对应特征测试和拆分计划之前，只允许在这些文件中进行必要修复或薄接线，
不得继续加入新的独立职责。

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
5. `PANORAMA BUG NEXT`：定位并关闭默认、DLSS 共同的 `panorama 0` 帧发布超时。
6. `S5`：上述红项关闭后，为一个小型 interop 结构建立 ABI schema 生成原型。
7. 完成上述门禁后，才开始 `NativeBridge` 或 `cycles_engine.cpp` 的生产代码拆分。

任何新功能开发在上述稳定化阶段完成前继续冻结。
