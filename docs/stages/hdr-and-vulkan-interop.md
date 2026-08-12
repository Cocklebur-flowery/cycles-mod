# Windows HDR 与 Cycles/Vulkan 互操作 Spike（P13）

状态：技术可行性已确认；实现必须拆成后续独立阶段
审计基线：Minecraft/NeoForge 26.2.0.58、Blender Cycles 5.2、Windows、OptiX、Vulkan 1.2

## 1. 结论

项目可以继续沿两个方向演进，但它们不是同一项功能：

1. **真 HDR 输出**需要改造 Minecraft 创建 Vulkan instance/device/swapchain 的早期流程，选择 HDR surface format/color space，并让最终显示 shader 输出匹配的传递函数与色域。
2. **Cycles 到 Vulkan 的零 CPU 像素拷贝**可以复用 Cycles 5.2 已有 `GraphicsInteropDevice::VULKAN` 路径，让 OptiX/CUDA 写入可导出的 Vulkan `VkBuffer`；之后仍需一次 Vulkan buffer-to-image GPU 复制，并建立明确的 CUDA/Vulkan 同步。

两条路径都涉及 Minecraft Vulkan 设备或资源的稳定契约，不能在当前显示类里局部替换。P13 只冻结事实、接口和停止条件，不修改 swapchain、逻辑设备或 Cycles fork。

## 2. 当前实现事实

当前成品帧数据流为：

```text
OptiX/CUDA render buffer
  -> Cycles DisplayDriver 回读到 Native std::vector<half4>
  -> Native 三槽 FrameStore
  -> Java FFM acquired frame
  -> CommandEncoder.writeToTexture 暂存上传
  -> Minecraft Vulkan RGBA16F VkImage
  -> OCIO/显示 shader
  -> Minecraft SDR 主目标与 swapchain
```

这条路径已避免逐像素 CPU tone-map，但 1080p 每张 RGBA16F 帧仍发生 GPU→CPU 回读和 CPU→GPU 上传。F10 的 Native display/copy 与 Vulkan upload 指标继续作为后续互操作前后的基线。

Minecraft 26.2 源码审计得到：

- `VulkanBackend.REQUIRED_DEVICE_EXTENSIONS` 只有动态渲染、push descriptor、synchronization2、vertex divisor 和 swapchain；没有 Win32 external memory/semaphore 或 HDR metadata。
- `VulkanInstance` 只启用 GLFW 必需的 instance extensions、调试扩展和 macOS portability；没有 `VK_EXT_swapchain_colorspace`。
- `VulkanGpuSurface.pickSwapchainSurfaceFormat` 只接受 `VK_COLOR_SPACE_SRGB_NONLINEAR_KHR` 和 8-bit RGBA/BGRA UNORM；创建 swapchain 时再次硬编码 color space `0`。
- `VulkanGpuTexture` 使用普通 VMA `vmaCreateImage`，没有 external-memory create info、可导出 allocation 或 Win32 handle。
- 项目自己的 `FrameDisplayDriver` 只实现 CPU `half4*` 映射，且 Cycles `SessionParams.headless=true`，所以 Cycles 会明确关闭 graphics interop。

因此 F10 当前显示 `HDR swapchain=false` 是准确状态，不能仅靠增加 Rec.2020/PQ 下拉框改变。

## 3. 真 HDR 输出契约

### 3.1 必需能力

后续 HDR 实现必须在创建 Vulkan instance/device/swapchain **之前**完成能力协商：

- instance：按可用性启用 `VK_EXT_swapchain_colorspace`；
- surface：枚举并保存完整的 `(VkFormat, VkColorSpaceKHR)` 对，而不是只保存 format；
- swapchain：只从 surface 实际报告的组合中选择 HDR 模式；
- metadata：可选启用 `VK_EXT_hdr_metadata` 并调用 `vkSetHdrMetadataEXT`；metadata 不会改变像素编码或 color space；
- 显示器/窗口移动、Windows HDR 状态或 surface capability 改变时重新协商并重建 swapchain；
- 失败时自动回到现有 sRGB SDR，不让 F8 原版回退失效。

优先评估两种输出模式：

