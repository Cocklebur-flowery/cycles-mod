package dev.cyclesrenderer.render;

import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import dev.cyclesrenderer.config.CyclesRenderSettings;
import dev.cyclesrenderer.nativebridge.NativeBridge;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageSubresourceLayers;
import org.lwjgl.vulkan.VkMemoryBarrier2;

import java.util.Optional;

public final class VulkanFrameInterop implements AutoCloseable {
    public static final int BYTES_PER_PIXEL = 8;
    private static final int DEPTH_BYTES_PER_PIXEL = 4;
    private static final int SLOT_BYTES_PER_PIXEL =
            BYTES_PER_PIXEL + DEPTH_BYTES_PER_PIXEL;
    public static final int SLOT_COUNT = 3;

    private final VulkanSharedAllocation allocation = new VulkanSharedAllocation();
    private int slotStrideBytes;
    private int capacityWidth;
    private int capacityHeight;
    private long logicalBytes;
    private TextureTarget frameTarget;
    private TextureTarget depthTarget;
    private GpuFence pendingCopyFence;
    private long pendingGeneration;
    private int pendingWidth;
    private int pendingHeight;
    private int pendingDepthWidth;
    private int pendingDepthHeight;
    private int pendingSlotIndex;
    private NativeBridge.ReprojectionMetadata pendingReprojectionMetadata;
    private long displayedGeneration;
    private int displayedWidth;
    private int displayedHeight;
    private int displayedDepthWidth;
    private int displayedDepthHeight;
    private int displayedSlotIndex;
    private NativeBridge.ReprojectionMetadata displayedReprojectionMetadata;
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

        VulkanSharedAllocation.Initialization initialization = allocation.initialize(
                minecraft,
                capacityWidth,
                capacityHeight,
                logicalBytes,
                SLOT_COUNT,
                slotStrideBytes);
        if (initialization.nativeBound()) {
            telemetry = new Telemetry(
                    true, true, true,
                    capacityWidth, capacityHeight,
                    logicalBytes, initialization.allocationBytes(),
                    initialization.state());
        } else {
            telemetry = Telemetry.inactive(
                    capacityWidth, capacityHeight, logicalBytes,
                    initialization.state());
        }
    }

    public Telemetry telemetry() {
        return telemetry;
    }

    public long vkBuffer() {
        return allocation.buffer();
    }

    public long allocationBytes() {
        return allocation.allocationBytes();
    }

    public boolean requiresLargerCapacity(CyclesRenderSettings settings) {
        return allocation.nativeBound()
                && Capacity.from(settings).logicalBytes() > logicalBytes;
    }

    public void pollCompletedFrame() {
        RenderSystem.assertOnRenderThread();
        finishPendingCopy(false);
        if (pendingCopyFence != null
                || !allocation.nativeBound()
                || !NativeBridge.isReady()) {
            return;
        }
        NativeBridge.VulkanInteropFrame acquired =
                NativeBridge.acquireVulkanReprojectionFrame(displayedGeneration);
        NativeBridge.VulkanInteropState state = acquired.state();
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
        pendingReprojectionMetadata = acquired.reprojectionMetadata();
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
            pendingReprojectionMetadata = null;
            throw error;
        }
    }

    private void signalDiscardedFrame(long generation) {
        if (generation == 0L || !allocation.allocated()
                || allocation.releaseSemaphore() == VK10.VK_NULL_HANDLE) {
            return;
        }
        VulkanDevice vulkanDevice = allocation.vulkanDevice();
        VulkanCommandEncoder encoder = vulkanDevice.createCommandEncoder();
        encoder.waitSemaphore(
                allocation.readySemaphore(),
                generation,
                VK13.VK_PIPELINE_STAGE_2_COPY_BIT);
        encoder.signalSemaphore(
                allocation.releaseSemaphore(),
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

    public Optional<NativeBridge.ReprojectionMetadata> reprojectionMetadata() {
        return Optional.ofNullable(displayedReprojectionMetadata);
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
        if (frameBytes > slotStrideBytes || slotEnd > allocation.allocationBytes()) {
            throw new IllegalStateException(
                    "native interop frame " + width + "x" + height
                            + " requires " + frameBytes + " bytes, capacity is "
                            + capacityWidth + "x" + capacityHeight + " ("
                            + logicalBytes + " logical, "
                            + allocation.allocationBytes() + " allocated)");
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
        if (!allocation.allocated() || frameTarget == null) {
            throw new IllegalStateException("Vulkan interop copy target is not initialized");
        }
        VulkanDevice vulkanDevice = allocation.vulkanDevice();
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
                        allocation.buffer(),
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
                            allocation.buffer(),
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
                    allocation.readySemaphore(),
                    pendingGeneration,
                    VK13.VK_PIPELINE_STAGE_2_COPY_BIT);
            encoder.execute(commandBuffer);
            encoder.signalSemaphore(
                    allocation.releaseSemaphore(),
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
            allocation.vulkanDevice().graphicsQueue().waitIdle();
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
        displayedReprojectionMetadata = pendingReprojectionMetadata;
        pendingGeneration = 0L;
        pendingWidth = 0;
        pendingHeight = 0;
        pendingDepthWidth = 0;
        pendingDepthHeight = 0;
        pendingSlotIndex = 0;
        pendingReprojectionMetadata = null;
        copyCount++;
    }

    private static long nanosToMicros(long nanos) {
        return Math.max(0L, nanos / 1_000L);
    }

    private static void check(int result, String operation) {
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with VkResult " + result);
        }
    }

    @Override
    public void close() {
        if (allocation.allocated()) {
            RenderSystem.assertOnRenderThread();
        }
        drainPendingCopy();
        allocation.unbindNative();
        if (frameTarget != null) {
            frameTarget.destroyBuffers();
            frameTarget = null;
        }
        if (depthTarget != null) {
            depthTarget.destroyBuffers();
            depthTarget = null;
        }
        allocation.close();
        displayedGeneration = 0L;
        displayedWidth = 0;
        displayedHeight = 0;
        displayedDepthWidth = 0;
        displayedDepthHeight = 0;
        displayedSlotIndex = 0;
        displayedReprojectionMetadata = null;
        pendingGeneration = 0L;
        pendingWidth = 0;
        pendingHeight = 0;
        pendingDepthWidth = 0;
        pendingDepthHeight = 0;
        pendingReprojectionMetadata = null;
        nativeActive = false;
        copyCount = 0L;
        generationGaps = 0L;
        lastCopyMicros = 0L;
        emaCopyMicros = 0L;
        maxCopyMicros = 0L;
        telemetry = Telemetry.inactive("released");
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
