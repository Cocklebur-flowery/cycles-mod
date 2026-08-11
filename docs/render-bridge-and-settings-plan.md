# 渲染数据桥与 Cycles 画面控制里程碑

状态：已确认，实施中（单元 A/A2 已验收；单元 D 基础接入已收口；设置、能力检测与 Pass 查看器已完成自动验证，等待游戏验收）
基线：Minecraft 26.2 / NeoForge 26.2.0.58 / Cycles 5.2 / C ABI v9

## 1. 目标

这个里程碑把“体素占用 + MapColor”的验证原型升级为可继续发展的渲染架构，包含三个方向：

1. 从 Minecraft 已解析的最终渲染模型提取几何、UV、纹理和材质信息，建立面向普通资源包与标准 MOD 方块的通用场景数据桥。
2. 通过可选 Provider 接入 Distant Horizons 的远景 LOD 数据，并复用同一套通用场景协议。
3. 建立 Cycles 的线性 HDR 输出、采样、降噪、色彩管理、设置持久化和可扩展多通道体系。

完成后应达到的结果：

- 普通完整方块、台阶、楼梯、栅栏等静态方块按照实际模型和基础纹理进入 Cycles。
- 普通 Java 资源包对方块模型和基础纹理的覆盖能反映到 Cycles 画面中。
- 大多数最终产出标准 `BakedQuad` 的 NeoForge 方块模型能沿同一条路径工作。
- 安装兼容版本的 Distant Horizons 时，远景 LOD 可通过专用 Provider 进入 Cycles；未安装时不影响 MOD 启动。
- 用户可以在游戏内调整交互/静止采样、降噪器、曝光和查看变换。
- Cycles 内部保留 Scene Linear HDR 数据，只在显示前应用曝光和 OCIO 查看变换。
- 多通道按需启用，不把全部 Pass 每帧复制到 Java 或 Vulkan。

这个里程碑不承诺 100% MOD 兼容。程序化 GPU 绘制、自定义 Shader、方块实体、实体、流体和其他世界渲染 Pass 需要后续专用桥接或合成策略。

## 2. 单元 A 开始前的基线与问题

单元 A 开始前，Java 端扫描相机周围 `64 × 32 × 64` 个方块，只保留 `isSolidRender()` 返回真的方块，并把 `MapColor` 打包成一个 RGBA 值。Native 端将每个非空格子重建成单位立方体的可见面网格，并创建固定粗糙度的 Diffuse 材质。

当前限制：

- 非完整方块的真实形状、模型随机变体和位置偏移丢失。
- UV、Sprite、资源包纹理、生物群系染色、顶点颜色、透明层和发光信息丢失。
- 只注册 `Combined` Pass。
- 内部分辨率固定上限为 `480 × 270`，采样固定为 8。
- OutputDriver 立即把线性浮点结果转成 sRGB RGBA8，超过显示范围的 HDR 信息被截断。
- OpenColorIO 库已链接，但运行目录没有 Blender OCIO 配置和 LUT。
- OptiX 已构建；OpenImageDenoise 依赖已下载，但 Cycles 静态库当前以 `WITH_CYCLES_OPENIMAGEDENOISE=OFF` 构建。
- 开启实验渲染器时会跳过整个原版世界 FrameGraph，其他 MOD 插入其中的世界渲染 Pass 也不会执行。

必须保留的现有行为：

- F8 可以启用/关闭实验渲染器。
- Native 失败时自动恢复原版渲染。
- 后端继续按 OptiX、CUDA、CPU 的顺序自动回退，除非用户明确选择设备策略。
- Minecraft 继续拥有 Vulkan swapchain、主渲染目标和命令提交；Cycles 不接管 Vulkan。
- Cycles 工作线程异步渲染，Minecraft 渲染线程不等待一个完整采样周期。
- 相机坐标、朝向和最终帧方向保持当前已经验证正确的约定。

## 3. 目标架构

