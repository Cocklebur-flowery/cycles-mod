# Cycles Renderer Prototype

这是一个面向 Minecraft 26.2 / NeoForge 26.2 的实验性客户端 MOD。Minecraft 本身使用 Vulkan 后端；MOD 将世界快照交给 Blender Cycles 渲染，并把 Cycles 输出的 RGBA 帧上传到 Minecraft 的 Vulkan 主渲染目标。

当前实现优先选择 OptiX，失败后依次回退到 CUDA 和 CPU。F8 用于启用或关闭实验渲染器，任何 native 错误都会关闭实验路径并恢复原版世界渲染。

## 当前阶段

- Cycles 5.2 已通过 C ABI v5 接入 Java Foreign Function & Memory API。
- 近景不再扫描固定 `64 × 32 × 64` 方块盒，而是直接复制 Minecraft `SectionCompiler` 生成的 16³ Section 网格。
- 活跃 Section 范围跟随游戏的“有效渲染距离”；水平判定复用原版区块距离规则，垂直范围复用原版渲染器的 Section 范围和世界高度边界。
- 方块放置/破坏、光照更新、区块装卸和视距移动触发原版 Section 重编译后，会以稳定 Section ID 增量替换或移除 Native 几何。
- 数据源包含原版方块、流体层和 NeoForge `AddSectionGeometryEvent` 追加到 Section 网格中的几何，因此比自行解析方块 JSON 更接近最终 MC 画面，也能覆盖一部分遵守标准区块渲染入口的 MOD。
- Cycles 材质当前支持共享方块图集、顶点色、Opaque、Cutout Alpha Clip 和基础 Translucent Alpha Blend；纹理 V 坐标使用 Minecraft 编译网格原值。
- 资源包重载会重建共享图集并让原版渲染器重新编译 Section，不复用旧 Sprite 像素。
- Distant Horizons 3.2.1 / API 7 的可选 Provider 骨架仍保留，但当前活动 Section 流送路径不合并其高度场；可靠远景、接缝和质量留待后续兼容阶段。
- Cycles 在自己的会话线程中渐进渲染；普通 Section 变化在现有 Session 内更新对应 Mesh/Object，并在更新期间继续显示上一张可用帧，不再主动回填蓝色测试帧。
- 内部渲染分辨率上限为 `480 × 270`；移动时使用 1 sample，静止 150 ms 后提高到 8 samples。
- Java 每次只上传 Native 的低分辨率新帧；Minecraft Vulkan 使用全屏三角形在 GPU 上最近邻放大到主渲染目标，不再在 CPU 上生成 4K RGBA 临时帧。
- Minecraft 的 Vulkan swapchain、纹理和命令编码器仍由 Minecraft 管理；Cycles 不使用 Vulkan。

暂未实现正确的玻璃/水折射材质、方块实体、实体、天空系统、动态光源、LabPBR、降噪和跨平台打包。普通 Section 修改已经下沉到现有 Cycles Scene；但几何规模变化以及 Section 增删仍会让 Cycles 更新设备几何和加速结构，不能视为零成本局部 BVH 更新。共享图集、场景原点或设备变化仍会重建 Session。

游戏内验收中，方块和区块更新造成的约 0.4 秒停顿已大幅缓解，仍可能出现约 0.1 秒顿卡。该问题保留到基础功能跑通后的性能热点分析阶段处理。

## 下一里程碑规划

当前里程碑分单元实现通用静态方块数据桥、Distant Horizons LOD Provider、Cycles HDR/采样/降噪/OpenColorIO/多通道核心以及游戏内设置界面。实现范围、兼容性边界、稳定协议、风险和验收标准见：

- [渲染数据桥与 Cycles 画面控制里程碑](docs/render-bridge-and-settings-plan.md)

规划已经确认并进入实施；每个可独立验证的单元完成后创建一个本地 Git 提交。

## 开发环境

