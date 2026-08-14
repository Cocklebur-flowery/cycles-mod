package dev.cyclesrenderer.render;

import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import dev.cyclesrenderer.config.CyclesRenderSettings;
import dev.cyclesrenderer.mixin.GpuDeviceAccessor;
import dev.cyclesrenderer.nativebridge.NativeBridge;
import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRExternalMemoryWin32;
import org.lwjgl.vulkan.KHRExternalSemaphoreWin32;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExportMemoryAllocateInfo;
import org.lwjgl.vulkan.VkExternalBufferProperties;
import org.lwjgl.vulkan.VkExternalMemoryBufferCreateInfo;
import org.lwjgl.vulkan.VkExportSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkExternalSemaphoreProperties;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryDedicatedAllocateInfo;
import org.lwjgl.vulkan.VkMemoryGetWin32HandleInfoKHR;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceExternalSemaphoreInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreGetWin32HandleInfoKHR;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageSubresourceLayers;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceExternalBufferInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import java.nio.LongBuffer;

public final class VulkanExternalBufferPrototype implements AutoCloseable {
    public static final int BYTES_PER_PIXEL = 8;
    private static final int DEPTH_BYTES_PER_PIXEL = 4;
    private static final int SLOT_BYTES_PER_PIXEL =
            BYTES_PER_PIXEL + DEPTH_BYTES_PER_PIXEL;
    public static final int SLOT_COUNT = 3;

    private static final int HANDLE_TYPE =
            VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
    private static final int SEMAPHORE_HANDLE_TYPE =
            VK11.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;
    private static final int USAGE = VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT
            | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;

    private VkDevice device;
    private VulkanDevice vulkanDevice;
    private long buffer;
    private long memory;
    private long readySemaphore;
    private long releaseSemaphore;
    private long allocationBytes;
    private int slotStrideBytes;
    private int capacityWidth;
    private int capacityHeight;
    private long logicalBytes;
    private boolean nativeBound;
    private TextureTarget frameTarget;
    private TextureTarget depthTarget;
    private GpuFence pendingCopyFence;
    private long pendingGeneration;
    private int pendingWidth;
    private int pendingHeight;
    private int pendingDepthWidth;
    private int pendingDepthHeight;
    private int pendingSlotIndex;
    private long displayedGeneration;
    private int displayedWidth;
    private int displayedHeight;
    private int displayedDepthWidth;
    private int displayedDepthHeight;
    private int displayedSlotIndex;
    private long copyCount;
    private long generationGaps;
    private long lastCopyMicros;
    private long emaCopyMicros;
    private long maxCopyMicros;
    private boolean nativeActive;
    private Telemetry telemetry = Telemetry.inactive("not initialized");