```text
资源包与 MOD 内置资源
  -> Minecraft 资源优先级与模型烘焙
  -> BlockStateModel / BlockStateModelPart / BakedQuad

Distant Horizons（可选）
  -> DH LOD Provider

上述来源 -> 不可变 ClientRenderSnapshot
       - Scene/Object/Chunk records
       - Vertex/Index buffers
       - Material table
       - Texture table
  -> Java FFM / C ABI
  -> Cycles Scene
       - Mesh/Object
       - Shader/Texture
       - Pass registry
  -> Scene Linear HDR Pass cache
  -> Exposure
  -> Blender OpenColorIO view transform
  -> Minecraft Vulkan display texture
```

设计原则：

- 使用 Minecraft 已经解析完成的最终模型与 Sprite，不自行重新解析方块 JSON。
- 场景、设置、帧和 Pass 是彼此独立的协议对象；改变曝光不能强迫场景重建。
- Java 只提交不可变快照；Native 工作线程拥有自己的数据副本。
- 材质和纹理去重，几何以材质 ID 引用，不为每个方块复制一份纹理。
- 输入纹理色彩空间和输出查看变换分离：Albedo 是颜色，Normal/Roughness/Metal 等是数据。
- 无法转换的内容必须有明确回退或诊断，不能静默生成错误几何。

## 4. 通用渲染数据桥

### 4.1 数据来源

静态方块从当前 Minecraft 26.2 的最终模型系统读取：

- 用客户端 `ModelManager` 获取当前 `BlockState` 对应的 `BlockStateModel`。
- 按 Minecraft/NeoForge 的位置、状态和随机种子语义收集模型部件。
- 从每个 `BakedQuad` 读取四个位置、法线、UV、方向、Tint index 和 Sprite。
- 从 `TextureAtlasSprite` 读取最终资源包解析后的 Sprite ID、尺寸和像素。
- 根据 BlockColors/Quad tint index 计算草、树叶、水等位置相关染色；不把原版烘焙光照乘进 Albedo。
- 使用 Minecraft 的面剔除语义，而不是只用“邻居是否占用”判断。
- 保存模型提供的透明层、发光和必要的材质标记。

资源包重载时必须：

1. 停止使用旧 Sprite/模型引用。
2. 增加资源代次编号。
3. 清空 Java 材质与模型缓存。
4. 重新生成 Native 材质、纹理和受影响场景。

### 4.2 快照内容

计划中的场景快照至少包含：

- 场景原点、范围、资源代次和场景 revision。
- 对象或区块记录：局部原点、顶点范围、索引范围、稳定诊断名称。
- 顶点：局部位置、几何法线、UV；法线贴图需要的切线可由几何与 UV 在 Native 端生成。
- 索引：三角形顶点索引。
- Primitive 材质索引。
- 材质：基础纹理 ID、Tint、Alpha 模式、发光、双面/阴影等标记。
- 纹理：资源 ID、宽高、帧信息、色彩空间角色和像素数据。

纹理第一版使用去重后的 RGBA8 Sprite 数据；Cycles 负责按颜色/非颜色角色解释。ABI v4 固定提取 Sprite 的第一帧，尚未携带动画 revision；支持动画纹理时必须升级 ABI 或增加独立资源/材质 revision，不能复用旧纹理身份冒充新帧。

### 4.3 Alpha 与材质范围

本里程碑的数据桥实现：

- Opaque。
- Cutout/Alpha Clip。
- 基础发光。
- 生物群系和模型 Tint。

暂不在这个单元实现：

- 玻璃、水等真实 Transmission/IOR。
- 半透明排序语义。
- 体积吸收和散射。
- LabPBR `_n` / `_s` 解码。

这些内容需要数据桥提供材质扩展位，但不会阻塞普通资源包基础纹理。

### 4.4 缓存与更新

通用桥验证后，近景已改为复用 Minecraft 自己的 `SectionCompiler` 输出。缓存键是 16³ Section 的稳定坐标 ID，不按 `BlockState` 猜测几何，因此位置、邻居、随机模型、连接纹理、流体层和 NeoForge Section 追加几何都由原版重编译结果决定。

当前更新规则：

