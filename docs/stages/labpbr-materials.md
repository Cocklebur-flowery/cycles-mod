# LabPBR 1.3 材质桥与 PBR-C 第一里程碑

状态：`F0 / F1 / F2 / F3 / F4 DONE`；`V1 AWAITING USER`

当前检查基线：`8202bb3`（2026-08-18，Asia/Shanghai）

目标资源包：`run/resourcepacks/SPBR-21.zip`

目标格式：ShaderLABS LabPBR 1.3

本文从当前源码、ABI 断言、测试和 Git 历史重新建立事实。旧的 PBR-0 至 PBR-12
编号只描述 2026-08-12 至 2026-08-15 的实施过程，不再作为当前阶段状态或 ABI
事实来源。

## 1. 当前实现结论

当前方块 Section 的 LabPBR 主链已经接通：

- 从 Minecraft 最终方块图集中的实际 Sprite 发现 `_n.png` 和 `_s.png`；
- 识别 `format=lab-pbr/1.3`，并支持 `AUTO / OFF / LAB_PBR_1_3`；
- 生成 Base Color、Normal、Material、Auxiliary 共四张同布局图集；
- 支持 DirectX Normal、Roughness、介电 F0、金属、自发光、材质 AO、孔隙度、
  湿润度、SSS 和高度；
- 支持 Bump 与图集边界约束的 Parallax Occlusion Mapping；
- 支持 Cutout、Alpha Blend，以及实验性的玻璃、水和植被材质；
- 资源重载、PBR 模式或 fallback 值变化时完整重建场景资源；
- F9 提供材质控制，F10 提供格式、覆盖率、图集和错误诊断。

当前没有实现真实置换/细分或运行时动画区域上传。实体、方块实体、物品和自定义
渲染器不经过当前 Section 方块图集路径。

## 2. 当前数据流与所有权

```text
Minecraft ResourceManager + AtlasIds.BLOCKS
  -> LabPbrResources：格式声明、Sprite companion 发现
  -> LabPbrAtlasBuilder：Normal / Material / Auxiliary RGBA8 数据图集
  -> SectionSceneResourceBuilder：Base Color + 固定材质/纹理描述符
  -> SectionSceneManager：资源 revision、重建触发和当前诊断状态
  -> NativeSceneUploadQueue：有序异步 Scene reset / section mutation / commit
  -> Java FFM / C ABI 45
  -> Cycles MemoryImageLoader + LabPBR shader graph
  -> realtime Section mesh
```

职责边界：

- `LabPbrResources` 只负责资源发现和覆盖率；
- `LabPbrAtlasBuilder` 只负责 companion 解码和三张数据图集；
- `SectionSceneResourceBuilder` 只负责不可变 SceneResources 描述符；
- `SectionSceneManager` 继续拥有 Minecraft 资源生命周期，不重新吸收图集复制逻辑；
- Native 材质图由 `labpbr_material` 编排，height、parallax、metal 和 surface 逻辑
  分别位于独立私有组件中。

## 3. LabPBR 1.3 输入合同

格式声明位置：

```text
minecraft:optifine/texture.properties
```

支持值：

```properties
format=lab-pbr/1.3
```

标准 Sprite `namespace:path` 的 companion 路径为：

```text
namespace:textures/path_n.png
namespace:textures/path_s.png
```

已经由 Minecraft 或其他模组拼入方块图集的 `optifine/ctm` 与 `mcpatcher/ctm`
Sprite 还会从资源根和 `textures/` 根依次查找 companion。桥本身不实现 CTM 邻接、
模型替换或连接规则。