| 模式 | 典型 surface 组合 | 显示 shader 责任 | 风险 |
| --- | --- | --- | --- |
| HDR10 | 10-bit UNORM + `HDR10_ST2084_EXT` | Linear Rec.709 → Rec.2020、绝对亮度标定、PQ | HUD/GUI 合成、峰值亮度和带状伪影 |
| Linear HDR | FP16 + `EXTENDED_SRGB_LINEAR_EXT` | 保持线性扩展 sRGB/scRGB 语义 | Windows Vulkan WSI/驱动支持须实机验证 |

不能把 ACES 2、AgX 或工作空间名称当作输出编码。查看变换负责把场景动态范围映射到显示目标；swapchain format/color space、传递函数和显示亮度契约仍需单独成立。

### 3.2 HDR metadata 边界

`VK_EXT_hdr_metadata` 只提交 SMPTE ST 2086 与 CTA 861.3 元数据。Khronos 明确指出它不覆盖 color space 或编码；Windows 也不保证显示器一致处理 metadata。因此首要目标是根据已协商显示能力正确 tone-map，metadata 只能是可选补充，不能作为“真 HDR 已启用”的判据。

## 4. Cycles/Vulkan 互操作契约

### 4.1 Cycles 5.2 已有能力

本地固定的 Cycles 5.2 源码已经包含完整入口：

- `DisplayDriver::graphics_interop_get_device()` 返回 `VULKAN` 与 Vulkan physical-device UUID；
- `graphics_interop_update_buffer()` 提供 half4 pixel buffer 的 OS handle 与字节数；
- Windows handle 语义是 Vulkan `VkBuffer` backing memory 的 opaque Win32 handle；
- CUDA/OptiX 路径比较 CUDA 与 Vulkan UUID，只允许同一物理 GPU；
- `CUDADeviceGraphicsInterop` 使用 `cuImportExternalMemory` 和 `cuExternalMemoryGetMappedBuffer`，让显示 kernel 直接写入外部 buffer；
- 不满足条件时 Cycles 会回到现有 naive CPU display update。

OptiX 设备继承 CUDA device 实现，因此 RTX/OptiX 是这条路径的首要目标，不需要把 Cycles 改成 Vulkan 渲染后端。

### 4.2 Minecraft 侧仍缺少的资源

第一版互操作目标应是 **共享 buffer，不共享 image**：

```text
Cycles/OptiX
  -> exportable Vulkan VkBuffer（RGBA16F half4）
  -> Vulkan GPU copy buffer-to-image
  -> 现有 RGBA16F sampled VkImage
  -> 现有 OCIO/显示 shader
```

原因是 Cycles 5.2 的显示互操作契约明确要求 pixel buffer，而 Minecraft 当前采样的是 `VkImage`。这种设计仍消除两次 PCIe/主存搬运，只保留设备内 GPU copy，并保持现有 shader、Pass 和 TextureView 合成路径。

需要的 Vulkan 能力至少包括：

- `VK_KHR_external_memory` 与 Windows 的 `VK_KHR_external_memory_win32`；
- exportable `VkBuffer`、`VkDeviceMemory` 和 `VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT`；
- Vulkan physical-device UUID 与 Cycles CUDA UUID 一致；
- resize/动态分辨率时安全轮换 buffer，旧 buffer 在两端停止使用后才能释放；
- 传给 Cycles 的必须是重复/转移所有权规则清晰的 Win32 handle，不能让 Java、Vulkan 和 Cycles 重复关闭同一 handle。

### 4.3 同步是硬门槛

外部内存只解决“同一块显存”，不解决访问顺序。NVIDIA 官方流程要求通过外部 semaphore 定义 CUDA 与 Vulkan 的执行顺序。当前 Cycles `GraphicsInteropBuffer` 只携带 memory handle，没有把 Vulkan semaphore 暴露给宿主；Vulkan buffer 在 CUDA kernel 完成前被读取会产生竞争。

后续原型必须在以下方案中证明一个：

1. 扩展固定 Cycles bridge/display driver，使 CUDA signal 与 Vulkan wait 使用 Win32 external timeline/binary semaphore；或
2. 证明 Cycles 在 `update_end` 前完成 CUDA queue 同步，并用 Vulkan host→device 提交边界建立正确可见性。

方案 2 若只能通过全局 `vkDeviceWaitIdle`、`cuCtxSynchronize` 或每帧 CPU 阻塞成立，只能用于诊断原型，不能作为正式实时路径。