- `SectionCompiler` 返回后、`MeshData` 被关闭前，复制 Position、Color、UV0 并按 Quad 生成三角形和面法线。
- 相同 Section 的待处理结果只保留最新 sequence，避免连续方块更新堆积旧网格。
- 活跃范围使用 `Options#getEffectiveRenderDistance()`、原版水平区块距离规则、相机上下各 viewDistance 个 Section 和世界 min/max Section。
- 方块/光照变化、区块装卸、视距移动和 `allChanged()` 触发的重编译进入同一条 upsert/remove 路径。
- 每帧最多上传 24 个 Section，Java 侧预算约 4 ms；首批场景等待 750 ms 安静窗口，已有场景的更新等待 100 ms，不再按固定最大间隔强制提交半成品场景。
- 资源包重载或相机离场景原点超过 1024 方块时重建共享资源/原点，并请求原版重新编译全部可见 Section。

Native 为活动 Cycles Session 保存 `Section ID -> Mesh/Object` 映射。提交时通过共享不可变 Section 快照识别未变化节点；已有 Section 原地清空并重填 Mesh，新增或卸载 Section 才创建或删除节点。共享资源指针变化（资源包/图集、场景原点）以及设备回退仍会重建 Session。几何规模变化和节点增删仍可能触发 Cycles 设备几何及 OptiX 加速结构更新，因此不能把这条路径描述成零成本局部 BVH 更新。

### 4.5 兼容性目标

| 类型 | 本里程碑目标 |
| --- | --- |
| 原版普通静态方块 | 模型、UV、基础纹理、Tint 正确 |
| 普通 Java 资源包 | 模型和基础纹理覆盖正确 |
| MOD 标准 BlockStateModel | 最终产出 BakedQuad 时通常兼容 |
| NeoForge 自定义模型加载器 | 最终走标准 Quad 时尽量兼容 |
| 连接纹理/位置相关模型 | 按实际 collectParts 结果验证，不承诺全部 MOD |
| 方块实体、实体、物品展示 | 不在本里程碑 |
| 水、熔岩、云、天气、粒子 | 不在本里程碑 |
| Shader MOD/自定义 FrameGraph Pass | 不直接兼容，后续研究混合渲染 |

### 4.6 Distant Horizons 兼容边界

第一批远景兼容只支持 Distant Horizons，Voxy 明确延后。DH 通过可选 Provider 隔离，主 MOD 不建立硬运行时依赖；没有安装 DH、版本不匹配或 Provider 初始化失败时，只关闭远景桥并保留近景 Cycles 与 F8 回退。

Provider 必须读取 DH 对外提供或能够稳定适配的最终 LOD 网格/材质数据，不能从 Minecraft Vulkan 缓冲中反向抓取。DH 的远景网格未来应转换成 ABI v5 的独立 Section/Object 记录，与近景方块共享 Native 材质路径，但保持独立的 revision 与更新节奏。

近景 Minecraft 模型和远景 DH LOD 之间需要明确所有权边界、重叠带和接缝策略，避免重复几何与闪烁。实际 DH 26.2 API、事件线程和网格寿命必须以本地安装版本为准；在读取对应依赖/API 前不冻结 Provider 接口，也不承诺兼容其他 DH 版本。

## 5. C ABI 演进

场景格式和设置都会成为稳定契约，修改时必须同步升级 ABI。

单元 A 冻结的 ABI v4 整场景入口继续保留；Section 流送阶段升级为 ABI v5；设置阶段升级为 ABI v6；实际采样阶段升级为 ABI v7；帧管线遥测阶段升级为 ABI v8；场景更新遥测阶段升级为 ABI v9，并继续避免把 C++ 类型暴露给 Java：