| 输入 | LabPBR 1.3 语义 | 当前 Cycles 输入 |
| --- | --- | --- |
| `_n.RG` | DirectX 切线空间 X/Y | 重建 Z 后写入 Normal RGB |
| `_n.B` | 线性材质 AO | 写入 Auxiliary R，普通非透射表面乘入 Albedo |
| `_n.A` | 线性高度/深度 | 写入 Auxiliary G，供 Bump/POM 使用 |
| `_s.R` | 感知光滑度 | 写入 `1 - smoothness`，由 Cycles Roughness socket 形成 GGX alpha |
| `_s.G 0..229` | 线性介电 F0 | 写入 Material B，再换算为 Specular IOR Level |
| `_s.G 230..237` | 八种预定义金属 | 使用对应复折射率 conductor closure |
| `_s.G 238..255` | 金属范围 | 使用 Base Color 的通用 metallic fallback |
| `_s.B 0..64` | 孔隙度 | 写入 Auxiliary A，按全局 Wetness 调制 |
| `_s.B 65..255` | SSS | 写入 Auxiliary A，驱动 Principled Subsurface Weight |
| `_s.A 0..254` | 线性自发光 | 归一化写入 Material A |
| `_s.A 255` | 忽略/无自发光 | 写入零强度 |

缺失 companion 时使用：中性切线法线、配置 fallback roughness、零 metallic、
配置 fallback F0、零 emission、AO 1.0、内部中性高度和零 porosity/SSS。尺寸不匹配
或解码失败只回退对应 Sprite 区域。

## 4. SceneResources 与稳定 ABI

当前公共 ABI 为 45。PBR 继续使用现有稳定结构：

- `CyclesBridgeMaterial`：32 bytes；
- `CyclesBridgeTexture`：32 bytes；
- `CyclesBridgeRenderSettings`：392 bytes；
- `CyclesBridgeDiagnostics`：672 bytes。

固定纹理槽：

| 索引 | 资源 | 角色 |
| ---: | --- | --- |
| 0 | Minecraft Block Atlas | `COLOR_SRGB` |
| 1 | `cyclesrenderer:blocks_normal` | `DATA_LINEAR` |
| 2 | `cyclesrenderer:blocks_material` | `DATA_LINEAR` |
| 3 | `cyclesrenderer:blocks_labpbr_auxiliary` | `DATA_LINEAR` |

固定材质槽保留现有数值和顺序：Solid、Cutout、Blend、Glass、Water、Foliage。
Java 与 Native 继续拒绝越界纹理索引、错误纹理角色、未知 PBR 格式、非法 flag
组合及 PBR/非 PBR 索引不一致。

PBR-C 第一里程碑不修改 ABI、结构大小、offset、enum/flag 数值、设置键、默认值、
资源 ID 或 reset 级别。

## 5. 当前材质行为

- 普通材质：Principled Base Color、Normal、Roughness、F0/Metal、AO、Wetness、
  SSS 和 Emission；
- Cutout：固定 alpha cutoff 0.5；
- Blend：使用基础纹理 alpha 混合 Transparent closure；
- Glass：Glass BSDF、有限纹理染色、Fresnel 阴影透射和纹理 alpha 表面混合；
- Water：Principled Transmission 1.0、IOR 1.333、Thin Wall；
- Foliage：专用 Principled 参数；部分植物卡片增加 0.01 block 背面和竖向轮廓壁；
- Height：默认 Bump；可选 POM 使用逐三角形 UV bounds 限制图集采样。

玻璃、水和植被已经存在于当前生产代码。旧文档中“玻璃/水等待 ABI 窗口”以及
“仍未实现玻璃折射和水材质”的表述已失效。

## 6. 动画、覆盖范围与明确延期

`SpriteAnimationStateMixin` 会记录 Minecraft 当前选中的 image frame，资源首次构建和
资源重载时 Base/Normal/Material/Auxiliary 使用同一个 frame。当前不会在动画 tick 后
更新 Native 图集；完整动画需要新的有序纹理区域更新合同，禁止通过每帧完整重建 Scene
模拟。

当前覆盖：