    public void initialize(Minecraft minecraft, CyclesRenderSettings settings) {
        RenderSystem.assertOnRenderThread();
        close();

        Capacity capacity = Capacity.from(settings);
        capacityWidth = capacity.width();
        capacityHeight = capacity.height();
        logicalBytes = capacity.logicalBytes();
        slotStrideBytes = Math.toIntExact(logicalBytes);

        VulkanCapabilityProbe.InteropBootstrap bootstrap =
                VulkanCapabilityProbe.interopBootstrap();
        if (!bootstrap.extensionsRequested()) {
            telemetry = Telemetry.inactive(
                    capacityWidth, capacityHeight, logicalBytes,
                    "device extensions not requested");
            return;
        }

        VulkanCapabilityProbe.Snapshot capabilities =
                VulkanCapabilityProbe.snapshot(minecraft);
        if (!capabilities.interopExtensionsEnabled()) {
            telemetry = Telemetry.inactive(
                    capacityWidth, capacityHeight, logicalBytes,
                    "device extensions not enabled");
            return;
        }

        long exportedHandle = 0L;
        long readyHandle = 0L;
        long releaseHandle = 0L;
        try {
            GpuDeviceBackend backend =
                    ((GpuDeviceAccessor) (Object) RenderSystem.getDevice())
                            .cyclesrenderer$getBackend();
            if (!(backend instanceof VulkanDevice vulkanDevice)) {
                telemetry = Telemetry.inactive(
                        capacityWidth, capacityHeight, logicalBytes,
                        "active graphics backend is not Vulkan");
                return;
            }
            long ringBytes = Math.multiplyExact(logicalBytes, SLOT_COUNT);
            allocate(vulkanDevice, ringBytes);
            exportedHandle = exportMemoryHandle();
            readyHandle = exportSemaphoreHandle(readySemaphore);
            releaseHandle = exportSemaphoreHandle(releaseSemaphore);
            try {
                NativeBridge.bindVulkanInteropBuffer(
                        capacityWidth,
                        capacityHeight,
                        allocationBytes,
                        exportedHandle,
                        readyHandle,
                        releaseHandle,
                        capabilities.physicalDeviceUuid(),
                        SLOT_COUNT,
                        slotStrideBytes);
            } finally {
                // The NativeBridge call owns all three handles on every return path.
                exportedHandle = 0L;
                readyHandle = 0L;
                releaseHandle = 0L;
            }
            nativeBound = true;
            telemetry = new Telemetry(
                    true, true, true,
                    capacityWidth, capacityHeight,
                    logicalBytes, allocationBytes,
                    "bound-native");
        } catch (RuntimeException | LinkageError error) {
            closeExportedHandle(exportedHandle);
            closeExportedHandle(readyHandle);
            closeExportedHandle(releaseHandle);
            releaseHandles();
            telemetry = Telemetry.inactive(
                    capacityWidth, capacityHeight, logicalBytes,
                    error.getClass().getSimpleName() + ": "
                            + String.valueOf(error.getMessage()));
        }
    }

    private static void closeExportedHandle(long handle) {
        if (handle != 0L) {
            NativeBridge.closeWin32Handle(handle);
        }
    }

    public Telemetry telemetry() {
        return telemetry;
    }

    public long vkBuffer() {
        if (buffer == VK10.VK_NULL_HANDLE) {
            throw new IllegalStateException("external buffer is not allocated");
        }
        return buffer;
    }

    public long allocationBytes() {
        return allocationBytes;
    }

    public boolean requiresLargerCapacity(CyclesRenderSettings settings) {
        return nativeBound && Capacity.from(settings).logicalBytes() > logicalBytes;
    }

    public void pollCompletedFrame() {
        RenderSystem.assertOnRenderThread();
        finishPendingCopy(false);
        if (pendingCopyFence != null || !nativeBound || !NativeBridge.isReady()) {
            return;
        }
        NativeBridge.VulkanInteropState state =
                NativeBridge.acquireVulkanInteropFrame(displayedGeneration);
        nativeActive = state.active();
        if (!state.frameAcquired() || state.generation() <= displayedGeneration) {
            return;
        }
        pendingGeneration = state.generation();
        pendingWidth = state.width();
        pendingHeight = state.height();
        pendingDepthWidth = state.depthWidth();
        pendingDepthHeight = state.depthHeight();
        pendingSlotIndex = state.slotIndex();
        try {
            validateFrame(
                    pendingWidth,
                    pendingHeight,
                    pendingDepthWidth,
                    pendingDepthHeight,
                    pendingSlotIndex);
            ensureFrameTargets(
                    pendingWidth,
                    pendingHeight,
                    pendingDepthWidth,
                    pendingDepthHeight);
            long start = System.nanoTime();
            encodeCopy(
                    pendingWidth,
                    pendingHeight,
                    pendingDepthWidth,
                    pendingDepthHeight,
                    pendingSlotIndex);
            lastCopyMicros = nanosToMicros(System.nanoTime() - start);
            emaCopyMicros = emaCopyMicros == 0L
                    ? lastCopyMicros
                    : (emaCopyMicros * 7L + lastCopyMicros) / 8L;
            maxCopyMicros = Math.max(maxCopyMicros, lastCopyMicros);
        } catch (RuntimeException | LinkageError error) {
            try {
                signalDiscardedFrame(pendingGeneration);
            } finally {
                NativeBridge.releaseVulkanInteropFrame(pendingGeneration);
            }
            pendingGeneration = 0L;
            pendingWidth = 0;
            pendingHeight = 0;
            pendingDepthWidth = 0;
            pendingDepthHeight = 0;
            pendingSlotIndex = 0;
            throw error;
        }
    }

