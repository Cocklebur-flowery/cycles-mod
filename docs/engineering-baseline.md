# Cycles Renderer 当前工程基线

状态：当前事实清单

检查日期：2026-08-14（Asia/Shanghai）

检查对象：S3 验证工作树（起点 `80afb23`；产品修复基线为 `fca5bd6`）

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

## 4. 2026-08-14 自动验证结果

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
| `cyclesrenderer_smoke_render` | `FAIL` / `KNOWN RED` | 5.41 秒后明确失败于绿色纹理内容断言 |
| `cyclesrenderer_smoke_denoiser` | `BLOCKED` / `SKIPPED` | 5.48 秒后由 render 前置失败返回 skip 77 |
| `cyclesrenderer_smoke_scene_lifecycle` | `BLOCKED` / `SKIPPED` | 5.29 秒后由 render 前置失败返回 skip 77 |
| `cyclesrenderer_scene_update` | `PASS` | 5.20 秒完成 |

CTest 总耗时 30.49 秒；6 项中 3 项通过、1 项失败、2 项跳过。

S4 表征运行确认 smoke 收到 2 个 `FRAME_UPDATED` 帧，最后 generation 为 18，
Combined pass 匹配，画面也有 RGB 变化。帧发布和 generation 游标正常；未满足的是
独立的绿色纹理内容断言。最接近绿色的像素为 `90,94,93`，绿色优势仅为 1，
不是原阈值 16 过严。该红项现在归类为初始场景几何、材质或纹理内容问题。

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
| `cyclesrenderer_smoke_render` | `FAIL` / `KNOWN RED` | 5.94 秒后明确失败于同一绿色纹理内容断言 |
| `cyclesrenderer_smoke_denoiser` | `BLOCKED` / `SKIPPED` | 5.86 秒后由 render 前置失败返回 skip 77 |
| `cyclesrenderer_smoke_scene_lifecycle` | `BLOCKED` / `SKIPPED` | 5.84 秒后由 render 前置失败返回 skip 77 |
| `cyclesrenderer_scene_update` | `PASS` | 5.84 秒完成 |

CTest 总耗时 33.52 秒；6 项中同样为 3 项通过、1 项失败、2 项跳过。

DLSS 表征运行同样收到 2 个 `FRAME_UPDATED` 帧，但没有绿色主导像素。
当前证据支持默认与 DLSS 共享的场景内容问题，不支持帧发布失败或 DLSS 独有故障。

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
| initial scene textured content / render | `KNOWN RED` | 两种变体均缺少预期绿色纹理内容；断言强度保持不变 |
| camera shift / autofocus / DoF / pass viewer | `BLOCKED` | 位于同一 render suite 的失败点之后 |
| panorama | `BLOCKED` | 未到达当前 panorama 循环；旧结果不替代本次结果 |
| denoiser | `BLOCKED` / `SKIPPED` | 独立 CTest 明确报告 render 前置失败 |
| scene lifecycle / dynamic resolution | `BLOCKED` / `SKIPPED` | 独立 CTest 明确报告 render 前置失败 |
| scene-update contract | `PASS` | 默认与 DLSS 独立 CTest 均通过 |

S3 已消除“一个 CTest 红项令后续领域完全无报告”的问题。S4 又将初始场景的帧到达
与内容验收拆成连续断言：该调用只等待 `FRAME_UPDATED`，随后由既有绿色纹理断言验证内容。
因此当前失败会在约 6 秒内准确报告，不再消耗约 135 秒并误报发布超时。真实场景内容
红项仍是 denoiser 与 scene-lifecycle 执行自身断言之前的前置阻塞。

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
4. `PBR BUG NEXT`：关闭初始场景缺少绿色纹理内容的已知红项，并复跑默认、DLSS
   完整验证入口；不得通过删除或放宽内容断言绕过。
5. `S5`：上述红项关闭后，为一个小型 interop 结构建立 ABI schema 生成原型。
6. 完成上述门禁后，才开始 `NativeBridge` 或 `cycles_engine.cpp` 的生产代码拆分。

任何新功能开发在上述稳定化阶段完成前继续冻结。