- 原版和使用标准方块图集的 NeoForge 静态 Section 几何；
- 已进入同一方块图集的 CTM Sprite；
- 方块颜色和动态 tint 已进入顶点颜色路径。

明确延期：

- 运行时 Base/Normal/Material/Auxiliary 区域更新；
- 真实置换和线性细分；
- 实体、方块实体、物品、手持物品和自定义渲染器；
- 独立 CTM 邻接/模型规则；
- oldPBR 或其他未声明格式；
- Distant Horizons；
- 与 LabPBR 正确性无关的 BVH、重投影、降噪或线程调度修改。

## 7. 当前资源与验证证据

2026-08-18 对本地 `SPBR-21.zip` 做只读清单检查：

- `format=lab-pbr/1.3`；
- 3055 张 `_n.png`；
- 4379 张 `_s.png`；
- 72 个基础动画 metadata，其中 55 个同时具有 `_n` 和 `_s`；
- 2593 个 `optifine/ctm` 或 `mcpatcher/ctm` 条目。

已有自动证据：

- `LabPbrResourcesTest` 锁定标准/OptiFine/MCPatcher companion 路径；
- `SectionSceneResourceBuilderTest` 锁定 Base/PBR 槽位、纹理角色、材质 flag 和尺寸
  mismatch fallback；
- `FoliageSolidifierTest` 锁定轮廓、背面、竖向壁和 partial UV 保护；
- Java/Native ABI contract 覆盖 PBR texture indexes、roles 和非法组合；
- Native render smoke 能创建 LabPBR shader 并证明基础纹理出帧。

PBR-C 新增自动证据：

- `LabPbrAtlasBuilderTest` 逐字节锁定 Normal、Roughness、F0/Metal、Emission、AO、
  Height 和 encoded surface；
- `cyclesrenderer_smoke_pbr` 使用活动三角形分别渲染 Cutout、Glass 和 Water，并锁定
  Normal、Diffuse、Emission、Roughness、Combined pass 不坍缩；
- `SectionMaterialCaptureTest` 锁定玻璃板、染色玻璃板、水 sprite、植被类别和
  solidification 完整 UV 条件；
- F10 按实际资源拓扑报告三张 PBR 数据图集。

`60e43dc` 上的 V0 综合实机矩阵曾覆盖方块、玻璃、水、植被和 LabPBR 呈现并由用户
确认未见异常；它不等于逐通道数值或同场景视觉 oracle。当前 PBR-C 完成后由用户执行
新的 V1 定向实机矩阵。

## 8. PBR-C 第一里程碑

```text
F0  当前事实、ABI 与阶段文档重建                 DONE
F1  LabPBR atlas 逐字节 characterization          DONE
F2  独立 Native PBR material smoke               DONE
F3  Section glass/water/foliage classification    DONE
F4  F10 PBR 数据图集计数修正                      DONE
V1  用户定向 Minecraft 实机验收                   AWAITING USER
```

每个 F 子阶段必须形成独立提交。任一阶段发现当前实现与测试 oracle 冲突时，保留失败
证据并停止，不把行为修复混入 characterization 提交。F0-F4 不进入运行时动画、真实
置换、ABI 扩展或主观材质调参。

## 9. V1 用户实机矩阵

F4 提交后，由用户使用当前 SPBR-21 同场景检查：

- PBR `OFF / AUTO`；
- Normal Strength `0 / 1`；
- Roughness、介电 F0、金属和 Emission；
- Wetness `0 / 1` 与 SSS Scale `0 / 当前值`；
- Bump / POM 的正视和掠射角；
- Glass、Water、Leaves 和 solidified plants；
- 资源重载、资源包切换、F8 关闭/重新启用和退出；
- F10 requested/effective、coverage、三张数据图集、错误计数；
- default 与 experimental DLSS 的实际执行边界。

V1 结果必须逐项记录 `PASS / FAIL / NOT RUN`。F0-F4 提交不预先宣称 V1 通过。
