# Cycles Renderer Prototype

这是一个面向 Minecraft 26.2 / NeoForge 26.2 的实验性客户端 MOD。Minecraft 本身使用 Vulkan 后端；MOD 将世界快照交给 Blender Cycles 渲染，并把 Cycles 输出的 RGBA 帧交给 Minecraft 的 Vulkan 主渲染目标。

当前实现优先选择 OptiX，失败后依次回退到 CUDA 和 CPU。F8 用于启用或关闭实验渲染器，F9 打开 Cycles 设置页，F10 切换诊断叠加层；任何 native 错误都会关闭实验路径并恢复原版世界渲染。

## 当前阶段

- Cycles 5.2 已通过 C ABI v36 接入 Java Foreign Function & Memory API；默认路径保留 RGBA16F FrameStore/FFM 回退，P16 路径让 OptiX/CUDA 直接写入三槽 Vulkan 外部 buffer，并用两条 Win32 timeline semaphore 与 Minecraft Vulkan 复制建立 GPU 端握手。
- 近景不再扫描固定 `64 × 32 × 64` 方块盒，而是直接复制 Minecraft `SectionCompiler` 生成的 16³ Section 网格。
- 活跃 Section 范围跟随游戏的“有效渲染距离”；水平判定复用原版区块距离规则，垂直范围复用原版渲染器的 Section 范围和世界高度边界。
- 方块放置/破坏、光照更新、区块装卸和视距移动触发原版 Section 重编译后，会以稳定 Section ID 增量替换或移除 Native 几何。
- 数据源包含原版方块、流体层和 NeoForge `AddSectionGeometryEvent` 追加到 Section 网格中的几何，因此比自行解析方块 JSON 更接近最终 MC 画面，也能覆盖一部分遵守标准区块渲染入口的 MOD。
- Cycles 材质当前支持共享方块图集、顶点色、Opaque、Cutout Alpha Clip 和基础 Translucent Alpha Blend；纹理 V 坐标使用 Minecraft 编译网格原值。
- 资源包重载会重建共享图集并让原版渲染器重新编译 Section，不复用旧 Sprite 像素。
- Distant Horizons 兼容近期明确延后；主编译源不保留未接入活动 Section 流送的旧 Provider/快照骨架。未来恢复时需针对当时受支持的 API 重新设计远近景所有权和接缝。
- Cycles 在自己的会话线程中渐进渲染；普通 Section 变化在现有 Session 内改写固定拓扑 Mesh 槽位并走动态 BVH refit，Section 换入换出优先复用退化隐藏的驻留槽位。更新期间继续显示上一张可用帧，不再主动回填蓝色测试帧。
- NeoForge CLIENT 配置持久化到 `config/cyclesrenderer-client.toml`。默认仍为 Fit Inside `480 × 270`、移动 1 sample、静止 150 ms 后 8 samples；可以在 F9/模组配置页改成固定分辨率，最高 `3840 × 2160`，包括 `1920 × 1080`。可选动态分辨率默认关闭；开启后交互/Settling 阶段使用独立百分比，静止阶段恢复基础输出尺寸。
- 设置页开放设备策略、分辨率、交互/静止采样、自适应采样、时间限制、光程反弹、Clamp、像素过滤、种子、相机、程序大气、降噪、EV、Gamma、查看变换、活动 Pass 和诊断开关。设置按 revision 异步提交给 Cycles 工作线程。
- World 背景已使用 Cycles 原生 Multiple Scattering Sky Texture；F9 可调太阳圆盘、角直径、强度、高度、旋转、海拔和空气/气溶胶/臭氧密度，默认太阳高度为 45°。大气改参会受控重建 Native Cycles Session，但复用内存中的资源和 Section 快照，不重新触发 Minecraft 区块捕获。
- Pass 查看器按需创建并显示 `Combined`、`Depth`、`Normal`、`Diffuse Color`、`Emission`、`Roughness` 和 `Sample Count`，不会同时把所有 Pass 复制到 Java/Vulkan。
- Native 以 `(Pass ID, Raw/Denoised)` 缓存最近查看过的 RGBA16F Pass，默认 LRU 预算为 256 MiB；F10 显示条目、占用、命中、淘汰、注册表和活动 descriptor。Pass 只在首次访问时加入注册 mask；当前每次切换仍重建 Cycles Session，以规避 Cycles 5.2 DisplayDriver 原地切换停在 `0/1` sample 的问题。
- 运行时能力查询会报告实际设备、可用 Pass 和降噪器。当前构建已在 RTX 5080 上分别验证 OptiX 与 OpenImageDenoise 2.5；移动/Settling 阶段输出 Raw，只有静止 Combined 才实际调度所选降噪器并写入 Denoised cache。
- 默认情况下 Java 每次只租用并上传 Native 的低分辨率 RGBA16F 新帧；启用启动级互操作开关后，OptiX/CUDA 直接写 Vulkan 外部 buffer，Minecraft 在 GPU 内复制到显示纹理，不再发生每帧 GPU→CPU→GPU 像素搬运。
- Minecraft 仍拥有 Vulkan swapchain、纹理和命令提交；Cycles 不是 Vulkan 渲染后端，只使用 Cycles 自带的 CUDA/Vulkan 显示互操作入口。

