# Cycles Renderer Prototype

这是一个面向 Minecraft 26.2 / NeoForge 26.2 的实验性客户端 MOD。Minecraft 本身使用 Vulkan 后端；MOD 将世界快照交给 Blender Cycles 渲染，并把 Cycles 输出的 RGBA 帧上传到 Minecraft 的 Vulkan 主渲染目标。

当前实现优先选择 OptiX，失败后依次回退到 CUDA 和 CPU。F8 用于启用或关闭实验渲染器，F9 打开 Cycles 设置页，F10 切换诊断叠加层；任何 native 错误都会关闭实验路径并恢复原版世界渲染。

## 当前阶段

- Cycles 5.2 已通过 C ABI v11 接入 Java Foreign Function & Memory API；v11 使用 Cycles DisplayDriver 保存 scene-linear RGBA16F，并保留 v10 的独立相机更新。
- 近景不再扫描固定 `64 × 32 × 64` 方块盒，而是直接复制 Minecraft `SectionCompiler` 生成的 16³ Section 网格。
- 活跃 Section 范围跟随游戏的“有效渲染距离”；水平判定复用原版区块距离规则，垂直范围复用原版渲染器的 Section 范围和世界高度边界。
- 方块放置/破坏、光照更新、区块装卸和视距移动触发原版 Section 重编译后，会以稳定 Section ID 增量替换或移除 Native 几何。
- 数据源包含原版方块、流体层和 NeoForge `AddSectionGeometryEvent` 追加到 Section 网格中的几何，因此比自行解析方块 JSON 更接近最终 MC 画面，也能覆盖一部分遵守标准区块渲染入口的 MOD。
- Cycles 材质当前支持共享方块图集、顶点色、Opaque、Cutout Alpha Clip 和基础 Translucent Alpha Blend；纹理 V 坐标使用 Minecraft 编译网格原值。
- 资源包重载会重建共享图集并让原版渲染器重新编译 Section，不复用旧 Sprite 像素。
- Distant Horizons 3.2.1 / API 7 的可选 Provider 骨架仍保留，但当前活动 Section 流送路径不合并其高度场；可靠远景、接缝和质量留待后续兼容阶段。
- Cycles 在自己的会话线程中渐进渲染；普通 Section 变化在现有 Session 内更新对应 Mesh/Object，并在更新期间继续显示上一张可用帧，不再主动回填蓝色测试帧。
- NeoForge CLIENT 配置持久化到 `config/cyclesrenderer-client.toml`。默认仍为 Fit Inside `480 × 270`、移动 1 sample、静止 150 ms 后 8 samples；可以在 F9/模组配置页改成固定分辨率，最高 `3840 × 2160`，包括 `1920 × 1080`。
- 设置页开放设备策略、分辨率、交互/静止采样、自适应采样、时间限制、光程反弹、Clamp、像素过滤、种子、降噪、EV、Gamma、查看变换、活动 Pass 和诊断开关。设置按 revision 异步提交给 Cycles 工作线程。
- Pass 查看器按需创建并显示 `Combined`、`Depth`、`Normal`、`Diffuse Color`、`Emission`、`Roughness` 和 `Sample Count`，不会同时把所有 Pass 复制到 Java/Vulkan。
- 运行时能力查询会报告实际设备、可用 Pass 和降噪器。当前构建已验证 RTX 5080 上的 OptiX 降噪；OpenImageDenoise 仍未编入 Cycles 静态库，因此会报告不可用。
- Java 每次只上传 Native 的低分辨率新帧；Minecraft Vulkan 使用全屏三角形在 GPU 上最近邻放大到主渲染目标，不再在 CPU 上生成 4K RGBA 临时帧。
- Minecraft 的 Vulkan swapchain、纹理和命令编码器仍由 Minecraft 管理；Cycles 不使用 Vulkan。

当前显示缓存仍是 RGBA8，不是可复用的线性 HDR Pass 缓存。`Standard` 和调试用 `Raw` 已生效，EV/Gamma 在输出转换时应用；`AgX` 与 `Khronos PBR Neutral` 的配置 ID 已预留，但在 Blender OCIO 配置/LUT 正式部署前按 `Standard` 显示，不能视为 Blender 色彩管理已经完成。暂未实现 OpenImageDenoise、正确的玻璃/水折射材质、方块实体、实体、天空系统、动态光源、LabPBR 和跨平台打包。

普通 Section 修改已经下沉到现有 Cycles Scene；但几何规模变化以及 Section 增删仍会让 Cycles 更新设备几何和加速结构，不能视为零成本局部 BVH 更新。共享图集、场景原点或设备变化仍会重建 Session。

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