## 5. 后续实现拆分

### P14：只读能力探针（已完成）

- 从已创建的活动 Vulkan instance/device 只读报告 external memory/semaphore、swapchain colorspace、HDR metadata 的驱动可用性与 Minecraft 启用状态。
- 在 surface 创建后报告所有 format/color-space 组合。
- 报告 Vulkan UUID 与 Cycles/OptiX UUID 是否一致。
- 不改变设备扩展、swapchain 或当前上传路径。

实现结果：

- Java 侧从活动 Minecraft `VulkanDevice` 和窗口 surface 只读枚举 instance/device extensions、完整 surface format/color-space 对和 Vulkan 设备 UUID；F10 区分 `available` 与 `enabled`。
- Native ABI v20 在活动 OptiX/CUDA 设备选中或回退时缓存 CUDA UUID；CPU 后端明确返回 `unavailable`，不会把 PCI 字符串当 UUID。
- F10 比较 Vulkan/Cycles UUID，并分别给出互操作扩展 available/enabled/prerequisites/active 与 HDR colorspace extension/surface advertised/negotiate/active。前置条件满足也不会被标成互操作已经 active。
- 当 `VK_EXT_swapchain_colorspace` 未在 instance 创建时启用，当前 surface 查询可能不会暴露扩展色彩空间；因此零个 HDR candidates 只能表示“当前 instance 下未枚举到”，不能断言显示器或驱动不支持 HDR。
- 当前仍未修改 Minecraft extension 集合、Cycles `headless`、surface 选择或 FrameStore 上传，因此 P14 不宣称 interop/HDR 已启用。
- 自动验证：Java 编译、Mixin 资源 JSON、native ABI/布局、RTX 5080 OptiX smoke 均通过；游戏内实际 surface 枚举仍需人工查看 F10。

### P15：外部 buffer 单帧原型

- 以显式实验开关启用设备扩展。
- 创建一张固定 480×270 RGBA16F exportable buffer，验证 Cycles 直接写入并由 Vulkan copy 到测试纹理。
- 首先使用保守同步证明正确性；F10 对比 CPU copied bytes、upload bytes 和 interop copy 时间。
- 任一条件失败立即回退 FrameStore/FFM 路径。

P15a 设备扩展开关：

- 设备创建早于 NeoForge 客户端配置加载，因此该开关不能在 F9 中热切换。
- 启动前设置 JVM 属性 `-Dcyclesrenderer.experimentalVulkanInterop=true`，或环境变量 `CYCLESRENDERER_VULKAN_INTEROP=1`。默认关闭。
- Mixin 在 Minecraft 已选定物理设备、尚未创建逻辑设备的边界检查两个 Win32 扩展。仅两者都可用时才把它们加入设备扩展集。
- F10 `interop bootstrap` 报告开关请求、注入是否执行及未启用原因；现有 `available/enabled` 行仍从实际 Vulkan 设备诊断读取。
- P15a 只启用后续创建可导出 buffer 的前置扩展，尚未创建共享资源，`active` 仍必须为 `false`。

P15b1 Vulkan 资源所有者：

- 只在 F8 启用实验后端且 P15a 已实际启用两个设备扩展时，创建固定 `480×270 RGBA16F` buffer。F8 关闭时立即释放。
- 资源使用原生 `vkCreateBuffer` 与 dedicated `vkAllocateMemory`，创建和分配链都声明 `OPAQUE_WIN32`外部内存。普通 Minecraft VMA buffer 不能事后转换为可导出资源。
- 分配前验证 external-buffer exportable/compatible 标志，并选择兼容的 device-local memory type。
- 本子阶段尚未调用 `vkGetMemoryWin32HandleKHR`，因此没有未交付 HANDLE 的泄漏或双重关闭风险。Cycles 仍只使用 FrameStore。
- F10 报告固定逻辑字节数、Vulkan 实际 allocation 字节数和失败原因。

P15b2 HANDLE 描述与 Native ABI v21：