当前 Native DisplayDriver、类型化 Pass cache 与 Minecraft 上传纹理均保持 scene-linear RGBA16F。`Standard` 和调试用 `Raw` 已生效，EV/Gamma 在 GPU 显示 shader 中应用；`AgX` 与 `Khronos PBR Neutral` 的配置 ID 已预留，但在 Blender OCIO 配置/LUT 正式部署前按 `Standard` 显示，不能视为 Blender 色彩管理已经完成。暂未实现正确的玻璃/水折射材质、方块实体、实体、Minecraft 时间/天气驱动的大气与体积天气、动态光源、LabPBR 和跨平台打包。

P16 会在驱动支持时默认为 Minecraft Vulkan 设备请求 Win32 external memory/semaphore 扩展。故障排查时可在启动前加入 JVM 参数 `-Dcyclesrenderer.experimentalVulkanInterop=false`，或设置环境变量 `CYCLESRENDERER_VULKAN_INTEROP=false` 来显式关闭；该选择必须在创建 Vulkan 设备前确定，因此修改后需要重启客户端。共享分配容量跟随 F9 输出设置，内部由三个等距 RGBA16F 槽组成；CUDA 与 Vulkan 分别通过 ready/release timeline semaphore 等待对方，不再依赖逐帧 CUDA 主机同步或 Vulkan fence 等待来保证像素可见性。

普通 Section 修改已经下沉到现有 Cycles Scene，并在预留容量内保持三角形和 Object 拓扑不变，使 OptiX BLAS/TLAS 走动态 refit。Section 增删复用驻留槽位；只有网格超过槽位容量才回退为真正拓扑重建。共享图集、场景原点或设备变化仍会重建 Session。

此前遥测确认剩余卡顿主要位于 Cycles device geometry/BVH；固定拓扑 refit 已实现，实际改善仍需在同一世界分别用单方块更新和连续跑图复测性能 JSON。

## 下一里程碑规划