- `CyclesBridgeCamera`：80 字节，沿用已验证的相机坐标约定。
- `CyclesBridgeScene`：48 字节，场景原点和各数组计数。
- `CyclesBridgeVertex`：40 字节，位置、法线、UV、打包 RGBA 顶点色。
- `CyclesBridgeTriangle`：16 字节，三个顶点索引和材质索引。
- `CyclesBridgeMaterial`：32 字节，纹理索引、Cutout 标记、发光强度和 Alpha 阈值。
- `CyclesBridgeTexture`：32 字节，RGBA8 像素范围和尺寸。
- `CyclesBridgeSceneResources`：48 字节，共享场景原点、材质、纹理和像素计数。
- `CyclesBridgeSection`：48 字节，稳定 ID、局部原点和几何计数。
- `CyclesBridgeFrame`：40 字节，低分辨率尺寸、generation、状态标记和实际 sample 数。
- `CyclesBridgeRenderSettings`：ABI v6 新增，固定 208 字节；包含稳定的设置 revision、设备/分辨率/采样/光程/过滤/降噪/显示/Pass ID。
- `CyclesBridgeCapabilities`：ABI v6 新增，固定 64 字节；区分编译能力、枚举到的设备、设备实际支持的降噪器、Pass mask 和最大输出尺寸。
- `CyclesBridgeDiagnostics`：ABI v6 新增、ABI v7 追加采样遥测、ABI v8 追加帧管线遥测、ABI v9 追加场景更新遥测，固定 240 字节；除设备/Pass/sample/帧统计外，还报告整 Section 映射提交、Cycles scene delta 和 render start 的计数及时间。
- Pass 使用稳定枚举/位掩码，不用 UI 显示字符串作为协议键。

每个结构继续包含 `struct_size`、`struct_version` 和保留字段。Java FFM 布局、C 头、参数校验和 Native 冒烟测试必须同时更新。

Java 25 FFM 与 MSVC 对上述字节布局均有静态/启动时断言。v5 使用 `reset_scene` 设置共享资源、`upsert_section` / `remove_section` 修改暂存区、`commit_scene` 发布不可变请求；v6 使用 `apply_settings`、`query_capabilities` 和 `query_diagnostics` 管理运行时画面控制。材质/图集通过共享所有权避免每次提交复制整张图集。`render_frame` 通过 generation 只复制变化后的 RGBA。C 层先验证尺寸、版本、枚举、范围和有限浮点值，再复制给 Native 工作线程。动画 revision 和 PBR 材质扩展仍需要后续显式 ABI 升级。

## 6. Cycles 画面设置

### 6.1 设置持久化与入口

设置保存在 NeoForge 客户端配置中，不修改系统环境变量，也不写入世界存档。配置带独立 schema version；未知或越界值回退到安全默认值并记录日志。

建议入口：

- F9 打开 Cycles 设置界面。
- NeoForge MOD 列表中的配置入口使用同一个界面。
- F8 仍只负责启用/关闭渲染器。

界面第一版分为：性能与采样、降噪、色彩管理、多通道、高级、诊断。

当前实现状态：NeoForge `CLIENT` 配置已经落到 `config/cyclesrenderer-client.toml`，带 schema version 和运行时 revision。F9 打开专用总览页，模组列表 Config 按钮进入同一页面；总览页再打开 NeoForge 自动生成的完整字段编辑器。F10 切换诊断叠加层。默认值继续保持 Fit Inside `480 × 270`、交互 1 sample、静止 8 samples 和 150 ms 静止延迟，用户可选择 Fixed `1920 × 1080`，native 上限为 `3840 × 2160`。

### 6.2 交互与静止双配置

Minecraft 相机持续运动，不能直接照搬 Blender 离线渲染的单一最大采样。设置包含两个质量配置：

**交互配置**

- 内部分辨率比例。
- 最大采样。
- 噪波阈值与最小采样。
- 每帧/每次累计时间预算。
- 降噪开始采样数。

**静止配置**

- 进入静止模式的延迟。
- 内部分辨率比例。
- 最大采样。
- 噪波阈值与最小采样。
- 最大累计时间。

建议默认值只作为首轮调试起点，不在评审时冻结：交互 50% 分辨率、8 samples；静止 75% 分辨率、64 samples；自动选择可用 GPU 降噪器。默认值必须通过当前 RTX 设备上的帧时间和画质测试再确定。

### 6.3 降噪

可选策略：

- Off。
- Auto：优先 OptiX，失败后 OpenImageDenoise，最后无降噪。
- OptiX。
- OpenImageDenoise CPU/GPU。

共同设置：

- 开始采样数。
- 输入：Color、Color + Albedo、Color + Albedo + Normal。
- OIDN Prefilter：None、Fast、Accurate。
- OIDN Quality：Fast、Balanced、High。

实现要求：

