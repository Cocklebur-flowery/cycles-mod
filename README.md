# Cycles Renderer Prototype

这是一个面向 Minecraft 26.2 / NeoForge 26.2 的实验性客户端 MOD。Minecraft 本身使用 Vulkan 后端；MOD 将世界快照交给 Blender Cycles 渲染，并把 Cycles 输出的 RGBA 帧上传到 Minecraft 的 Vulkan 主渲染目标。

当前实现优先选择 OptiX，失败后依次回退到 CUDA 和 CPU。F8 用于启用或关闭实验渲染器，任何 native 错误都会关闭实验路径并恢复原版世界渲染。

## 当前阶段

- Cycles 5.2 已通过 C ABI v4 接入 Java Foreign Function & Memory API。
- 世界快照为相机周围 `64 × 32 × 64` 个方块。
- 快照读取 Minecraft/NeoForge 最终 `BlockStateModel` 与 `BakedQuad`，保留非完整方块形状、模型随机变体、位置偏移、UV 和几何法线。
- 使用 Minecraft 的面剔除规则，并从最终 `TextureAtlasSprite` 提取资源包覆盖后的 RGBA 像素。
- Cycles 材质支持基础纹理、方块/生物群系 Tint、Cutout Alpha Clip 和基础发光；半透明层暂时跳过并计数。
- 资源包重载会增加场景资源代次并强制重建快照，不复用旧 Sprite 像素。
- 安装 Distant Horizons 3.2.1 / API 7 时，实验性可选 Provider 会在后台读取 DH 地形仓库并尝试生成彩色远景高度场；未安装、未初始化或读取失败时只关闭远景分支。当前游戏测试尚未看到可靠远景，完整 DH 可见性与质量留待后续兼容阶段。
- Cycles 在自己的会话线程中渐进渲染，Minecraft 渲染线程只提交相机并读取最新完成帧。
- 内部渲染分辨率上限为 `480 × 270`，当前每次累计 8 个样本，再放大到窗口尺寸。
- Minecraft 的 Vulkan swapchain、纹理和命令编码器仍由 Minecraft 管理；Cycles 不使用 Vulkan。

暂未实现半透明/折射方块、流体、方块实体、实体、天空系统、动态光源、区块增量更新、PBR 扩展、降噪和跨平台打包。

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
Minecraft 最终 BakedQuad / Sprite / Tint（近景）
DistantHorizonsSceneProvider / Terrain Repo（可选远景）
  -> ClientRenderSnapshot
  -> NativeBridge（ABI v4 扁平场景数组）
  -> CyclesEngine 请求队列
  -> Cycles 网格 + 内存纹理 + Diffuse/Cutout/Emission 材质
  -> Cycles Session（OptiX -> CUDA -> CPU）
  -> OutputDriver 最新渐进帧
  -> RGBA8 最近邻放大
  -> Minecraft Vulkan writeToTexture
```

近景在相机进入新的区块/高度 Section 或资源包重载时重新捕获。DH Provider 每移动 64 方块异步刷新远景，完成后通过独立 revision 触发场景上传，不在 Minecraft 渲染线程读取数据库。相机位置、朝向、FOV 或输出尺寸变化时会重置 Cycles 累计；静止时继续积累当前帧。

## 代码入口

- `CyclesRendererMod.java`：F8 生命周期、场景刷新和 Vulkan 上传。
- `ClientRenderSnapshot.java`：从客户端世界最终模型采集固定范围的网格、纹理和基础材质。
- `DistantHorizonsSceneProvider.java`：反射检测 DH API 7，在后台读取 Terrain Repo 并发布不可变远景高度场。
- `NativeBridge.java`：Java 25 FFM 布局、native 生命周期和 ABI 校验。
- `native/include/cycles_bridge.h`：稳定 C ABI；修改结构、状态码或函数时必须同步升级 Java ABI。
- `native/src/cycles_bridge.cpp`：C ABI 参数校验和错误边界。
- `native/src/cycles_engine.cpp`：设备选择、后台 Session、网格/材质、相机和帧交换。
- `native/tests/cycles_bridge_smoke.cpp`：不启动 Minecraft 的 native 端到端测试。
- `scripts/setup-cycles.ps1`：固定版本的依赖获取与 Cycles 构建。

## ABI 与运行时约束

ABI v4 保留 `CyclesBridgeCamera`（80 字节），以 `CyclesBridgeScene` 和扁平的 Vertex/Triangle/Material/Texture 数组取代体素结构。纹理像素通过一次调用以内存 RGBA8 传入；Java 与 DLL 的 ABI 版本不一致时会在启用前拒绝运行。

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
5. 进入世界按 F8，确认日志显示 `backend=OPTIX`、画面方向正确、移动时不阻塞，并确认再次按 F8 可恢复原版。

每个完成的代码阶段单独创建一次本地 Git 提交。不要提交 `.deps/`、`build/`、`run/` 或 `.tools/`。
