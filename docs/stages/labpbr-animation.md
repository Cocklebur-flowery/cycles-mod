# LabPBR 运行时动画图集同步第二里程碑

状态：`A0 / A1 / A2 / A3 / A4 / A5 DONE`；`A6 / A7 NOT STARTED`；
`V2 NOT STARTED`

A0 调查与设计基线：`323a8f2`（2026-08-20，Asia/Shanghai）

目标资源包：`run/resourcepacks/SPBR-21.zip`

目标格式：ShaderLABS LabPBR 1.3

本文只定义第二里程碑的合同、边界、风险和验证门槛。A0 不修改运行时代码、
Native ABI、配置、资源 ID、Cycles patch 或稳定标志位，也不表示动画已经实现。

## 1. 里程碑目标

PBR-A 只解决 Section 方块图集的运行时动画同步：Minecraft 更新方块 Sprite 后，
Cycles 中 Base Color、Normal、Material 和 Auxiliary 的同一图集区域按同一逻辑
revision 更新。

完成条件：

- 离散动画和 `interpolate=true` 动画都跟随 Minecraft 实际动画状态；
- 四张图集不会跨 revision 混用；
- Java、FFM 和 Native 只传输变化区域，不复制完整图集；
- Cycles 在既有图像、材质、Section Mesh 和 Session 上应用区域更新；
- 资源重载、资源包切换、F8 关闭/重新启用和退出不会发布旧资源 generation 的更新；
- Default 与 experimental DLSS 构建使用同一语义和 ABI。

不允许通过每 tick 完整重建 Scene、Session、材质图或四张图集来模拟动画。

## 2. A0 已确认的当前事实

当前初始化链为：

```text
Minecraft block atlas + ResourceManager
  -> LabPbrAnimationFrames：记录当前离散 image frame
  -> LabPbrAtlasBuilder：构建三张 LabPBR 数据图集
  -> SectionSceneResourceBuilder：构建 Base Color 和固定资源描述符
  -> NativeSceneUploadQueue：reset / section mutation / commit
  -> C ABI 45
  -> Cycles MemoryImageLoader
```

当前限制：

- `SpriteAnimationStateMixin` 在 `AnimationState.drawToAtlas()` 中只记录 `oldFrame`；
- `LabPbrAnimationFrames` 只保存 Sprite identity 与整数 frame，没有 dirty drain、
  revision、插值进度或上传通知；
- Base/Normal/Material/Auxiliary 只在 SceneResources 创建或重载时选择同一离散帧；
- `SceneResourcesData` 与 `MemoryImageLoader` 当前持有不可变完整像素快照；
- ABI 45 只有完整资源 reset、Section mutation 和 commit，没有纹理区域命令；
- 当前 Cycles 5.2 `ImageManager` 没有公开的既有 `ImageHandle` 区域更新接口。

Minecraft 26.2 的真实动画语义是：

- `AnimationState.tick()` 推进动画时间；
- 非插值动画只在 image frame 改变后标记 dirty；
- 插值动画即使 image frame 未变化也会在每个 atlas draw 使用当前帧、下一帧和
  量化进度重绘；
- 当前 Mixin 的回调发生在 Minecraft atlas draw 路径，属于渲染线程时序，不能在
  回调中执行 PNG 解码、ResourceManager I/O、完整像素复制或阻塞式 FFM 调用。

对本地 `SPBR-21.zip` 的只读检查找到 100 个纹理动画 metadata，其中 43 个声明
`interpolate=true`。因此只支持离散 frame change 不是本里程碑的完整实现。

## 3. 动画状态合同

每次 Minecraft 实际绘制一个动画 Sprite 时，捕获以下逻辑状态：

```text
resource generation
sprite Identifier
SpriteContents identity
current image frame
next image frame
interpolation enabled
Minecraft quantized progress (0..999)
monotonic event sequence
```

规则：

1. 非插值动画只在 Minecraft 选择的 image frame 发生变化时形成 dirty state。
2. 插值动画按每次实际 atlas draw 记录 old/next/progress；上传侧可以丢弃同一 Sprite
   的过时中间状态，但不能倒序发布。
3. 相同 generation、Sprite 和等价动画状态不得重复上传。
4. `SpriteContents` identity 改变表示资源已替换；旧 identity 的事件立即失效。
5. 资源重载建立新 generation。旧 generation 的已排队或正在编码事件不得进入新的
   Native 资源快照。
