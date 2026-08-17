# 2D 异步深度重投影

状态：`2D-R0`、`2D-R1` 完成；`2D-R2`～`2D-R6` 未实现
设计基线：`337d3bf`
生产基线：ABI43、单 Cycles Session、现有 Vulkan 三槽 interop

## 1. 目标

2D 只实现源帧解耦的异步深度重投影。Cycles 可以低频产生真实颜色和深度，Minecraft
仍在每个显示帧使用最新相机重投影最后一组完整源帧，使转向、步行、横移和跳跃不必
等待下一张 Cycles 帧。

2D 改善相机响应和低源帧率下的显示连续性，不提高 Cycles 源 FPS，也不生成新的世界、
光照、阴影、实体动画或方块状态。文档中的“异步”只表示显示帧与 Cycles 源帧解耦；
第一版不建立独立 compositor、独立线程、第二条 Vulkan queue 或 scanout 前 late latch。

## 2. 明确排除

- 不实现自适应质量，不自动改变分辨率、动态分辨率比例、sample 或时间限制。
- 不修改 Scene mutation、Session reset、BVH、BLAS/TLAS、Section slot 或上传调度。
- 不建立 Active/Staging 双 Session，不暂停 Active，不在运行中替换 display driver。
- 不引入 micro-mesh，不新增或修改 Cycles 上游 patch。
- 不改变 DLSS history invalidation、降噪、PBR、色彩管理、HDR 或 Post DoF 语义。
- 不依赖或冒充 NVIDIA Reflex 2 Frame Warp；官方 SDK 集成属于未来独立阶段。
- 不为 CPU frame fallback 伪造深度；第一版只在 Vulkan interop 能提供一致深度时启用。

以上任一责任成为完成 2D 的前提时，立即停止并重新规划，不扩大当前阶段。

## 3. 当前事实

- Java 与 Native 当前 ABI 均为 43。
- Vulkan interop state 为 80 字节，报告颜色尺寸、generation、slot 和深度尺寸。
- interop 已能把 `RGBA16_FLOAT` 颜色与 `R32_FLOAT` 深度复制到 Minecraft Vulkan 纹理。
- Native 当前只在 Post Process DoF 需要时导出深度；重投影不能继续依赖该偶然条件。
- Presenter 能消费颜色与深度，但只有当前相机参数，没有与源颜色严格匹配的源相机。
- 当前 pipeline 顺序是可选 Post DoF、自动曝光、显示变换和主目标 presentation。

2D 不从已废弃 C2 复制 ABI44 数值、布局、Shader 或相机历史实现。任何新契约都从 ABI43
和当前源代码重新推导。

## 4. 帧包与所有权

一次可重投影源帧是不可拆分的 `ReprojectionFrameBundle`：

```text
interop generation + slot
  ├─ source RGBA16F color
  ├─ source R32F depth
  └─ source frame metadata
       ├─ scene / camera / frame revision
       ├─ production monotonic timestamp
       ├─ source position and orientation
       ├─ projection, FOV, aspect and shift
       ├─ near/far and depth semantic
       └─ color/depth dimensions
```

Native 在颜色与深度完成同一个 slot 时冻结 metadata。Java 只在 metadata generation、
interop generation 和 slot 全部相同时接受帧包；不一致时拒绝重投影，不等待、不猜测，
也不把旧 metadata 配给新颜色。

源帧包由 interop/presenter 持有到下一组完整 generation 到达或 renderer reset。当前相机
是每个 Minecraft 显示帧的借用快照，不回写源帧包。F8 disable、world unload、interop
rebuild 和 close 必须使两者同时失效，重复关闭保持幂等。

完整相机 metadata 使用独立 ABI 结构，不把大段矩阵或相机字段继续追加到 80 字节
interop transport state。新结构必须有 `struct_size`、`struct_version`、generation 匹配和
明确的旧版本拒绝路径。

## 5. 深度与投影契约

`2D-R1` 必须先从当前 Cycles Depth pass 和 Post DoF 消费路径确认实际深度语义，禁止先假定
它是 clip depth、view-space Z 或 ray distance。契约冻结后才允许进入 ABI 阶段。

