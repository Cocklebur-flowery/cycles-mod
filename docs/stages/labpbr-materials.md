# LabPBR 1.3 材质桥（PBR-0）

状态：PBR-0 至 PBR-5 已完成；PBR-6 自动验收已完成，待游戏内视觉验收
目标资源包：`run/resourcepacks/SPBR-21.zip`
目标格式：ShaderLABS LabPBR 1.3

## 1. 本阶段目标

把 Minecraft 已拼接进方块纹理图集的精灵及其 LabPBR 伴随纹理转换成 Cycles 可直接采样的材质数据。第一版支持：

- 原版与使用标准方块图集的 NeoForge 模组纹理；
- LabPBR 1.3 `_n` 法线纹理；
- LabPBR 1.3 `_s` 粗糙度、介电 F0、金属和逐像素自发光；
- 资源包切换和资源重载后的完整重建；
- 缺图、尺寸不匹配和不支持格式的安全回退；
- F9 控制与 F10 覆盖率、图集内存和有效通道诊断。

本阶段不改变 Minecraft 区块几何捕获、世界帧接管、Vulkan swapchain、Pass ID、RGBA16F 帧租约以及 F8/F9/F10 键位。

## 2. 数据流与兼容边界

```text
Minecraft ResourceManager（已应用资源包优先级）
  -> 方块图集中的实际 Sprite 标识符
  -> 基础纹理 + 同命名 _n.png / _s.png
  -> 与基础图集完全相同的槽位和 UV 布局
  -> Base Color / Cycles Normal / Cycles Material 三张图集
  -> Java SceneResources
  -> FFM / C ABI
  -> Cycles ImageTexture + Principled BSDF
```

只扫描 Minecraft 实际拼接进方块图集的 Sprite，而不是遍历 ZIP 中所有 PNG。因此：

- 标准方块模型和标准图集纹理可以自动继承 PBR；
- 仅存在于 OptiFine CTM 目录、实体、物品、方块实体或自定义渲染器中的纹理，不会被误报为已支持；
- MTR 等自定义几何只有在其最终进入当前 Section 方块图集捕获路径时才会自动兼容；
- Distant Horizons 的 LOD 几何与材质仍属于独立兼容层，本阶段不承诺 PBR；
- 不猜测其他旧 PBR 格式。未声明 `format=lab-pbr/1.3` 时使用普通材质回退。

## 3. LabPBR 1.3 输入合约

格式声明从最终 ResourceManager 中的 `minecraft:optifine/texture.properties` 读取。支持值固定为：

```properties
format=lab-pbr/1.3
```

对基础 Sprite `namespace:path`，伴随资源为：

```text
namespace:textures/path_n.png
namespace:textures/path_s.png
```

原始通道语义：

| 纹理 | 通道 | LabPBR 1.3 语义 | 第一版处理 |
| --- | --- | --- | --- |
| `_n` | R/G | DirectX 切线空间 X/Y | 解码并重建 Z，交给 Cycles DirectX Normal Map |
| `_n` | B | 材质 AO | 检测并统计，不乘入 Base Color |
| `_n` | A | 高度/位移 | 保留格式能力信息，第一版不做 POM/位移 |
| `_s` | R | 感知光滑度 | 转为 Principled Roughness 输入 `1 - smoothness` |
| `_s` | G 0..229 | 线性介电 F0 | 转为 Principled Specular IOR Level |
| `_s` | G 230..255 | 预定义/反照率金属 | 第一版统一作为 Metalness 1，颜色取 Base Color |
| `_s` | B | 孔隙度或 SSS | 检测并统计，第一版不接入着色器 |
| `_s` | A 0..254 | 自发光强度 | 使用 Base Color 作为发光颜色并按强度缩放 |
| `_s` | A 255 | 忽略/无自发光 | 转为零自发光 |

LabPBR 文档给出的线性粗糙度为 `(1 - smoothness)^2`。Cycles Principled 会在内部把 Roughness 输入平方成微表面 alpha，因此桥接层输入 `1 - smoothness`，不能预先再次平方。

介电 F0 使用 Principled 默认 IOR 1.5 的基准 F0 0.04，换算为：

```text
specular_ior_level = F0 / 0.08
```

这样 Principled 内部的 `F0 = 0.04 * 2 * specular_ior_level` 可恢复原始 F0。

## 4. Cycles 数据图集

现有 Section 顶点继续只携带全局方块图集 UV，不增加逐顶点 PBR 字段。资源构建阶段生成三张尺寸、槽位和 UV 完全一致的图集：

1. `Base Color atlas`：当前 Minecraft 方块颜色图集，按 sRGB 颜色纹理加载。
2. `Cycles Normal atlas`：线性数据纹理。CPU 将 LabPBR R/G 解码为 `[-1, 1]`，按 `sqrt(max(0, 1-x²-y²))` 重建 Z，再编码成 RGB。
3. `Cycles Material atlas`：线性数据纹理，通道预解码为：
   - R：Principled Roughness 输入；
   - G：Metalness；
   - B：线性介电 F0；
   - A：归一化逐像素 Emission。

Material atlas 第一版使用 RGBA8。介电 F0 的有效范围可高于 0.08，换算后的 Specular IOR Level 也会高于 1，不能直接编码进 UNORM 通道。因此 B 保存原始线性 F0，Cycles 节点图采样后再执行 `F0 / 0.08`。这不会改变上一节定义的 Principled 换算关系。

Normal 与 Material 图集不是对原始 `_n`/`_s` 文件的无损复制，而是当前 Cycles shader 的稳定输入契约。这样避免每次采样重复执行格式分支，也避免把 `_n.B` 的 AO 错当法线 Z。

