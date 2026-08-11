# Cycles 降噪调度（P10）

状态：P10a 已实现，等待游戏内人工验收
目标平台：Cycles 5.2、OptiX、OpenImageDenoise 2.5

## 1. 调度契约

降噪器的“配置选择”“设备可用”“当前实际运行”是三个不同状态。能力查询继续报告设备可用性；`effective_denoiser` 只报告当前渲染是否真的启用了降噪，不把已选择但本帧未调度的降噪器显示为生效。

第一版实时调度规则：

| 采样状态 / Pass | 结果 | 原因 |
| --- | --- | --- |
| Interactive Combined | Raw | 输入优先，避免每次移动都运行降噪器 |
| Settling Combined | 沿用 Interactive Raw 帧 | 等待 stationary delay，不启动额外渲染 |
| Still Combined | 按配置运行 OptiX/OIDN | 静止后产出可缓存的 Denoised 结果 |
| 任意调试 Pass | Raw | 保持 Depth/Normal/Roughness/Sample Count 的数值语义 |
| 降噪器关闭或不可用 | Raw | 准确回退，不伪造 Denoised variant |

Raw 与 Denoised 继续使用 P9 的独立缓存键。进入 Still 产生的新结果才能标记为 Denoised；交互帧不能污染 Denoised cache。

## 2. 子阶段

- P10a：落实 Interactive Raw / Still Denoised，并用 OptiX Smoke 验证状态转换（已完成）。
- P10b：ABI/F10 增加调度原因、有效起始 sample、运行与跳过计数。
- P10c：用本地 Blender Windows 依赖重建 Cycles，启用 OIDN 2.5 并验证 DLL 部署与实际渲染。

P10c 不从系统目录寻找或安装依赖；只允许使用 `.deps/cycles/lib/windows_x64/openimagedenoise` 中已经下载的头文件、导入库和运行时 DLL。若链接或运行闭包不完整，停止并报告缺失文件。

## 3. 验证

Native Smoke 在 RTX/OptiX 可用时必须观察到：启用设置后的首个 Interactive 帧为 Raw、`effective_denoiser=Off`；静止延迟后 Combined 变为 Denoised 且 `effective_denoiser=OptiX`；随后 Pass cache 仍同时保留 Combined Denoised 与调试 Pass Raw。

游戏内人工验证保留到 P10 完成后：移动鼠标期间画面响应不被逐帧降噪拖慢；停止后画面切换为降噪结果；再次移动不会显示旧相机的 Denoised 缓存。