固定版本和工具：

- Minecraft `26.2`
- NeoForge `26.2.0.58`
- Gradle `9.2.1`
- Java toolchain `25`（`run-client.cmd` 可使用 JDK 17 启动 Gradle）
- Visual Studio 2022 C++ x64 工具链
- CMake 3.25 或更高版本
- Cycles `v5.2.0`，提交 `3b97e190c5ff1a2ed2160d879ad5bf95bea7b8ba`
- Blender Windows libraries，提交 `60d6e96b917568278d400a4024c98da0fb777338`
- CUDA 13.3、OptiX 9.1

`.deps/`、`.tools/`、Gradle 缓存、构建输出和游戏运行目录均不进入 Git。

## 首次准备 Cycles

在 PowerShell 中执行：

```powershell
cd E:\MCservers\MClife_client\cycles-mod
powershell -ExecutionPolicy Bypass -File .\scripts\setup-cycles.ps1
```

脚本会校验固定提交，只下载构建所需的 Windows LFS 目录，并生成：

```text
.deps/cycles/          Cycles 源码与 Windows 预编译依赖
.deps/cycles-build/    Cycles Release 静态库
.deps/cycles-install/  运行时 DLL、OptiX/CUDA 内核和 cycles.exe
```

依赖已经存在时不需要重复执行。`buildNative` 只验证并使用这些产物，不会每次重新下载 36 GB 仓库。

## 常用命令

项目提供本地 Gradle 启动脚本：

```bat
run-client.cmd setup
run-client.cmd buildNative
run-client.cmd runNativeSmoke
run-client.cmd runClient
```

也可以直接使用已经可用的 Gradle：

```powershell
.\gradlew.bat buildNative
.\gradlew.bat runNativeSmoke
.\gradlew.bat runClient
```

`buildNative` 会构建 native DLL 和冒烟程序，并把 `.deps/cycles-install` 中的运行时 DLL 与 `lib/kernel_*.zst` 自动部署到 `build/native/bin/`。不需要手工复制 JAR、DLL 或 GPU 内核。

`runNativeSmoke` 会构造一个带 UV、彩色纹理与 Alpha Clip 的小型网格场景，等待真实 Cycles 帧并输出所选后端、设备、分辨率和帧校验和。

`runClient` 只会为启动出的 Minecraft 进程把 `build/native/bin/` 加入 `PATH`，使 Windows 能找到 Cycles 的二级 DLL 依赖；它不会修改系统或用户环境变量。修改 native 运行时文件后必须重新启动客户端。

## 运行时数据流

```text
Minecraft SectionCompiler（16³ Section、流体、NeoForge 追加几何）
  -> SectionCompilerMixin 在 MeshData 关闭前复制 CPU 顶点
  -> SectionGeometryCollector（按 Section ID 合并最新重编译结果）
  -> SectionSceneManager（原版视距、装卸、资源代次、批量提交）
  -> NativeBridge（ABI v5：共享资源 + Section upsert/remove/commit）
  -> CyclesEngine 后台场景请求
  -> 现有 Cycles Session 内按 Section ID 新建、原地更新或删除 Mesh/Object
  -> 共享图集与 Opaque/Cutout/Blend 材质
  -> Cycles Session（OptiX -> CUDA -> CPU）
  -> OutputDriver 最新渐进帧
  -> 低分辨率 RGBA8 Vulkan 纹理
  -> Minecraft Vulkan 全屏三角形 GPU 放大
```

场景变更先在 Java/Native 暂存区按 Section ID 合并。首批 Section 等待 750 ms 安静窗口后提交，后续更新等待 100 ms，不再用固定最大间隔强制提交半成品场景。每帧最多处理 24 个 Section 且限制约 4 ms Java 上传预算。Native 复用未变化 Section 的节点、原地改写已变化 Mesh，并只为新增/卸载 Section 创建或删除节点；相机位置、朝向、FOV 或输出尺寸变化时请求交互帧，静止后请求更高采样帧。