6. Minecraft Base Sprite 的时间轴是四张图集的唯一权威时间轴。

Companion `_n` / `_s` 使用 Base 的 image frame 索引和插值进度。它们必须形成与
Base frame 尺寸兼容的完整帧网格；本里程碑不运行独立 companion 时间轴。尺寸不匹配、
缺失或解码失败继续使用现有中性 fallback，并进入已有错误诊断语义。

## 4. 像素编码合同

一个动画状态产生一个逻辑批次。批次覆盖同一 atlas 坐标中的：

| 固定槽 | 内容 | 颜色角色 |
| ---: | --- | --- |
| 0 | Base Color | `COLOR_SRGB` |
| 1 | Normal | `DATA_LINEAR` |
| 2 | Material | `DATA_LINEAR` |
| 3 | Auxiliary | `DATA_LINEAR` |

离散动画直接选择目标 image frame。插值动画必须先按 Minecraft 的量化进度混合原始
输入帧，再执行现有 LabPBR 解码；不能直接混合两个已经解码完成的 Cycles 数据 patch。
这样可以保持 `_n.RG` 法线重建、`_s.G` F0/金属分类、emission、AO、height 和
encoded surface 的现有边界语义。

A3 必须用 Minecraft 动画 shader 的实际数值行为锁定 byte rounding oracle。在 oracle
建立前，不把普通整数除法、浮点舍入或主观视觉近似写成稳定合同。

每个 region 必须满足：

- atlas index 属于固定四槽；
- `x/y/width/height` 全部位于对应纹理边界内；
- RGBA8 row stride 与 pixel byte count 可由尺寸无溢出地验证；
- 同一逻辑批次内的所有 region 使用同一 generation 和 revision；
- Native 在完整验证并复制批次后才允许其进入 commit；部分有效批次整体拒绝。

## 5. 线程、所有权与背压

职责固定为：

```text
Minecraft render thread
  -> 只记录轻量动画状态
  -> latest-state coalescing
  -> 资源 generation 对应的只读动画源/cache
  -> 编码 RGBA8 region batch
  -> NativeSceneUploadQueue 有序提交
  -> Native scene update accumulator
  -> Cycles 安全更新点应用 image region
```

约束：

- 动画回调不读取 ResourceManager，不解码 PNG，不等待 Native；
- PNG 与原始动画帧只在资源建立阶段读取，运行时编码使用 generation 所有的只读数据；
- 队列按 Sprite 合并为 latest-state，容量有界，不允许动画速度无限制造积压；
- reset 是 generation 屏障，顺序优先于新 generation 的 region；
- close/disable 会丢弃尚未发布的动画状态，不再触发 Native 调用；
- Section mutation 与 texture region 都通过现有单写者上传队列排序，不引入第二写者；
- 不改变固定材质槽、纹理槽、Section ID、mesh topology 或 BVH refit 合同。

## 6. Cycles 原位区域更新硬门槛

A1 只建立固定 Cycles 5.2 上的最小原位 image-region 能力。正式实现必须作为新的项目
patch 进入 `patches/` 和 `setup-cycles.ps1` 的 patch-set fingerprint；不提交或依赖
`.deps/cycles` 中的探索性修改。

A1 通过条件：

- 在现有 `ImageHandle` 和设备图像分配上修改指定二维区域；
- OptiX/CUDA 主目标实际执行二维子区域 host-to-device copy；
- 不新建 ShaderGraph、Material、Section Mesh、Scene 或 Session；
- 不改变图像宽高、颜色角色、采样方式或 texture index；
- 相同输入在 Default 与 experimental DLSS patch set 都能 apply、构建和运行；
- 有 Native 测试证明区域内像素改变、区域外像素保持，并证明资源/图像身份不变。

如果固定 Cycles 5.2 无法在可控 patch 范围内满足这些条件，PBR-A 在 A1 停止。不得
静默退化为每 tick 完整图集上传、完整 Scene rebuild 或 Session 重建。非 CUDA 后端若
无法提供相同语义，也必须显式停止或保留静态帧，不能采用未记录的昂贵回退。

## 7. ABI 与生成式布局计划