当前里程碑分单元实现通用静态方块数据桥、Cycles HDR/采样/降噪/OpenColorIO/多通道核心以及游戏内设置界面；Distant Horizons 兼容不在近期实施范围。实现范围、兼容性边界、稳定协议、风险和验收标准见：

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
- OpenImageDenoise `2.5.0`（来自上述固定 Windows 依赖树）
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
run-client.cmd runNativeTests
run-client.cmd runClient
```

也可以直接使用已经可用的 Gradle：

```powershell
.\gradlew.bat buildNative
.\gradlew.bat runNativeSmoke
.\gradlew.bat runNativeTests
.\gradlew.bat runClient
```

`buildNative` 会构建 native DLL 和冒烟程序，并把 `.deps/cycles-install` 中的通用运行时 DLL、OIDN 主库/core/CPU/CUDA 插件与 `lib/kernel_*.zst` 自动部署到 `build/native/bin/`。不需要手工复制 JAR、DLL 或 GPU 内核；面向 NVIDIA 的构建不会部署 OIDN HIP/SYCL 插件。

`runNativeSmoke` 会构造一个带 UV、彩色纹理与 Alpha Clip 的小型网格场景，验证 ABI v36 的 RGBA16F DisplayDriver、CPU 帧租约、三槽 Vulkan interop 描述与三个 Win32 HANDLE 的所有权状态、独立相机更新、能力/诊断、实际/目标 sample、Interactive/Settling/Still、动态分辨率尺寸跃迁、全部 7 个 Pass descriptor/按需注册、Raw/Denoised Pass cache、OptiX 与 OIDN 各自的 Interactive Raw/Still Denoised 调度，以及 Section 创建、修改和删除，然后输出实际后端、设备、分辨率和帧校验和。真正的 CUDA/Vulkan timeline wait/signal 与图像复制仍需在 Minecraft 中实机验收。

`runNativeTests` 通过 CTest 执行当前 CMake 注册的全部 Native 测试，包括完整渲染 smoke 和独立的 Section 更新/公共 ABI 集成测试。`buildNative` 只证明这些目标可以编译；需要用 `runNativeTests` 证明它们实际执行通过。

`runClient` 只会为启动出的 Minecraft 进程把 `build/native/bin/` 加入 `PATH`，使 Windows 能找到 Cycles 的二级 DLL 依赖；它不会修改系统或用户环境变量。修改 native 运行时文件后必须重新启动客户端。

## 运行时数据流

```text
Minecraft SectionCompiler（16³ Section、流体、NeoForge 追加几何）
  -> SectionCompilerMixin 在 MeshData 关闭前复制 CPU 顶点
  -> SectionGeometryCollector（按 Section ID 合并最新重编译结果）
  -> SectionSceneManager（原版视距、装卸、资源代次、批量提交）
  -> NativeBridge（ABI v36：Section 流送 + RGBA16F DisplayDriver + CPU 帧租约/三槽 Vulkan timeline interop + Pass cache/registry + 降噪调度）
  -> CyclesEngine 后台场景请求
  -> 现有 Cycles Session 内按 Section ID 更新或复用固定拓扑 Mesh/Object 槽位
  -> 共享图集与 Opaque/Cutout/Blend 材质
  -> Cycles Session（OptiX -> CUDA -> CPU）
  -> DisplayDriver 三槽 scene-linear RGBA16F 最新渐进帧
  -> FFM acquire/release + RGBA16F Vulkan 纹理
  -> Minecraft Vulkan 全屏三角形 GPU 显示变换与放大