缺失纹理默认值：

| 缺失项 | 默认值 |
| --- | --- |
| `_n` | 中性切线法线 `(0.5, 0.5, 1.0)` |
| `_s` Roughness | F9 的回退粗糙度，初始沿用当前普通材质 0.8 |
| `_s` Metalness | 0 |
| `_s` F0 | 0.04，即 Specular IOR Level 0.5 |
| `_s` Emission | 0 |

伴随纹理尺寸或动画布局不兼容时，只回退该 Sprite 对应区域，不得破坏整张图集或导致客户端退出。

## 5. ABI 与稳定合约

PBR-3 已在优化线程提交后修改 Java/FFM/C ABI。实施前检查并确认以下路径已无外部未提交改动：

- `native/include/cycles_bridge.h`
- `native/src/cycles_bridge.cpp`
- `native/src/cycles_engine.cpp`
- `native/tests/cycles_bridge_smoke.cpp`
- `src/main/java/dev/cyclesrenderer/nativebridge/NativeBridge.java`

存在未提交的外部改动时，不进入 PBR-3，也不暂存这些文件。

ABI v28 保持 `CyclesBridgeMaterial` 与 `CyclesBridgeTexture` 的 32 字节结构大小不变，并定义了原有保留槽位：

- Material 增加 normal/material texture index 和 PBR 格式标志；
- Texture 增加 `COLOR_SRGB` 或 `DATA_LINEAR` 角色；
- 缺失索引使用 `UINT32_MAX`（Java 侧为 `-1`）；
- ABI 从 v27 递增到 v28，未重排既有字段；
- Java 和 C 两侧都拒绝越界索引、错误纹理角色、未知 PBR 格式及不一致的回退材质；
- Native smoke 覆盖结构大小、偏移、无效索引和纹理角色错误路径。

若当前代码证明保留槽位不足，必须先停止并重新确认结构扩展方案。

## 6. 设置与诊断

F9 第一版开放：

- PBR 模式：`Auto / Off / LabPBR 1.3`；
- Normal Strength；
- Emission Scale；
- 缺失 `_s` 时的 Roughness；
- 缺失 `_s` 时的 dielectric F0。

F10 按动态诊断区显示：

- 检测到的格式、声明来源资源包和当前模式；
- 方块图集 Sprite 总数、`_n`/`_s` 命中数和覆盖率；
- Base/Normal/Material 图集尺寸与总内存；
- Normal、Roughness、F0、Metal、Emission 的实际启用状态；
- 缺失、尺寸不匹配、解码失败和不支持格式计数。

固定格式与配置放在静态信息区，不与 FPS、samples/s、帧延迟等速率数据混排。

## 7. 子阶段与提交边界

- PBR-0：本文档与验收基线。
- PBR-1：ResourceManager 格式检测、伴随纹理发现和覆盖率遥测。
- PBR-2：同布局 Normal/Material 数据图集与缺失默认值。
- PBR-3：Java/FFM/C ABI 纹理角色和材质索引。
- PBR-4：Cycles Principled、DirectX Normal、Roughness、F0/Metal 和 Emission。
- PBR-5：F9 材质设置、ABI 29 着色倍率与 F10 PBR 诊断。
- PBR-6：自动和游戏内验收、文档收口。

每个子阶段独立提交，只暂存该子阶段自己的文件或可精确隔离的 hunk。不得带入其他线程的性能、BVH、场景时序或互操作改动。

## 8. 验收基线

自动验证：

- Java 离线编译通过；
- Native smoke 通过（从 PBR-3 开始）；
- 普通无 PBR 资源包路径行为不变；
- SPBR-21 被识别为 LabPBR 1.3；
- 资源重载后格式、覆盖率和数据图集同步重建；
- 缺图和坏尺寸能回退且有诊断；
- diff 与暂存区不包含外部线程文件。

游戏内手动验证：

- F8 开关、F9 页面、F10 调试仍响应；
- 普通方块基础颜色和透明裁剪不回归；
- 法线朝向正确，移动相机时凹凸不反转；
- 光滑/粗糙表面反射宽度明显不同；
- 金属不再表现为普通介电漫反射；
- 自发光纹理仅在有效像素发光；
- 切换资源包或执行资源重载后材质更新；
- 关闭 PBR 后回到现有普通 Cycles 材质。

## 9. 明确延期

以下内容不属于本阶段完成标准：

- POM、真实位移或细分；
- AO 乘色、孔隙度、潮湿、SSS；
- 预定义金属的精确复折射率；
- 玻璃折射和水材质；
- OptiFine CTM 专用几何/纹理解析；
- 动画 PBR 帧同步；
- 实体、方块实体、物品和自定义模组渲染器；
- Distant Horizons PBR；
- oldPBR 或其他未声明格式；
- 性能热点、BVH 或线程调度优化。

## 10. PBR-6 自动验收记录

2026-08-12 对当前开发资源包 `run/resourcepacks/SPBR-21.zip` 做了只读检查：

- 声明为 `format=lab-pbr/1.3`；
- 包内发现 3055 张 `_n.png` 与 4379 张 `_s.png`；
- Java 离线编译与资源处理通过；
- 公共 ABI 已更新到 29；
- OptiX 原生烟测通过，包含三纹理 LabPBR 场景；
- 原生增量场景更新测试通过。

仍需游戏内确认法线方向、粗糙/金属差异、自发光范围以及 F9/F10 文案；这些视觉结果无法由离线烟测代替。