A0 保持 ABI 45。只有 A1 原位区域更新门槛通过后，A5 才允许升级 ABI。

计划中的 ABI 46：

- 追加纹理 region batch 描述符和 staging 函数；
- 不修改现有 struct 的字段、大小、offset、enum、flag 或函数语义；
- 不修改配置序列化、资源 ID 或设置默认值；
- Java 与 Native 继续严格拒绝不同 ABI 版本，Jar 与 DLL 必须一起构建和发布；
- region 描述符与 pixel byte buffer 分离，公共描述符只使用固定宽度标量。

新增稳定结构使用独立 JSON schema，例如
`abi/cycles_bridge_texture_region_update.json`。现有生成器继续负责 Java FFM
`MemoryLayout`、offset 常量以及 Native `sizeof/offsetof` 断言；C++ struct 仍在新的
公共头文件中显式定义。不会把整个 `cycles_bridge.h` 或全部历史 ABI 迁移为生成代码，
也不会修改现有 Vulkan/reprojection schema。

精确字段、大小和函数签名在 A5 进入稳定合同前，必须由 A1/A4 的内部模型与测试先
证明；A0 不预先分配未经验证的 ABI 数值。

## 8. 分阶段提交

| 阶段 | 独立结果 | 主要稳定边界 |
| --- | --- | --- |
| A0 | 本文、范围和 go/no-go 门槛 | 不改运行时 |
| A1 | Cycles 原位 image-region patch 与独立测试 | 固定 upstream patch set |
| A2 | Java 离散/插值状态跟踪与合并测试 | 不改 ABI |
| A3 | 四图集 region 编码与逐字节测试 | 保持现有 LabPBR 解码语义 |
| A4 | Native 内部 region 累积、排序和应用 | 不公开新 ABI |
| A5 | 生成式 descriptor 布局、ABI 46 与 Java FFM | 单次原子跨语言合同提交 |
| A6 | Minecraft 资源生命周期与上传队列接线 | 单写者与 generation 屏障 |
| A7 | Default/DLSS 综合 smoke、诊断和文档收口 | 不扩展功能范围 |
| V2 | 用户游戏内动画验收记录 | 收到结果后单独提交 |

每个 A 子阶段必须形成独立、自洽、可验证的 Git 提交。前一阶段的测试与 diff 审核
完成后才能进入下一阶段。A1、A4 或 A5 如果发现需要扩大 ABI、patch 或生命周期范围，
必须停止并重新规划。

## 9. 自动验证与 V2 边界

自动验证至少覆盖：

- Java 离散帧、插值帧、重复状态、乱序事件、identity 和 generation 失效；
- Base/Normal/Material/Auxiliary region 逐字节结果与边界验证；
- Native 非法 index、越界 rectangle、stride、byte count、revision 和部分批次拒绝；
- 同一 Sprite latest-wins，以及 commit 后新事件不会被旧 acknowledge 删除；
- 区域内改变、区域外保持、图像身份保持、Scene/Session 不重建；
- 资源 reset、F8 disable/re-enable、close 与旧 generation 隔离；
- Default 与 experimental DLSS 的 patch apply、构建和 focused CTest；
- Java tests、`git diff --check` 和提交文件白名单。

V2 由用户在 A7 后执行，至少观察离散和插值动画方块的 Combined、Normal、Roughness
与 Emission，并覆盖资源重载、资源包切换、F8 和退出。V2 不验证真实置换、实体、
独立 CTM、oldPBR 或 Distant Horizons。

## 10. 明确不在本里程碑

- 真实 displacement、linear subdivision 或 micro-mesh；
- 实体、方块实体、物品、手持物品或自定义渲染器；
- 独立 CTM 邻接、模型替换或连接规则；
- oldPBR 或其他未声明格式；
- Distant Horizons 或任何已删除的 DH/legacy debug 代码；
- 新配置键、动画速度控制、材质调参或 UI；
- 与动画正确性无关的 BVH、重投影、降噪、DLSS 算法或线程调度改造。

## 11. A1 实施结果

A1 新增正式 patch `cycles-v5.2-image-region-update.patch`，并将它登记在
`setup-cycles.ps1` 的固定 patch 序列中。该 patch：