```

场景变更先在 Java/Native 暂存区按 Section ID 合并。首批 Section 等待 750 ms 安静窗口后提交，后续更新等待 100 ms，不再用固定最大间隔强制提交半成品场景。每帧最多处理 24 个 Section 且限制约 4 ms Java 上传预算。Native 复用未变化 Section；已变化 Mesh 在预留容量内只改属性并 refit BVH，卸载槽位写成退化三角形后供新区块复用，容量溢出才重建拓扑。相机位置、朝向、FOV 或输出尺寸变化时请求交互帧，静止后请求更高采样帧。

DH 兼容已从当前主编译源移除。此前实验没有得到可靠远景，后续若恢复兼容，将从当时受支持的公开 API 和当前 Section 流送边界重新设计，不复用失活骨架。

## 代码入口

- `CyclesRendererMod.java`：F8 生命周期、F9/F10 入口、设置提交、Section 场景更新和 Vulkan 展示。
- `CyclesClientConfig.java` / `CyclesRenderSettings.java`：NeoForge CLIENT 配置、稳定枚举 ID 和不可变设置快照。
- `CyclesSettingsScreen.java`：设置总览、NeoForge 配置页入口、Pass 快捷切换与能力检测。
- `SectionCompilerMixin.java` / `SectionGeometryCollector.java`：复制原版已编译 Section 网格并合并重复重编译。
- `SectionSceneManager.java`：视距、世界/资源代次、区块卸载、增量缓存和提交节奏。
- `CyclesFramePresenter.java` / `CyclesRenderPipelines.java`：RGBA16F 帧纹理、显示 uniform 和 Vulkan GPU 全屏显示变换/放大；没有已上传纹理时不会用 Native 的旧 ready 标记遮住原版世界。
- `NativeBridge.java`：Java 25 FFM 布局、native 生命周期和 ABI 校验。
- `native/include/cycles_bridge.h`：稳定 C ABI；修改结构、状态码或函数时必须同步升级 Java ABI。
- `native/src/cycles_bridge.cpp`：C ABI 参数校验和错误边界。
- `native/src/cycles_engine.cpp`：设备选择、后台 Session、Section 请求调度、材质、相机和帧交换。
- `native/src/realtime_section_mesh.*`：固定拓扑 Section 槽位、动态 BVH refit/重建回退和空闲槽复用。
- `native/tests/cycles_bridge_smoke.cpp` / `scene_update_test.cpp`：不启动 Minecraft 的 Native 端到端测试，覆盖 Section 创建、突发修改、删除和驻留槽复用。
- `scripts/setup-cycles.ps1`：固定版本的依赖获取与 Cycles 构建。

## 游戏内设置与诊断

- F8：启用或关闭 Cycles 世界帧；关闭后恢复原版世界渲染。
- F9：打开 Cycles 总览页。`编辑全部 Cycles 设置` 进入 NeoForge 自动生成的完整客户端配置页；模组列表的 Config 按钮进入同一总览页。
- F10：切换诊断叠加层，显示 ABI、实际设备/降噪器、native 状态、内部尺寸、活动 Pass、sample、revision、Section 数和最近 reset 等级。
- 总览页的 Pass 按钮可依次查看 7 个已实现 Pass；修改完整配置后回到游戏会按 reset 等级异步更新，不要求重启客户端。
- `AUTO` 设备策略保持 OptiX → CUDA → CPU；显式选择某设备时不会悄悄改用另一类设备。降噪器的“配置值”“编译能力”“设备能力”和“当前实际生效值”分别记录，F9/F10 显示的是 native 查询结果。

## ABI 与运行时约束

当前 ABI 为 v36。`CyclesBridgeRenderSettings` 固定 368 字节、`CyclesBridgeCapabilities` 固定 64 字节、`CyclesBridgePassDescriptor` 固定 64 字节、`CyclesBridgeColorLutDescriptor` 固定 72 字节、`CyclesBridgeDiagnostics` 固定 624 字节；Vulkan interop buffer/state 分别固定为 80/72 字节。Java FFM 与 C++ 各自对这些布局做尺寸断言，Java 与 DLL 的 ABI 版本不一致时会在启用前拒绝运行。历史 ABI 的字段演进记录保留在 `docs/stages/`，不作为当前布局的来源。

CPU 租约持有期间对应槽位不会被 Cycles 覆写；interop 槽只有在 Vulkan release timeline 达到对应 generation 后才允许 CUDA 重写。C ABI、结构体字段/偏移、枚举 ID、HANDLE 所有权与 timeline generation 都是稳定契约，修改时必须同步升级 Java/native 版本并完整执行 Native 测试。

Cycles 的 GPU 内核通过 `path_init()` 相对于 `cyclesrenderer_native.dll` 查找。因此以下布局是运行时契约：

```text
build/native/bin/
  cyclesrenderer_native.dll
  OpenImageIO.dll 等运行时 DLL
  OpenImageDenoise.dll
  OpenImageDenoise_core.dll
  OpenImageDenoise_device_cpu.dll
  OpenImageDenoise_device_cuda.dll
  lib/
    kernel_optix.ptx.zst
    kernel_optix_mnee.ptx.zst
    kernel_optix_shader_raytrace.ptx.zst
    kernel_sm_120.cubin.zst
```

不要把 `.deps/cycles-build/lib/Release` 当作运行时目录；其中是链接阶段使用的静态库。

## 修改后的验证顺序

1. `run-client.cmd buildNative`
2. `run-client.cmd runNativeTests`
3. `run-client.cmd build`
4. `run-client.cmd runClient`
5. 进入世界按 F9，确认完整配置页可打开；选择 Fixed `1920 × 1080` 后返回游戏，再按 F10 确认 native 尺寸和设置 revision 更新。
6. 按 F8 启用，确认日志/叠加层显示实际后端；依次切换 Combined、Depth、Normal、Diffuse Color、Emission、Roughness 和 Sample Count。
7. 分别选择 OptiX 与 OpenImageDenoise，确认交互期间 F10 显示所选后端但实际降噪为 Off，停止移动进入 Still 后实际降噪器切换为对应后端且 Variant 为 Denoised。
8. 确认方块纹理上下方向正确，左右/上下转动及前后移动方向正确。
9. 连续破坏和放置方块，确认对应 Section 更新；改变游戏视距并移动跨区块，确认范围随之变化且不出现蓝色清屏。
10. 按 F8 恢复原版；退出并重新进入客户端，确认 CLIENT 配置仍然保留。

每个完成的代码阶段单独创建一次本地 Git 提交。不要提交 `.deps/`、`build/`、`run/` 或 `.tools/`。