- 重新构建 Cycles 静态库并启用 OpenImageDenoise。
- 链接并部署 OIDN 主 DLL 和实际使用的设备 DLL。
- 查询实际可用性；界面不能显示一个运行时不可用的选项为“正常”。
- 指定降噪器失败时按照设置决定自动回退或报告错误，不能导致 Minecraft 崩溃。

当前实现状态：ABI v9 已区分“配置请求”“构建时编译能力”“设备枚举能力”和“当前实际生效降噪器”，并追加实际采样、帧管线和场景更新遥测。OptiX 已在 RTX 5080 的 native 冒烟中实际启用并产出画面；当前 Cycles 静态库仍以 `WITH_CYCLES_OPENIMAGEDENOISE=OFF` 构建，因此 OIDN 会准确报告为不可用，未假装已经支持。重建 Cycles/OIDN 及部署 DLL 保留到后续独立阶段。

### 6.4 线性 HDR 与色彩管理

FrameStore 改为保存 Scene Linear Float/half 数据，不在接收 Tile 时转换成 RGBA8。

显示顺序：

```text
Scene Linear Pass
  -> Exposure：value * 2^EV
  -> OCIO View Transform
  -> Display colorspace
  -> RGBA8 Minecraft texture
```

第一版提供：

- Display：sRGB。
- View Transform：AgX、Standard、Khronos PBR Neutral、Raw/调试。
- Look：使用所选 Blender OCIO 配置实际提供的列表。
- Exposure，单位 Stops/EV。
- Gamma。

为了得到与 Blender 一致的 AgX，不实现自定义近似曲线。运行包应包含与 Cycles 5.2 对齐并固定来源版本的 Blender `config.ocio`、LUT 和相关色彩管理文件。

输入纹理色彩空间由材质角色决定：

- Base Color/Emission color：sRGB 转 Scene Linear。
- Normal/Roughness/Metal/AO/Height/ID：Non-Color/Data。
- HDR 环境纹理：根据文件元数据或明确设置解释。

第一版最终仍写入 Minecraft 当前 SDR RGBA8 主纹理。HDR10/Display P3/Rec.2020 输出需要确认 Minecraft Vulkan swapchain 与操作系统 HDR 状态，属于后续范围。

当前实现状态：设置 ID、EV、Gamma、Standard/Raw 输出路径已经接通，但 FrameStore 仍在接收 Tile 时转换并保存 RGBA8，没有建立可复用的 Scene Linear HDR Pass cache。`AgX` 与 `Khronos PBR Neutral` 已有稳定配置 ID 和 UI 选项，但在 Blender `config.ocio`/LUT 正式部署前按 Standard 显示；这不是 Blender AgX 的近似实现，也不能宣称 OCIO 色彩管理已经完成。

### 6.5 设置变更语义

| 变更类别 | 示例 | 运行时行为 |
| --- | --- | --- |
| 仅显示 | EV、View Transform、Look、Gamma | 复用 HDR Pass，不重置采样 |
| 重置累计 | 采样、噪波阈值、反弹、Clamp | 保留场景，重置当前累计 |
| 重建 Buffer | 启用/关闭 Pass、分辨率 | 重建 Render Buffer 并重置累计 |
| 重建 Session | 设备、OSL/Shading System | 取消并重建 Cycles Session |
| 重建场景/材质 | 资源包、PBR 规则、纹理角色 | 重建受影响场景与材质 |

设置界面应用前显示必要的重建等级；仅显示类设置必须即时生效。

## 7. 多通道体系

Native 端维护 Pass registry 和 HDR Pass cache。Java 默认只获取已经色彩映射的当前显示 Pass；需要参与 Minecraft 合成的 Pass 才创建额外 Vulkan 纹理。

第一版默认启用：

- Combined。
- 当前降噪器要求的 Albedo/Normal 辅助 Pass。

第一版可选调试 Pass：

- Depth。
- Normal。
- Diffuse Color/Albedo。
- Emission。
- Roughness。
- Sample Count。

当前实现状态：上述 7 个 Pass 已在 native 中按当前选择按需注册，Java/Vulkan 每次只接收一个活动显示 Pass。Depth、Normal、Roughness 和 Sample Count 使用明确的调试映射，Combined/Diffuse Color/Emission 走当前曝光与显示转换。native 冒烟逐一切换所有 Pass、恢复 Combined 后，再继续验证同一 Renderer 中的 Section 修改和删除。