    private void signalDiscardedFrame(long generation) {
        if (generation == 0L || vulkanDevice == null
                || releaseSemaphore == VK10.VK_NULL_HANDLE) {
            return;
        }
        VulkanCommandEncoder encoder = vulkanDevice.createCommandEncoder();
        encoder.waitSemaphore(
                readySemaphore,
                generation,
                VK13.VK_PIPELINE_STAGE_2_COPY_BIT);
        encoder.signalSemaphore(
                releaseSemaphore,
                generation,
                VK13.VK_PIPELINE_STAGE_2_COPY_BIT);
        encoder.submit();
    }

    public boolean hasFrame() {
        return frameTarget != null && displayedGeneration != 0L;
    }

    public boolean hasActiveFrame() {
        return nativeActive && hasFrame();
    }

    public GpuTextureView frameTextureView() {
        if (!hasFrame()) {
            throw new IllegalStateException("Vulkan interop frame is not ready");
        }
        return java.util.Objects.requireNonNull(frameTarget.getColorTextureView());
    }

    public boolean hasDepthFrame() {
        return hasFrame()
                && depthTarget != null
                && displayedDepthWidth > 0
                && displayedDepthHeight > 0;
    }

    public GpuTextureView depthTextureView() {
        if (!hasDepthFrame()) {
            throw new IllegalStateException("Vulkan interop depth frame is not ready");
        }
        return java.util.Objects.requireNonNull(depthTarget.getColorTextureView());
    }

    public long generation() {
        return displayedGeneration;
    }

    public CopyTelemetry copyTelemetry() {
        return new CopyTelemetry(
                pendingCopyFence != null,
                pendingGeneration,
                displayedGeneration,
                copyCount,
                generationGaps,
                displayedWidth,
                displayedHeight,
                displayedSlotIndex,
                lastCopyMicros,
                emaCopyMicros,
                maxCopyMicros);
    }

    public void drainPendingCopy() {
        RenderSystem.assertOnRenderThread();
        finishPendingCopy(true);
    }

    private void validateFrame(
            int width,
            int height,
            int depthWidth,
            int depthHeight,
            int slotIndex) {
        if (slotIndex < 0 || slotIndex >= SLOT_COUNT) {
            throw new IllegalStateException(
                    "native interop frame has invalid slot " + slotIndex);
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException(
                    "native interop frame has invalid dimensions " + width + "x" + height);
        }
        if ((depthWidth == 0) != (depthHeight == 0)
                || depthWidth < 0
                || depthHeight < 0) {
            throw new IllegalStateException(
                    "native interop frame has invalid depth dimensions "
                            + depthWidth + "x" + depthHeight);
        }
        long frameBytes;
        try {
            long colorBytes = Math.multiplyExact(
                    Math.multiplyExact((long) width, height),
                    BYTES_PER_PIXEL);
            long depthBytes = Math.multiplyExact(
                    Math.multiplyExact((long) depthWidth, depthHeight),
                    DEPTH_BYTES_PER_PIXEL);
            frameBytes = Math.addExact(colorBytes, depthBytes);
        } catch (ArithmeticException error) {
            throw new IllegalStateException(
                    "native interop frame dimensions overflow " + width + "x" + height,
                    error);
        }
        long slotEnd = Math.addExact(
                Math.multiplyExact((long) slotIndex, slotStrideBytes),
                frameBytes);
        if (frameBytes > slotStrideBytes || slotEnd > allocationBytes) {
            throw new IllegalStateException(
                    "native interop frame " + width + "x" + height
                            + " requires " + frameBytes + " bytes, capacity is "
                            + capacityWidth + "x" + capacityHeight + " ("
                            + logicalBytes + " logical, " + allocationBytes + " allocated)");
        }
    }