- Java 通过 `vkGetMemoryWin32HandleKHR` 导出 `OPAQUE_WIN32` memory HANDLE，并与尺寸、RGBA16F、实际 allocation 字节数和 Vulkan UUID 一起交给 Native。
- `cycles_bridge_bind_vulkan_interop_buffer` 采用“调用即转移 HANDLE 所有权”契约：描述无效或 UUID 不匹配时 Native 也必须关闭 HANDLE；成功时持有到 unbind 或 Renderer 销毁。
- Native 再次比较 Vulkan UUID 与当前 Cycles CUDA/OptiX UUID。CPU 或 UUID 不可用时拒绝绑定。
- F8 关闭顺序为 Native unbind/CloseHandle，然后销毁 Vulkan buffer/memory。这一子阶段仍不调用 CUDA external-memory import，不改变 FrameStore 出图。

P15c1 Cycles 图形互操作会话与 Native ABI v22：

- Cycles 会话在创建时领取已绑定的 Win32 HANDLE，`DisplayDriver` 报告 Vulkan UUID 并把 HANDLE 交给 Cycles 5.2 `GraphicsInteropBuffer`；OptiX/CUDA 随后通过 `cuImportExternalMemory` 直接映射共享 buffer。
- 共享原型固定为 `480×270 RGBA16F`，Native 在互操作会话中强制该内部尺寸。若 Cycles 拒绝互操作，`DisplayDriver` 仍允许 naive CPU `FrameStore` 路径继续出图。
- `update_end` 发生在 Cycles film-convert CUDA stream 同步之后，因此 ABI 状态的 generation 只在共享 buffer 写入完成后推进；本提交只提供可轮询的 ready/generation/sync telemetry，不让 Minecraft 读取 buffer。
- HANDLE 一旦进入会话，普通 unbind 会被拒绝；必须先销毁 Renderer 并等待 Cycles/CUDA import 释放，再销毁 Vulkan buffer/memory。这样为下一步 F8 安全关闭顺序提供硬约束。

### P16：互操作环与正式同步

- 建立至少三槽 external buffer 生命周期与 resize 规则。
- 实现 CUDA/Vulkan semaphore 所有权和 wait/signal。
- 恢复 1080p、动态分辨率、Pass 切换、OptiX/OIDN 和 F8 回退测试。

### P17：HDR swapchain 原型

- 与 P15/P16 解耦，单独选择 HDR surface mode 并实现输出 shader。
- 先验证 Windows HDR 开/关、窗口跨显示器、全屏/窗口、HUD 白点与 SDR 回退，再开放用户设置。
- 未通过实机仪器或可信测试图验收前，F9 不显示“真 HDR 已启用”。

## 6. 稳定契约与停止条件

保持不变：Section/材质 ABI、Pass ID、场景线性 RGBA16F 缓存、OCIO LUT、F8/F9/F10、DH 可选 Provider 和 SDR 路径。

出现以下任一情况必须停止原型而不是强行接管：

- Minecraft 逻辑设备已创建，无法补启用必需扩展；
- Vulkan/CUDA UUID 不一致；
- surface 不报告目标 HDR format/color-space 组合；
- 只能靠私有驱动行为而无法建立跨 API 同步；
- 修改 Minecraft Vulkan 类导致原版渲染、窗口重建或其他模组无法共享设备；
- CPU/非 NVIDIA 后端没有可靠回退。

## 7. P13 证据来源

- 当前项目与固定依赖：Minecraft 26.2 patched sources；Cycles 5.2 `session/display_driver.h`、`device/cuda/graphics_interop.cpp`、`device/cuda/device_impl.cpp` 和 `integrator/path_trace_work_gpu.cpp`。
- Khronos `VK_EXT_hdr_metadata`：https://registry.khronos.org/VulkanSC/specs/1.0-extensions/man/html/VK_EXT_hdr_metadata.html
- Khronos `VkColorSpaceKHR`：https://registry.khronos.org/VulkanSC/specs/1.0-extensions/man/html/VkColorSpaceKHR.html
- NVIDIA CUDA/Vulkan 外部资源互操作：https://docs.nvidia.com/cuda/cuda-programming-guide/04-special-topics/graphics-interop.html
- Microsoft Windows Advanced Color：https://learn.microsoft.com/windows/win32/direct3darticles/high-dynamic-range
- Microsoft HDR metadata 限制：https://learn.microsoft.com/windows/win32/api/dxgi1_5/nf-dxgi1_5-idxgiswapchain4-sethdrmetadata