DH Provider 代码仍隔离保留，但本阶段不再把其低模高度场合并进活动近景场景。此前游戏测试未得到可靠远景，完整 DH 可见距离、接缝和质量继续作为后续兼容工作，不影响本阶段原版 Section 流送。

## 代码入口

- `CyclesRendererMod.java`：F8 生命周期、Section 场景更新和 Vulkan 展示。
- `SectionCompilerMixin.java` / `SectionGeometryCollector.java`：复制原版已编译 Section 网格并合并重复重编译。
- `SectionSceneManager.java`：视距、世界/资源代次、区块卸载、增量缓存和提交节奏。
- `CyclesFramePresenter.java`：低分辨率帧纹理与 Vulkan GPU 全屏放大；没有已上传纹理时不会用 Native 的旧 ready 标记遮住原版世界。
- `ClientRenderSnapshot.java`：旧固定范围采集实现，当前活动路径不再使用，保留作过渡参考。
- `DistantHorizonsSceneProvider.java`：反射检测 DH API 7，在后台读取 Terrain Repo 并发布不可变远景高度场。
- `NativeBridge.java`：Java 25 FFM 布局、native 生命周期和 ABI 校验。
- `native/include/cycles_bridge.h`：稳定 C ABI；修改结构、状态码或函数时必须同步升级 Java ABI。
- `native/src/cycles_bridge.cpp`：C ABI 参数校验和错误边界。
- `native/src/cycles_engine.cpp`：设备选择、后台 Session、Section 节点增量更新、网格/材质、相机和帧交换。
- `native/tests/cycles_bridge_smoke.cpp`：不启动 Minecraft 的 Native 端到端测试，覆盖 Section 首次创建、原地修改和删除。
- `scripts/setup-cycles.ps1`：固定版本的依赖获取与 Cycles 构建。

## ABI 与运行时约束

ABI v5 保留旧的整场景入口供兼容和诊断，并新增 `CyclesBridgeSceneResources`、`CyclesBridgeSection` 和 `CyclesBridgeFrame`。共享材质/图集由 `reset_scene` 设置；区块通过 `upsert_section`、`remove_section` 进入暂存区，再由 `commit_scene` 原子发布。`render_frame` 只在帧 generation 改变时复制低分辨率像素。Java 与 DLL 的 ABI 版本不一致时会在启用前拒绝运行。

Cycles 的 GPU 内核通过 `path_init()` 相对于 `cyclesrenderer_native.dll` 查找。因此以下布局是运行时契约：

```text
build/native/bin/
  cyclesrenderer_native.dll
  OpenImageIO.dll 等运行时 DLL
  lib/
    kernel_optix.ptx.zst
    kernel_optix_mnee.ptx.zst
    kernel_optix_shader_raytrace.ptx.zst
    kernel_sm_120.cubin.zst
```

不要把 `.deps/cycles-build/lib/Release` 当作运行时目录；其中是链接阶段使用的静态库。

## 修改后的验证顺序

1. `run-client.cmd buildNative`
2. `run-client.cmd runNativeSmoke`
3. `run-client.cmd build`
4. `run-client.cmd runClient`
5. 进入世界按 F8，确认日志显示 `backend=OPTIX`，等待 Section 逐步出现。
6. 确认方块纹理上下方向正确，左右/上下转动及前后移动方向正确。
7. 连续破坏和放置方块，确认对应 Section 更新；改变游戏视距并移动跨区块，确认范围随之变化且不出现蓝色清屏。
8. 确认日志中的 Native 帧不超过 `480 × 270`，静止后 sample 数由 1 提升到 8，再按 F8 可恢复原版。

每个完成的代码阶段单独创建一次本地 Git 提交。不要提交 `.deps/`、`build/`、`run/` 或 `.tools/`。