`runNativeSmoke` 会构造一个带 UV、彩色纹理与 Alpha Clip 的小型网格场景，验证 ABI v11 的 RGBA16F DisplayDriver、独立相机更新、能力/诊断、实际/目标 sample、帧与场景更新计数、全部 7 个 Pass、Combined 恢复、检测到的 OptiX 降噪，以及 Section 创建、修改和删除，然后输出实际后端、设备、分辨率和帧校验和。

`runClient` 只会为启动出的 Minecraft 进程把 `build/native/bin/` 加入 `PATH`，使 Windows 能找到 Cycles 的二级 DLL 依赖；它不会修改系统或用户环境变量。修改 native 运行时文件后必须重新启动客户端。

## 运行时数据流

```text
Minecraft SectionCompiler（16³ Section、流体、NeoForge 追加几何）
  -> SectionCompilerMixin 在 MeshData 关闭前复制 CPU 顶点
  -> SectionGeometryCollector（按 Section ID 合并最新重编译结果）
  -> SectionSceneManager（原版视距、装卸、资源代次、批量提交）
  -> NativeBridge（ABI v11：共享资源 + Section 流送 + RGBA16F DisplayDriver + 独立相机更新 + 遥测）
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

- `CyclesRendererMod.java`：F8 生命周期、F9/F10 入口、设置提交、Section 场景更新和 Vulkan 展示。
- `CyclesClientConfig.java` / `CyclesRenderSettings.java`：NeoForge CLIENT 配置、稳定枚举 ID 和不可变设置快照。
- `CyclesSettingsScreen.java`：设置总览、NeoForge 配置页入口、Pass 快捷切换与能力检测。
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

## 游戏内设置与诊断

- F8：启用或关闭 Cycles 世界帧；关闭后恢复原版世界渲染。
- F9：打开 Cycles 总览页。`编辑全部 Cycles 设置` 进入 NeoForge 自动生成的完整客户端配置页；模组列表的 Config 按钮进入同一总览页。
- F10：切换诊断叠加层，显示 ABI、实际设备/降噪器、native 状态、内部尺寸、活动 Pass、sample、revision、Section 数和最近 reset 等级。
- 总览页的 Pass 按钮可依次查看 7 个已实现 Pass；修改完整配置后回到游戏会按 reset 等级异步更新，不要求重启客户端。
- `AUTO` 设备策略保持 OptiX → CUDA → CPU；显式选择某设备时不会悄悄改用另一类设备。降噪器的“配置值”“编译能力”“设备能力”和“当前实际生效值”分别记录，F9/F10 显示的是 native 查询结果。

## ABI 与运行时约束

ABI v11 保留旧的整场景入口、v5 Section 布局和 v6 设置/能力字段；`CyclesBridgeRenderSettings` 固定 208 字节、`CyclesBridgeCapabilities` 固定 64 字节，`CyclesBridgeDiagnostics` 固定 240 字节。v7 字段报告实际/目标 sample、采样状态和 sample/s；v8 追加帧管线统计；v9 追加场景更新时间；v10 新增 `update_camera`；v11 将 Cycles 交互输出切换为 scene-linear `half4`，并利用原 reserved 尾字段报告内部像素格式。`render_frame` 暂时把 half4 通过预计算查找表兼容转换为 RGBA8；P6/P7 将让 Java/Vulkan 直接消费 RGBA16F。Java 与 DLL 的 ABI 版本不一致时会在启用前拒绝运行。

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
5. 进入世界按 F9，确认完整配置页可打开；选择 Fixed `1920 × 1080` 后返回游戏，再按 F10 确认 native 尺寸和设置 revision 更新。
6. 按 F8 启用，确认日志/叠加层显示实际后端；依次切换 Combined、Depth、Normal、Diffuse Color、Emission、Roughness 和 Sample Count。
7. 若能力页显示 OptiX 降噪可用，选择 OptiX 并确认 F10 的实际降噪器为 OptiX；当前 OIDN 应显示不可用。
8. 确认方块纹理上下方向正确，左右/上下转动及前后移动方向正确。
9. 连续破坏和放置方块，确认对应 Section 更新；改变游戏视距并移动跨区块，确认范围随之变化且不出现蓝色清屏。
10. 按 F8 恢复原版；退出并重新进入客户端，确认 CLIENT 配置仍然保留。

每个完成的代码阶段单独创建一次本地 Git 提交。不要提交 `.deps/`、`build/`、`run/` 或 `.tools/`。