- 为既有 `device_image` 增加按二维区域逐行上传入口；
- 在 CUDA resident image 上使用带 byte offset/range 的 `cuMemcpy2DUnaligned`，
  保持 device allocation 与 texture object identity；
- 为 `ImageManager` 增加既有 `ImageHandle` 的 RGBA8 region 更新入口；
- 在写入 Cycles host image 前复用原 metadata 的 alpha/colorspace conform 语义；
- 拒绝未加载、非 RGBA8、缩放后尺寸不一致、越界或 stride 不足的更新；
- CPU unified image memory 不执行无意义的重新分配或复制。

Default 与 experimental DLSS 受控源码树均通过 patch 正向/反向检查；受影响的
`memory.cpp`、CUDA `device_impl.cpp` 和 `image.cpp` 均通过 MSVC `/Zs` 独立语法编译。
完整 MSBuild 在当前沙箱中被父环境的 `Path/PATH` 重复键阻断，未形成 Native link 或
运行时像素证据；A4/A7 仍必须补齐该证据，不能从语法编译推断运行时通过。

## 12. A2 实施结果

A2 将 Minecraft `AnimationState.drawToAtlas` 的实际离散/插值选择记录为轻量状态：

- 离散状态只以当前 image frame 为有效内容，忽略 next/progress 差异；
- 插值状态记录 old frame、next frame 和 Minecraft 已量化的 `0..999` progress；
- 每个 Sprite 仅保留最新 dirty 状态，不同 Sprite 按单调 sequence 发布；
- 等价状态不重复发布，旧 snapshot 的 acknowledge 不会删除其后到达的新状态；
- `SpriteContents` identity 替换不会误用旧帧，弱 identity 失效后状态可被回收；
- generation barrier 清空当前与 dirty 状态，并拒绝旧 generation 事件。

状态跟踪只执行内存操作，不读取资源、不解码图片、不调用 Native。A2 还没有把 generation
barrier 或 pending/acknowledge 接入资源生命周期和上传队列；这些接线仍属于 A6。

针对当前 Minecraft 26.2 patched bytecode 的静态检查确认 `oldFrame`、
`frameProgressAsInt`、`newFrame` 分别是整数 local ordinal `0/1/2`，插值分支中的
`NextSprite` 是目标 `bindTexture` 的 ordinal `1`。完整 Java tests 与 Jar 组装通过；
Mixin 注入的游戏内应用仍需 A7/V2 运行时验证。

## 13. A3 实施结果

A3 新增纯 Java `LabPbrAnimationRegionEncoder`，一个合并后的动画状态只产生一个固定
四槽批次。四个 RGBA8 region 共享 Sprite 的 atlas rectangle、generation 和 revision，
并按 Color、Normal、Material、Auxiliary 的 `0..3` 顺序持有独立像素缓冲。

插值 oracle 直接对应当前 Minecraft shader：量化整数 progress 先除以 `1000.0F`，
RGBA8_UNORM channel 转为 float 后按 GLSL `mix` 的
`current * (1 - factor) + next * factor` 计算，最后 clamp 并转换回 RGBA8。规范允许精确
中点选择任一最近整数，因此 CPU 合同使用 `Math.round` 固定中点向上，避免结果依赖驱动；
A7/V2 仍需在目标 Vulkan 后端观察可见一致性。

Normal 与 Specular companion 在原始输入 channel 上完成上述插值后，才复用现有
LabPBR normal/material decode；这避免混合已重建的 normal Z、metal classification、
emission 或 auxiliary channel。缺失 companion 会输出现有中性 fallback，但批次仍完整。

A3 还验证并拒绝 Sprite/generation 不匹配、无效进度、越界 atlas rectangle、不完整帧
网格、错误 RGBA8 stride/byte count 和非固定槽序。8 项 focused tests 覆盖离散、插值、
逐字节四槽输出、fallback、输入所有权、row order 与非法输入；完整 Java tests 与 Jar
组装通过。资源建立阶段如何填充只读 `FramePixels`、何时编码和提交仍属于 A6。

## 14. A4 实施结果

A4 新增 Native 私有 `TextureRegionUpdateAccumulator`，ABI 保持 45。一个待提交批次必须
包含固定 `0..3` 四槽、相同 rectangle、紧密 RGBA8 stride 和完整像素所有权；atlas
布局、越界、generation、revision 或部分批次不合法时整体拒绝。