后续再开放：

- Direct/Indirect Diffuse、Glossy、Transmission。
- Position、UV、Motion。
- Object ID、Material ID、Cryptomatte。
- Light Groups。
- Shader AOV。

延后原因：Motion 需要可靠的前后帧变换；Object/Cryptomatte 需要确定方块、区块、实体的稳定身份；Light Groups 和 AOV 需要先建立灯光与材质扩展协议。

内存规则：

- Pass 默认关闭，按需分配。
- Denoiser 隐式要求的 Pass 自动启用并在 UI 中标记。
- Native 不把全部 Pass 每帧复制给 Java。
- Pass 分辨率、分量数、数据类型和用途通过只读描述查询，不由 Java 猜测。

## 8. 实施单元与提交边界

整个里程碑涉及 Java、C ABI、Native、可选 MOD 集成、依赖部署和持久配置，不能作为一个不受控的大提交完成。静态桥验收后增加了一个独立的 Section 流送/展示性能单元，其余设置单元继续分开提交。

### 单元 A：通用静态方块桥

结果：Cycles 画面显示真实静态方块形状、基础纹理、Tint、Cutout 和发光。旧 MapColor 立方体路径已经移除；暂不支持的半透明 Quad 被跳过并计数，不伪装成实体立方体。

预计涉及：场景捕获类、MOD 生命周期、Java FFM、C ABI、Cycles 网格/材质构建、Native 冒烟测试和 README。若实际文件超过批准预算，先拆分 Native 协议与 Java 提取，但任何中间提交都必须保持项目可构建且 F8 可安全回退。

验收：

- Native 冒烟覆盖多材质、UV 和 Alpha Clip。
- Java/Native ABI 布局断言通过。
- 原版石头、草方块、原木、台阶、楼梯、栅栏模型与基础纹理可辨认。
- 切换普通资源包并重载后，Cycles 纹理随之变化。
- 无法转换的模型有计数和日志，不导致崩溃。

当前状态：代码、Java 构建、Native 构建、OptiX 纹理冒烟和游戏内模型/纹理验收已经通过，提交为 `d91e71e`。

### 单元 A2：原版 Section 流送、动态更新与 Vulkan 展示

结果：固定方块扫描替换为原版 16³ Section 编译网格；范围跟随游戏视距，方块/区块变化进入增量缓存并在现有 Cycles Session 内更新对应节点；上一帧在后台更新期间保留；低分辨率 Native 帧由 Vulkan 在 GPU 上放大。

实现包括：

- `SectionCompilerMixin`、线程安全的最新结果合并队列和世界身份隔离。
- `SectionSceneManager` 的视距裁剪、区块卸载、资源重载、原点重定位、上传预算与批量提交。
- ABI v5 的共享资源、Section upsert/remove/commit 和 frame generation。
- Native Session 内的 `Section ID -> Mesh/Object` 映射、未变化节点复用、已有 Mesh 原地更新及节点增删。
- 移动 1 sample、静止 150 ms 后 8 samples 的基础交互策略。
- `CyclesFramePresenter` 的低分辨率 Vulkan 纹理和全屏三角形最近邻放大，以及“无已上传纹理则继续原版”的有效帧判定。
- 纹理 V 坐标修正；Section 网格使用 Minecraft 编译结果中的原始 atlas UV。

验收：

- Java 构建、Native 构建和 ABI v5 OptiX 冒烟通过；Native 冒烟覆盖同一 Renderer 中的 Section 创建、修改和删除。
- 游戏视距变化和跨区块移动会增加/移除对应 Section，不再限制为固定短视距盒。
- 放置、破坏方块后对应 Section 可见更新。
- 纹理上下方向、相机方向和移动方向正确。
- 场景提交期间不显示蓝色占位帧；4K 窗口不再每帧执行 CPU 4K 放大和整帧上传。
- F8 启用前及第一张 Cycles 帧准备前仍显示原版世界；F8 关闭恢复原版。

当前状态：代码、自动验证和游戏内验收已经完成。方块/区块更新造成的约 0.4 秒停顿已大幅缓解；残余约 0.1 秒顿卡作为后续性能热点优化项，不阻塞基础框架收口。