R1 源码审计冻结以下事实：

- 普通 `PASS_DEPTH` 写入 `camera_z_depth(kg, sd->P)`；透视相机返回 Cycles
  `worldtocamera` 的 Z，不是归一化设备/clip depth，也不是从相机到命中点的射线长度。
- 当前 Native 相机矩阵把 Minecraft 本地 `-Z` 前方翻转为 Cycles 相机 `+Z`，因此 Java
  侧契约中的正深度是 `-Minecraft local Z`，即轴向深度。
- 无命中时 pass accessor 把零值转换为 `1e10`。Oracle 不依赖该哨兵常量，而是统一拒绝
  非有限、非正以及超出源 near/far 的值。
- 离轴像素必须按轴向深度反投影：先构造本地透视方向，再以
  `axialDepth / -direction.z` 求射线距离。把 R32F 数值直接当射线长度是错误语义。
- Post Process DoF 的 focus distance 同样使用透视轴向距离，所以重投影输出的目标深度
  继续保持 `-targetLocal.z`，无需改变现有 DoF 单位。

`DepthReprojectionMath` 是 R1 的 CPU reference oracle：使用现有 viewplane 的 FOV、aspect、
shift 公式，按 Minecraft quaternion 的 local-to-world 顺序重建世界点，再按目标相机的逆旋转
投影。它以 nearest-depth forward splat 规定遮挡胜者；没有获胜源样本的目标像素保持 invalid，
不在 oracle 中填洞。GPU 实现必须与此数值契约交叉验证。

第一版只支持普通透视相机，并要求：

- 颜色、深度和源相机来自同一渲染请求与 generation。
- 深度尺寸和采样映射明确；首版使用完整源渲染尺寸的 R32F 深度。
- 当前相机与源相机使用同一世界坐标、轴方向、手性和 projection 约定。
- 无效、非有限、背景和越界深度产生 invalid pixel，不参与遮挡竞争。
- 多个源像素投向同一目标位置时，离当前相机最近的有效深度获胜。
- 相机完全相同时，重投影不得改变有效像素位置或深度。

全景、鱼眼、非透视 projection 和 Physical DoF 在第一版自动旁路重投影并报告原因。
Post Process DoF 在重投影完成后使用重投影后的颜色和深度；色彩管理、曝光和 HDR 显示
仍位于其后。

## 6. 显示帧调度

Cycles 发布新 generation 时更新源帧包；没有新 generation 时继续保留旧帧包。每次
Minecraft world display callback 都重新采集当前相机并执行一次 presentation：

```text
latest complete source bundle
  + current Minecraft camera
  -> depth reprojection
  -> reprojected HDR color + depth + invalid mask
  -> optional Post DoF
  -> auto exposure / OCIO / HDR transform
  -> main target
```

重投影不得等待下一张 Cycles 帧、Native scene revision、CPU readback、interop semaphore
之外的新同步点或其他线程。即使相同源 generation 被显示多次，也不能伪增 Native
produced-frame 计数。

如果源帧包缺失、相机/深度不受支持、metadata 不匹配、GPU stage 失败或 invalid coverage
超过验证阶段确定的安全上限，本显示帧旁路重投影并显示未重投影源颜色。禁止黑屏、清空
上一张有效纹理或自动修改渲染质量。小孔洞只允许有界、保守的邻域填充；2D 不实现预测
式 in-painting。

## 7. 性能菜单与诊断

`2D-R5` 在现有 F9 设置编辑器新增 `PERFORMANCE` 分类。第一版只增加稳定配置键：

```text
performance.reprojection.enabled = false
```

默认关闭，关闭时不得导出仅供重投影使用的深度、分配重投影目标或执行重投影 pass。
不增加“同步/异步”双路径开关；启用即表示显示帧与源帧解耦。不暴露孔洞半径、深度阈值、
内部 dispatch 尺寸或 Reflex 伪开关。现有动态分辨率继续属于 Output 分类且语义不变。

F10 只读诊断至少报告：requested/actual、source age、source/current camera revision、源与
深度尺寸、GPU 时间、invalid coverage、旁路次数和最近旁路原因。诊断不能替代正确性门禁。