    private void ensureFrameTargets(
            int width,
            int height,
            int depthWidth,
            int depthHeight) {
        if (frameTarget == null) {
            frameTarget = new TextureTarget(
                    "Cycles Vulkan interop frame",
                    width,
                    height,
                    false,
                    GpuFormat.RGBA16_FLOAT);
        } else if (frameTarget.width != width || frameTarget.height != height) {
            frameTarget.resize(width, height);
        }
        if (depthWidth <= 0 || depthHeight <= 0) {
            return;
        }
        if (depthTarget == null) {
            depthTarget = new TextureTarget(
                    "Cycles Vulkan interop depth",
                    depthWidth,
                    depthHeight,
                    false,
                    GpuFormat.R32_FLOAT);
        } else if (depthTarget.width != depthWidth || depthTarget.height != depthHeight) {
            depthTarget.resize(depthWidth, depthHeight);
        }
    }

    private void encodeCopy(
            int width,
            int height,
            int depthWidth,
            int depthHeight,
            int slotIndex) {
        if (vulkanDevice == null || frameTarget == null) {
            throw new IllegalStateException("Vulkan interop copy target is not initialized");
        }
        if (!(frameTarget.getColorTexture() instanceof VulkanGpuTexture destination)) {
            throw new IllegalStateException("interop copy target is not a Vulkan texture");
        }
        VulkanGpuTexture depthDestination = null;
        if (depthWidth > 0 && depthHeight > 0) {
            if (depthTarget == null
                    || !(depthTarget.getColorTexture()
                    instanceof VulkanGpuTexture vulkanDepth)) {
                throw new IllegalStateException(
                        "interop depth copy target is not a Vulkan texture");
            }
            depthDestination = vulkanDepth;
        }
        VulkanCommandEncoder encoder = vulkanDevice.createCommandEncoder();
        GpuFence nextFence = encoder.createFence();
        VkCommandBuffer commandBuffer = encoder.allocateAndBeginTransientCommandBuffer();
        try {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                pipelineBarrier(
                        commandBuffer,
                        stack,
                        VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                        VK13.VK_ACCESS_2_MEMORY_WRITE_BIT,
                        VK13.VK_PIPELINE_STAGE_2_COPY_BIT,
                        VK13.VK_ACCESS_2_TRANSFER_READ_BIT);
                long slotOffset = Math.multiplyExact((long) slotIndex, slotStrideBytes);
                VkBufferImageCopy.Buffer region = copyRegion(
                        stack, slotOffset, width, height);
                VK12.vkCmdCopyBufferToImage(
                        commandBuffer,
                        buffer,
                        destination.vkImage(),
                        VK10.VK_IMAGE_LAYOUT_GENERAL,
                        region);
                if (depthDestination != null) {
                    long colorBytes = Math.multiplyExact(
                            Math.multiplyExact((long) width, height),
                            BYTES_PER_PIXEL);
                    VkBufferImageCopy.Buffer depthRegion = copyRegion(
                            stack,
                            Math.addExact(slotOffset, colorBytes),
                            depthWidth,
                            depthHeight);
                    VK12.vkCmdCopyBufferToImage(
                            commandBuffer,
                            buffer,
                            depthDestination.vkImage(),
                            VK10.VK_IMAGE_LAYOUT_GENERAL,
                            depthRegion);
                }
                pipelineBarrier(
                        commandBuffer,
                        stack,
                        VK13.VK_PIPELINE_STAGE_2_COPY_BIT,
                        VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT,
                        VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                        VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
                check(VK10.vkEndCommandBuffer(commandBuffer),
                        "end interop copy command");
            }
            encoder.waitSemaphore(
                    readySemaphore,
                    pendingGeneration,
                    VK13.VK_PIPELINE_STAGE_2_COPY_BIT);
            encoder.execute(commandBuffer);
            encoder.signalSemaphore(
                    releaseSemaphore,
                    pendingGeneration,
                    VK13.VK_PIPELINE_STAGE_2_COPY_BIT);
            encoder.submit();
            pendingCopyFence = nextFence;
        } catch (RuntimeException | LinkageError error) {
            vulkanDevice.graphicsQueue().waitIdle();
            nextFence.close();
            throw error;
        }
    }