### 单元 D：Distant Horizons LOD Provider

结果：安装受支持 DH 版本时，Cycles 可以消费远景 LOD 快照；没有 DH 或兼容层失败时，近景桥和原版回退保持可用。Voxy 不在此单元。

预计涉及：DH 版本/API 审计、可选依赖声明、隔离的 Provider、LOD 快照转换、近远景所有权/接缝策略、诊断和文档。若 DH 没有可稳定读取的公开或受支持接口，先停止并报告，而不是绑定不可靠的内部 Vulkan 资源。

实现采用 DH 3.2.1-b-dev 的公开 API 7.1 Terrain Repo，不读取 DH 私有 Vulkan/OpenGL 缓冲。Provider 通过反射保持可选，校验 API major 7，并在独立守护线程中使用 `createSoftCache()` 读取地形列。旧实验路径曾把最上层非空气数据点重建为半径 256 方块、8 方块网格的彩色高度场，并合并进 ABI v4 整场景。

Section 流送切换到 ABI v5 后，旧高度场暂不再合并进活动场景。完整 DH 兼容需要重新定义近景 Section 与远景 LOD 的所有权/接缝，并让远景作为独立 v5 Object/Section 提交。旧远景颜色使用对应 Minecraft `BlockState` 的 MapColor，几何包含顶面和相邻高度差形成的侧面；它只是公开地形数据的低模重建，不声称与 DH 私有最终渲染网格逐顶点一致。

验收：

- 没有安装 DH 时可以正常构建和启动。
- 安装目标 DH 版本后能看到超出近景捕获范围的 LOD，并且移动/更新不会阻塞渲染线程。
- 近远景没有明显重复表面、反向几何或持续闪烁。
- 禁用 DH、禁用 Cycles以及 Provider 失败时均可安全回退。

当前状态：API/版本审计、反射 Provider、异步软缓存读取、彩色高度场、诊断和文档已经编码并通过构建。安装 DH 时游戏和 F8 回退正常，但游戏测试没有看到可靠远景；Provider 作为隔离的兼容骨架保留，当前 v5 活动场景不消费它，最终网格、可见距离、接缝和质量优化延后。

### 单元 B：HDR、采样、降噪与 OCIO 核心

结果：Native 保留线性 HDR；采样、分辨率、自适应采样、OptiX/OIDN 和 AgX 可通过设置快照控制。

预计涉及：设置 ABI、Cycles Session/Integrator、FrameStore、OCIO/OIDN 构建与运行时部署、Native 冒烟和文档。

验收：

- 曝光和 View Transform 改变时不重置 Cycles 累计。
- 采样改变时只重置累计，不重建场景。
- OptiX 与 OIDN 分别完成可识别的降噪冒烟；不可用时给出准确状态。
- HDR 测试场景在 AgX 下保留高光层次，Raw/Standard 行为可对照。
- 设置错误不会让 Minecraft 进程崩溃。

当前状态：采样、分辨率、光程、过滤、设备策略、OptiX 降噪和基础显示参数已经通过 ABI v9 接通；真正的线性 HDR Pass cache、Blender OCIO 资产/AgX 和 OIDN 构建尚未完成，因此单元 B 只完成了控制面与一部分 Cycles 参数，不标记为整体验收。

### 单元 C：Minecraft 设置界面与 Pass 查看器

结果：游戏内可以持久调整设置，查看当前有效后端/降噪器，并切换已启用的调试 Pass。

预计涉及：NeoForge 客户端配置、设置 Screen、键位/配置入口、NativeBridge 设置提交、语言资源和 README。

验收：

- 配置保存后重启客户端仍生效。
- 越界配置安全回退。
- UI 标明设置变更的重置等级。
- Combined、Depth、Normal、Emission 等已启用 Pass 可以切换查看。
- F8 关闭后原版渲染恢复，F9 设置界面不依赖 Cycles 已启用。

当前状态：CLIENT 配置、F9/模组列表入口、F10 诊断、能力查询、运行时设置提交和 7 Pass 查看器已编码；Java 编译及 OptiX native 冒烟通过，等待游戏内持久化、1080p、所有 Pass、F8 回退和 UI 操作验收后提交。