累积器按 Sprite 保留最新 revision，commit snapshot 按 revision、Sprite index 稳定排序。
acknowledge 只删除与 snapshot 同一共享对象的项，因此 commit 之后到达的新 revision 不会
被旧 acknowledge 误删。reset 只接受严格递增 generation，并原子清空旧 generation 的
revision 与 pending 状态。该对象本身不提供第二套锁；A5/A6 必须把它置于现有单写者和
`request_mutex_` 顺序内。

`SceneRuntime` 现在保留创建材质时使用的四个 `ImageHandle`。私有应用适配器会先验证
全部 region 与 resident image，再调用 A1 的 `ImageManager::update_image_region`，并在
每次写入后确认 `device_image` identity 未变化。A4 没有新增 bridge 导出函数，也没有把
累积器接入 worker；因此区域更新尚不能从 Java 发布，实际公开链路属于 A5/A6。

新增第 8 项 CTest 覆盖 latest-wins、排序、旧 acknowledge、generation barrier，以及
partial/order/bounds/stride/byte-count/revision 拒绝。Default 与 experimental DLSS 均
成功编译并链接 Native DLL、辅助测试和 smoke；两者的 contract、color 与 focused
texture-region 测试通过。Default 的完整 GPU smoke 中 5 项既有 OptiX 渲染测试仍在
camera shift 附近进程崩溃；切换到工作区可写 OptiX cache 后警告消失但崩溃仍可复现。
由于 A4 尚无公开 region 调用，该结果不构成区域写入运行证据，A5/A7 仍必须补齐实际
区域内/外像素与 image identity 验证。

## 15. A5 实施结果

A5 将 bridge ABI 从 45 升级为 46，并只追加一个
`cycles_bridge_stage_texture_region` 导出。所有既有 struct 字段、大小、offset、enum、
flag、配置序列化和导出函数语义保持不变；Jar 与 DLL 因严格 ABI 检查必须配套发布。

新增 `abi/cycles_bridge_texture_region_update.json` 是 88-byte descriptor 的唯一布局源。
同一 schema 为 Java 生成 `MemoryLayout`、byte size 和 offset 常量，也为 C++ 生成
`sizeof/offsetof` 断言。descriptor 使用固定宽度标量记录 generation、revision、Sprite
index、共享 rectangle、紧密 RGBA8 stride/byte count，以及四槽 pixel offset。

pixel buffer 合同固定为四段等长连续数据：

```text
offset 0 * byteCount -> Color
offset 1 * byteCount -> Normal
offset 2 * byteCount -> Material
offset 3 * byteCount -> Auxiliary
capacity              -> 4 * byteCount
```

Native 在复制前拒绝非 canonical offset/capacity、无效 header、零 generation/revision、
尺寸溢出或错误 stride；复制完成后才交给 A4 accumulator。reset 会清空 pending update 并
建立“下一批 generation 必须严格大于上次”的屏障，不依赖 Java 与 Native 各自计数恰好
同步。现有 `commit_scene` 同时冻结 scene 与 texture snapshot；worker 在已有
`request_mutex_` / Cycles scene mutex 单写者路径应用区域，并用共享 snapshot identity
acknowledge，未增加第二写者。

Java 新增拥有像素副本的 `TextureRegionUpdate`、独立 marshaller 和窄入口
`NativeTextureRegions.stage`。生成布局、FFM symbol descriptor、ABI public surface 与
wire offsets 均由 focused contract tests 固定。A6 仍需把 A3 `RegionBatch` 转换为该类型，
并在现有 `NativeSceneUploadQueue` 中按 stage 后 commit 的顺序调用。

Default 与 experimental DLSS 的 A1 `cycles_device` / `cycles_scene` 依赖均已从固定源码树
重建，ABI 46 DLL、辅助测试和 smoke 成功链接。两种 variant 的 ABI contract 与 A4
texture-region CTest 均 2/2 通过；Java focused tests、完整 tests 和 Jar 组装通过。A5
contract smoke 验证合法 staging、重复 revision 拒绝、非 canonical offset 拒绝，以及
reset 后旧/new generation 行为。公开链路的区域内/外像素、device image identity 与
可见动画仍属于 A7 自动 smoke 和 V2 游戏内验收。
