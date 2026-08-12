# Cycles 程序大气阶段

## 已实现范围

世界背景使用 Cycles 5.2 原生 `Sky Texture` 的 `Multiple Scattering` 模型，并接到 World `Background`。场景同时创建启用 MIS 的 Cycles `BackgroundLight`，因此天空不只对相机可见，也会被作为环境发光体主动采样；Sky Texture 自身的坐标映射把 Minecraft 的 Y-up 转换为 Cycles 天空模型的 Z-up，并保留解析太阳引导。太阳圆盘、太阳直射和天空漫反射间接光都进入同一套路径追踪积分。

Cycles 的 Fast GI Approximation 保持关闭：`ao_bounces`、`ao_factor` 与 `ao_additive_factor` 显式为零。方块间的亮度传递来自真实 diffuse bounce，而不是用 AO 替代的近似 GI。关闭“太阳圆盘”只移除天空模型中的太阳圆盘/直射成分，天空环境本身仍会照亮场景，这与 Blender 的 World Sky 行为一致。

F9 的 NeoForge 客户端配置新增 `atmosphere` 分组：

| 参数 | 默认值 | 范围 |
| --- | ---: | ---: |
| 太阳圆盘 | 开 | 开 / 关 |
| 太阳角直径 | 0.545° | 0.01°–180° |
| 太阳强度 | 1.0 | 0–1000 |
| 太阳高度 | 45° | -90°–90° |
| 太阳旋转 | 35° | -360°–360° |
| 海拔 | 1000 m | 0–60000 m |
| 空气密度 | 1.0 | 0–10 |
| 气溶胶密度 | 1.0 | 0–10 |
| 臭氧密度 | 2.0 | 0–10 |

Java 与 Native 的设置契约已升级到 ABI v26。参数保存后异步发送给 Cycles 工作线程，无需重启客户端，也不会重新收集 Minecraft Section 或重建 Java 侧共享图集。F10 会显示当前太阳角度、大小、强度与三项密度。

Cycles 5.2 在当前持续 Session 内原地替换 World shader 后会停在 `rendering` 且不再产出帧。为避免交付卡死路径，大气参数修改采用受控 Session reset：取消旧渲染，使用内存中已有的资源和 Section 快照建立新 Session，再从交互采样开始。它不会触发 Minecraft Section 重新编译或 Java 场景捕获，但 Native 会重新创建 Cycles 场景和设备侧加速结构，因此应用设置时可能短暂停顿。这一限制保留到后续专门的 Cycles scene-update 同步阶段处理。

## 当前边界

- 大气模型暂时固定为 `Multiple Scattering`，没有开放使用不同参数体系的旧式 Preetham/Hosek-Wilkie 模型。
- 参数暂未自动跟随 Minecraft 世界时间、维度、天气、雨雪或生物群系。
- 目前只有 World surface 天空照明；未实现空气透视、体积雾、云层或天气体积。
- 太阳是 Cycles 天空节点的一部分；尚未建立独立 Minecraft 日月天体、月相和星空。
- Minecraft 编译区块提供的顶点颜色当前仍同时承载生物群系染色和原版烘焙亮度/AO，材质桥会把它乘入基础色。因此世界光修复后仍可能残留一部分原版 AO 外观；不能直接丢弃该通道，否则草、树叶等生物群系染色会一起丢失。后续材质桥阶段需要拆分“纯染色”和“烘焙光照”。
- 大气修改会重建 Cycles Session、丢弃旧累积结果并重新采样；短暂停顿和噪声恢复是当前预期行为，不适合每帧驱动太阳位置。

## 验证契约

原生 smoke 覆盖：ABI v26、参数范围、程序天空空场景、运行时修改太阳高度/旋转后受控重建并产生新帧，以及重建前后 Section 数量与 scene-delta 计数不变。游戏内仍需验证 F9 保存、F10 显示、不同太阳高度下的地平线方向、太阳盘可见性、直射阴影和背光区域的漫反射回弹。