单元 A、D、A2、B、C 各自完成后创建一个本地 Git 提交。

## 9. 保持不动的范围

本里程碑不会：

- 更换 Minecraft、NeoForge、Gradle、Java、CUDA、OptiX 或 Cycles 固定版本。
- 让 Cycles 使用 Vulkan 后端。
- 修改系统或用户环境变量。
- 接管 Minecraft GUI、输入或游戏逻辑。
- 实现实体、方块实体、流体、天气、云、粒子或传送门。
- 实现 LabPBR；数据桥只为下一里程碑预留材质能力。
- 实现 HDR 显示器输出。
- 解决所有其他渲染 MOD 的 FrameGraph/Mixin 冲突。
- 兼容 Voxy；本阶段只实现 Distant Horizons Provider。

## 10. 风险与停止条件

主要风险：

- Minecraft 26.2 模型 API 与旧版本差异较大，不能直接照搬旧版 NeoForge 示例。
- 自定义模型可能依赖世界或位置，错误缓存会破坏连接纹理和随机模型。
- 高分辨率资源包可能显著增加 Java 到 Native 的复制量和 OptiX 显存。
- Cutout、Tint、纹理方向和颜色空间任何一项错误都会造成明显视觉偏差。
- OIDN 和官方 Blender OCIO 资产会改变 Cycles 构建与运行时文件契约。
- Pass 数量会增加设备缓冲与主存占用。
- 设置配置、C ABI 枚举和 Pass ID 一旦发布即成为稳定契约。
- DH API 和线程/缓存寿命可能随具体版本变化，Provider 必须与主数据桥隔离。

实施时遇到以下情况必须停止并重新评审：

- 需要修改超过已批准文件预算或新增未规划依赖。
- 当前模型 API 无法在不调用第三方 MOD 私有实现的情况下提取最终 Quad。
- 目标 DH 版本没有可稳定适配的 LOD 数据接口，或只能反向读取 Vulkan/GPU 私有资源。
- 资源重载无法安全失效缓存。
- 需要改变 Minecraft Vulkan 主目标格式或 swapchain。
- OIDN/OCIO 所需运行时资产无法固定版本或合法、可重复地部署。
- 其他 MOD 的用户改动与目标文件发生无法安全分离的重叠。

## 11. 验证顺序

每个实施单元至少执行：

1. 检查 Git 工作树与实际 diff。
2. `run-client.cmd buildNative`。
3. `run-client.cmd runNativeSmoke`。
4. `run-client.cmd build`。
5. `run-client.cmd runClient` 手动进入世界验证。
6. F8 启用、关闭与错误回退。
7. 检查日志中的实际设备、降噪器、Pass、分辨率和设置 revision。

资源桥额外验证普通资源包重载和代表性方块；设置阶段额外验证持久化、即时显示调整和重建等级；多通道阶段额外验证 Pass 数值范围、方向、内存占用和未启用 Pass 不分配。

## 12. 评审时需要确认

开始实现前请确认以下产品选择：

1. 接受单元 A、D、A2、B、C 的提交边界，而不是把整个里程碑塞进一个提交。
2. 设置入口采用 F9，并同时接入 NeoForge MOD 配置入口。
3. 第一版显示设备只提供 sRGB；AgX、Standard、Khronos PBR Neutral 和 Raw 作为查看变换。
4. 第一版材质范围为 Opaque + Cutout + Tint + Emission；玻璃/水和 LabPBR 后续实现。
5. 第一版多通道只开放 Combined、Depth、Normal、Diffuse Color、Emission、Roughness、Sample Count。
6. 通用静态桥验证后，以独立 A2 单元实施 Section 流送、区块增量缓存和现有 Cycles Session 内的 Mesh/Object 更新；更细粒度且低成本的设备 BVH 更新继续后移。
7. 远景第一版只支持 Distant Horizons，Voxy 延后，并使用可选 Provider 而不是硬依赖。

上述选择已经确认。A、D 已收口，A2 等待游戏验收；之后按 B、C 的顺序实施，并在每个单元通过相应验收后创建本地提交。