## 8. 分阶段实现与提交

每个子阶段只有一个责任、验证通过后立即提交；当前阶段未提交时禁止开始下一阶段。

1. `2D-R0`：本设计、范围、所有权、降级和验证矩阵。
   - Commit：`docs(runtime): define asynchronous depth reprojection`
2. `2D-R1`：深度语义、坐标约定和 CPU reference oracle。
   - Commit：`test(presentation): characterize depth reprojection math`
3. `2D-R2`：独立源帧 metadata ABI、Java/Native layout 和拒绝测试。
   - Commit：`feat(abi): expose reprojection frame metadata`
4. `2D-R3`：Native 发布 generation 一致的颜色、深度和 metadata 帧包。
   - Commit：`feat(interop): publish matched reprojection inputs`
5. `2D-R4`：开发属性门控的 Vulkan 显示帧深度重投影，不增加正式设置。
   - Commit：`feat(presentation): add gated depth reprojection`
6. `2D-R5`：F9 Performance 分类、持久开关和明确 invalidation。
   - Commit：`feat(config): expose reprojection controls`
7. `2D-R6`：F10 诊断、双变体实机矩阵和最终证据收口。
   - Commit：`docs(runtime): record 2D reprojection evidence`

新文件必须在创建阶段立即进入可见 diff。提交只暂存当前阶段批准的精确路径；A1、D3
和其他实验内容不得混入 2D 提交。

## 9. 验证矩阵

### 2D-R1 数值 oracle

- identity、旋转、平移、FOV、aspect、shift 和 near/far。
- 近处视差大于远处，方向与 CPU reference 一致。
- nearest-depth 遮挡、越界、背景、NaN/Inf 和 invalid mask。
- 错误矩阵顺序、坐标手性或深度语义必须能让测试失败。

### ABI 与 Native

- schema/generator、Java/Native size、alignment、offset、enum 和 mismatch rejection。
- generation/slot 匹配与故意错配拒绝。
- default 与 experimental DLSS 完整 `verifyProject`。

### Vulkan 与 Minecraft

- 重投影关闭时与 ABI43 基线行为一致且没有额外深度/目标分配。
- 开启后转向、步行、横移、跳跃、贴近方块和快速 180 度转向。
- 相同源 generation 下当前相机变化能更新显示，世界内容不会被宣称为新帧。
- 新源 generation 到达时无闪黑、旧 metadata、错 slot 或一帧 CPU fallback。
- 窗口缩放、F8 disable/re-enable、world unload、interop rebuild 和正常退出。
- Post DoF/关闭 DoF；Physical DoF、全景和鱼眼验证明确旁路。

GPU pass 时间、显存和 invalid coverage 必须记录，但 R0 不预设未经测量的性能百分比。
编译成功、单个 smoke frame 或主观“更流畅”都不等于 2D 验收通过。

## 10. 停止条件

- 深度语义无法与源相机严格对应。
- 颜色、深度和 metadata 无法以同一 generation 原子匹配。
- 实现需要修改 Session、Scene、BVH、DLSS history 或动态分辨率。
- 重投影关闭仍产生额外资源、同步或可见画面变化。
- invalid 区域需要无界填充或以旧像素伪造大面积新内容。
- 任一阶段需要跨越已确认的文件、模块、ABI 或生命周期预算。
- default/DLSS 门禁或对应实机阶段失败，且根因无法限制在当前提交。

触发停止条件后不得继续堆叠后续阶段。保留当前失败证据，报告最小决策点，并从最近一次
已验证提交重新规划。

## 11. 外部技术边界

- OpenXR depth composition 说明深度可让 runtime 执行更准确的逐像素重投影：
  <https://registry.khronos.org/OpenXR/specs/1.0-khr/html/xrspec.html>
- NVIDIA Reflex 2 Frame Warp 使用最新输入、历史相机、颜色和深度执行专用 late warp；
  2D 不声称等价，也不依赖尚未通用开放的 Frame Warp 集成：
  <https://developer.nvidia.com/performance-rendering-tools/reflex>