    private static VkBufferImageCopy.Buffer copyRegion(
            MemoryStack stack,
            long bufferOffset,
            int width,
            int height) {
        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        region.bufferOffset(bufferOffset);
        region.bufferRowLength(width);
        region.bufferImageHeight(height);
        VkImageSubresourceLayers subresource = region.imageSubresource();
        subresource.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT);
        subresource.mipLevel(0);
        subresource.baseArrayLayer(0);
        subresource.layerCount(1);
        region.imageOffset().set(0, 0, 0);
        region.imageExtent().set(width, height, 1);
        return region;
    }

    private static void pipelineBarrier(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess) {
        VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack)
                .sType$Default()
                .srcStageMask(sourceStage)
                .srcAccessMask(sourceAccess)
                .dstStageMask(destinationStage)
                .dstAccessMask(destinationAccess);
        VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
                .sType$Default()
                .pMemoryBarriers(barrier);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependency);
    }

    private void finishPendingCopy(boolean wait) {
        if (pendingCopyFence == null) {
            return;
        }
        if (wait) {
            java.util.Objects.requireNonNull(vulkanDevice).graphicsQueue().waitIdle();
        } else if (!pendingCopyFence.awaitCompletion(0L)) {
            return;
        }
        pendingCopyFence.close();
        pendingCopyFence = null;
        if (NativeBridge.isReady()) {
            NativeBridge.releaseVulkanInteropFrame(pendingGeneration);
        }
        if (displayedGeneration != 0L && pendingGeneration > displayedGeneration + 1L) {
            generationGaps += pendingGeneration - displayedGeneration - 1L;
        }
        displayedGeneration = pendingGeneration;
        displayedWidth = pendingWidth;
        displayedHeight = pendingHeight;
        displayedDepthWidth = pendingDepthWidth;
        displayedDepthHeight = pendingDepthHeight;
        displayedSlotIndex = pendingSlotIndex;
        pendingGeneration = 0L;
        pendingWidth = 0;
        pendingHeight = 0;
        pendingDepthWidth = 0;
        pendingDepthHeight = 0;
        pendingSlotIndex = 0;
        copyCount++;
    }

    private static long nanosToMicros(long nanos) {
        return Math.max(0L, nanos / 1_000L);
    }

    private long exportMemoryHandle() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryGetWin32HandleInfoKHR handleInfo =
                    VkMemoryGetWin32HandleInfoKHR.calloc(stack)
                            .sType$Default()
                            .memory(memory)
                            .handleType(HANDLE_TYPE);
            PointerBuffer output = stack.callocPointer(1);
            check(KHRExternalMemoryWin32.vkGetMemoryWin32HandleKHR(
                            device, handleInfo, output),
                    "export Win32 memory handle");
            long handle = output.get(0);
            if (handle == 0L) {
                throw new IllegalStateException(
                        "export Win32 memory handle returned null");
            }
            return handle;
        }
    }

    private long exportSemaphoreHandle(long semaphore) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreGetWin32HandleInfoKHR handleInfo =
                    VkSemaphoreGetWin32HandleInfoKHR.calloc(stack)
                            .sType$Default()
                            .semaphore(semaphore)
                            .handleType(SEMAPHORE_HANDLE_TYPE);
            PointerBuffer output = stack.callocPointer(1);
            check(KHRExternalSemaphoreWin32.vkGetSemaphoreWin32HandleKHR(
                            device, handleInfo, output),
                    "export Win32 timeline semaphore handle");
            long handle = output.get(0);
            if (handle == 0L) {
                throw new IllegalStateException(
                        "export Win32 timeline semaphore handle returned null");
            }
            return handle;
        }
    }

    private void allocate(VulkanDevice vulkanDevice, long requestedBytes) {
        VkDevice nextDevice = vulkanDevice.vkDevice();
        VkPhysicalDevice physicalDevice = nextDevice.getPhysicalDevice();
        validateExternalBufferSupport(physicalDevice);
        validateExternalSemaphoreSupport(physicalDevice);

        long nextBuffer = VK10.VK_NULL_HANDLE;
        long nextMemory = VK10.VK_NULL_HANDLE;
        long nextReadySemaphore = VK10.VK_NULL_HANDLE;
        long nextReleaseSemaphore = VK10.VK_NULL_HANDLE;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkExternalMemoryBufferCreateInfo externalBuffer =
                    VkExternalMemoryBufferCreateInfo.calloc(stack)
                            .sType$Default()
                            .handleTypes(HANDLE_TYPE);
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .pNext(externalBuffer.address())
                    .size(requestedBytes)
                    .usage(USAGE)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer bufferOutput = stack.callocLong(1);
            check(VK10.vkCreateBuffer(nextDevice, bufferInfo, null, bufferOutput),
                    "create exportable buffer");
            nextBuffer = bufferOutput.get(0);

            VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
            VK10.vkGetBufferMemoryRequirements(nextDevice, nextBuffer, requirements);
            int memoryType = findDeviceLocalMemoryType(
                    physicalDevice, requirements.memoryTypeBits(), stack);

            VkMemoryDedicatedAllocateInfo dedicated =
                    VkMemoryDedicatedAllocateInfo.calloc(stack)
                            .sType$Default()
                            .buffer(nextBuffer);
            VkExportMemoryAllocateInfo export =
                    VkExportMemoryAllocateInfo.calloc(stack)
                            .sType$Default()
                            .pNext(dedicated.address())
                            .handleTypes(HANDLE_TYPE);
            VkMemoryAllocateInfo allocation = VkMemoryAllocateInfo.calloc(stack)
                    .sType$Default()
                    .pNext(export.address())
                    .allocationSize(requirements.size())
                    .memoryTypeIndex(memoryType);
            LongBuffer memoryOutput = stack.callocLong(1);
            check(VK10.vkAllocateMemory(nextDevice, allocation, null, memoryOutput),
                    "allocate exportable buffer memory");
            nextMemory = memoryOutput.get(0);
            check(VK10.vkBindBufferMemory(nextDevice, nextBuffer, nextMemory, 0L),
                    "bind exportable buffer memory");

            VkSemaphoreTypeCreateInfo semaphoreType =
                    VkSemaphoreTypeCreateInfo.calloc(stack)
                            .sType$Default()
                            .semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE)
                            .initialValue(0L);
            VkExportSemaphoreCreateInfo semaphoreExport =
                    VkExportSemaphoreCreateInfo.calloc(stack)
                            .sType$Default()
                            .pNext(semaphoreType.address())
                            .handleTypes(SEMAPHORE_HANDLE_TYPE);
            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack)
                    .sType$Default()
                    .pNext(semaphoreExport.address());
            LongBuffer semaphoreOutput = stack.callocLong(1);
            check(VK10.vkCreateSemaphore(nextDevice, semaphoreInfo, null, semaphoreOutput),
                    "create ready timeline semaphore");
            nextReadySemaphore = semaphoreOutput.get(0);
            semaphoreOutput.put(0, 0L);
            try {
                check(VK10.vkCreateSemaphore(
                                nextDevice, semaphoreInfo, null, semaphoreOutput),
                        "create release timeline semaphore");
            } catch (RuntimeException error) {
                VK10.vkDestroySemaphore(nextDevice, nextReadySemaphore, null);
                nextReadySemaphore = VK10.VK_NULL_HANDLE;
                throw error;
            }
            nextReleaseSemaphore = semaphoreOutput.get(0);

            device = nextDevice;
            this.vulkanDevice = vulkanDevice;
            buffer = nextBuffer;
            memory = nextMemory;
            readySemaphore = nextReadySemaphore;
            releaseSemaphore = nextReleaseSemaphore;
            allocationBytes = requirements.size();
            nextBuffer = VK10.VK_NULL_HANDLE;
            nextMemory = VK10.VK_NULL_HANDLE;
            nextReadySemaphore = VK10.VK_NULL_HANDLE;
            nextReleaseSemaphore = VK10.VK_NULL_HANDLE;
        } finally {
            if (nextBuffer != VK10.VK_NULL_HANDLE) {
                VK10.vkDestroyBuffer(nextDevice, nextBuffer, null);
            }
            if (nextMemory != VK10.VK_NULL_HANDLE) {
                VK10.vkFreeMemory(nextDevice, nextMemory, null);
            }
            if (nextReadySemaphore != VK10.VK_NULL_HANDLE) {
                VK10.vkDestroySemaphore(nextDevice, nextReadySemaphore, null);
            }
            if (nextReleaseSemaphore != VK10.VK_NULL_HANDLE) {
                VK10.vkDestroySemaphore(nextDevice, nextReleaseSemaphore, null);
            }
        }
    }

    private static void validateExternalSemaphoreSupport(
            VkPhysicalDevice physicalDevice) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreTypeCreateInfo semaphoreType =
                    VkSemaphoreTypeCreateInfo.calloc(stack)
                            .sType$Default()
                            .semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE)
                            .initialValue(0L);
            VkPhysicalDeviceExternalSemaphoreInfo info =
                    VkPhysicalDeviceExternalSemaphoreInfo.calloc(stack)
                            .sType$Default()
                            .pNext(semaphoreType.address())
                            .handleType(SEMAPHORE_HANDLE_TYPE);
            VkExternalSemaphoreProperties properties =
                    VkExternalSemaphoreProperties.calloc(stack).sType$Default();
            VK11.vkGetPhysicalDeviceExternalSemaphoreProperties(
                    physicalDevice, info, properties);
            if ((properties.externalSemaphoreFeatures()
                    & VK11.VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT) == 0) {
                throw new IllegalStateException(
                        "opaque Win32 timeline semaphore is not exportable");
            }
            if ((properties.compatibleHandleTypes() & SEMAPHORE_HANDLE_TYPE) == 0) {
                throw new IllegalStateException(
                        "opaque Win32 timeline semaphore handle is not compatible");
            }
        }
    }

    private static void validateExternalBufferSupport(VkPhysicalDevice physicalDevice) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceExternalBufferInfo info =
                    VkPhysicalDeviceExternalBufferInfo.calloc(stack)
                            .sType$Default()
                            .flags(0)
                            .usage(USAGE)
                            .handleType(HANDLE_TYPE);
            VkExternalBufferProperties properties =
                    VkExternalBufferProperties.calloc(stack).sType$Default();
            VK11.vkGetPhysicalDeviceExternalBufferProperties(
                    physicalDevice, info, properties);
            int features = properties.externalMemoryProperties().externalMemoryFeatures();
            int compatibleHandles =
                    properties.externalMemoryProperties().compatibleHandleTypes();
            if ((features & VK11.VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT) == 0) {
                throw new IllegalStateException("opaque Win32 buffer memory is not exportable");
            }
            if ((compatibleHandles & HANDLE_TYPE) == 0) {
                throw new IllegalStateException("opaque Win32 handle type is not compatible");
            }
        }
    }

    private static int findDeviceLocalMemoryType(
            VkPhysicalDevice physicalDevice,
            int compatibleTypes,
            MemoryStack stack) {
        VkPhysicalDeviceMemoryProperties properties =
                VkPhysicalDeviceMemoryProperties.calloc(stack);
        VK10.vkGetPhysicalDeviceMemoryProperties(physicalDevice, properties);
        for (int index = 0; index < properties.memoryTypeCount(); index++) {
            boolean compatible = (compatibleTypes & (1 << index)) != 0;
            boolean deviceLocal = (properties.memoryTypes(index).propertyFlags()
                    & VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0;
            if (compatible && deviceLocal) {
                return index;
            }
        }
        throw new IllegalStateException("no compatible device-local memory type");
    }

    private static void check(int result, String operation) {
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with VkResult " + result);
        }
    }

    @Override
    public void close() {
        if (device != null) {
            RenderSystem.assertOnRenderThread();
        }
        drainPendingCopy();
        if (nativeBound && NativeBridge.isReady()) {
            NativeBridge.VulkanInteropState state = NativeBridge.vulkanInteropState();
            if (state.sessionAttached()) {
                throw new IllegalStateException(
                        "native renderer must close before Vulkan interop memory");
            }
            NativeBridge.unbindVulkanInteropBuffer();
        }
        nativeBound = false;
        if (frameTarget != null) {
            frameTarget.destroyBuffers();
            frameTarget = null;
        }
        if (depthTarget != null) {
            depthTarget.destroyBuffers();
            depthTarget = null;
        }
        releaseHandles();
        displayedGeneration = 0L;
        displayedWidth = 0;
        displayedHeight = 0;
        displayedDepthWidth = 0;
        displayedDepthHeight = 0;
        displayedSlotIndex = 0;
        pendingGeneration = 0L;
        pendingWidth = 0;
        pendingHeight = 0;
        pendingDepthWidth = 0;
        pendingDepthHeight = 0;
        nativeActive = false;
        copyCount = 0L;
        generationGaps = 0L;
        lastCopyMicros = 0L;
        emaCopyMicros = 0L;
        maxCopyMicros = 0L;
        telemetry = Telemetry.inactive("released");
    }

    private void releaseHandles() {
        if (device != null && buffer != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyBuffer(device, buffer, null);
        }
        if (device != null && memory != VK10.VK_NULL_HANDLE) {
            VK10.vkFreeMemory(device, memory, null);
        }
        if (device != null && readySemaphore != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroySemaphore(device, readySemaphore, null);
        }
        if (device != null && releaseSemaphore != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroySemaphore(device, releaseSemaphore, null);
        }
        device = null;
        vulkanDevice = null;
        buffer = VK10.VK_NULL_HANDLE;
        memory = VK10.VK_NULL_HANDLE;
        readySemaphore = VK10.VK_NULL_HANDLE;
        releaseSemaphore = VK10.VK_NULL_HANDLE;
        allocationBytes = 0L;
    }

    private record Capacity(int width, int height, long logicalBytes) {
        private static Capacity from(CyclesRenderSettings settings) {
            int width = Math.max(
                    1,
                    (int) Math.floor(settings.renderWidth()
                            * (settings.resolutionPercentage() / 100.0)));
            int height = Math.max(
                    1,
                    (int) Math.floor(settings.renderHeight()
                            * (settings.resolutionPercentage() / 100.0)));
            long bytes = Math.multiplyExact(
                    Math.multiplyExact((long) width, height),
                    SLOT_BYTES_PER_PIXEL);
            return new Capacity(width, height, bytes);
        }
    }

    public record Telemetry(
            boolean requested,
            boolean allocated,
            boolean nativeBound,
            int capacityWidth,
            int capacityHeight,
            long logicalBytes,
            long allocationBytes,
            String state) {
        private static Telemetry inactive(String state) {
            return inactive(0, 0, 0L, state);
        }

        private static Telemetry inactive(
                int capacityWidth,
                int capacityHeight,
                long logicalBytes,
                String state) {
            return new Telemetry(
                    false, false, false,
                    capacityWidth, capacityHeight,
                    logicalBytes, 0L, state);
        }
    }

    public record CopyTelemetry(
            boolean pending,
            long pendingGeneration,
            long displayedGeneration,
            long copyCount,
            long generationGaps,
            int displayedWidth,
            int displayedHeight,
            int displayedSlotIndex,
            long lastCopyMicros,
            long emaCopyMicros,
            long maxCopyMicros) {}
}
