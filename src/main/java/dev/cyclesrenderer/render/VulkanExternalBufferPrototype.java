package dev.cyclesrenderer.render;

import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.cyclesrenderer.mixin.GpuDeviceAccessor;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExportMemoryAllocateInfo;
import org.lwjgl.vulkan.VkExternalBufferProperties;
import org.lwjgl.vulkan.VkExternalMemoryBufferCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryDedicatedAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceExternalBufferInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import java.nio.LongBuffer;

public final class VulkanExternalBufferPrototype implements AutoCloseable {
    public static final int WIDTH = 480;
    public static final int HEIGHT = 270;
    public static final int BYTES_PER_PIXEL = 8;
    public static final long LOGICAL_BYTES = (long) WIDTH * HEIGHT * BYTES_PER_PIXEL;

    private static final int HANDLE_TYPE =
            VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
    private static final int USAGE = VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT
            | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;

    private VkDevice device;
    private long buffer;
    private long memory;
    private long allocationBytes;
    private Telemetry telemetry = Telemetry.inactive("not initialized");

    public void initialize(Minecraft minecraft) {
        RenderSystem.assertOnRenderThread();
        close();

        VulkanCapabilityProbe.InteropBootstrap bootstrap =
                VulkanCapabilityProbe.interopBootstrap();
        if (!bootstrap.extensionsRequested()) {
            telemetry = Telemetry.inactive("device extensions not requested");
            return;
        }

        VulkanCapabilityProbe.Snapshot capabilities =
                VulkanCapabilityProbe.snapshot(minecraft);
        if (!capabilities.interopExtensionsEnabled()) {
            telemetry = Telemetry.inactive("device extensions not enabled");
            return;
        }

        try {
            GpuDeviceBackend backend =
                    ((GpuDeviceAccessor) (Object) RenderSystem.getDevice())
                            .cyclesrenderer$getBackend();
            if (!(backend instanceof VulkanDevice vulkanDevice)) {
                telemetry = Telemetry.inactive("active graphics backend is not Vulkan");
                return;
            }
            allocate(vulkanDevice);
            telemetry = new Telemetry(
                    true, true, LOGICAL_BYTES, allocationBytes, "allocated");
        } catch (RuntimeException | LinkageError error) {
            releaseHandles();
            telemetry = Telemetry.inactive(
                    error.getClass().getSimpleName() + ": "
                            + String.valueOf(error.getMessage()));
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

    private void allocate(VulkanDevice vulkanDevice) {
        VkDevice nextDevice = vulkanDevice.vkDevice();
        VkPhysicalDevice physicalDevice = nextDevice.getPhysicalDevice();
        validateExternalBufferSupport(physicalDevice);

        long nextBuffer = VK10.VK_NULL_HANDLE;
        long nextMemory = VK10.VK_NULL_HANDLE;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkExternalMemoryBufferCreateInfo externalBuffer =
                    VkExternalMemoryBufferCreateInfo.calloc(stack)
                            .sType$Default()
                            .handleTypes(HANDLE_TYPE);
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .pNext(externalBuffer.address())
                    .size(LOGICAL_BYTES)
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

            device = nextDevice;
            buffer = nextBuffer;
            memory = nextMemory;
            allocationBytes = requirements.size();
            nextBuffer = VK10.VK_NULL_HANDLE;
            nextMemory = VK10.VK_NULL_HANDLE;
        } finally {
            if (nextBuffer != VK10.VK_NULL_HANDLE) {
                VK10.vkDestroyBuffer(nextDevice, nextBuffer, null);
            }
            if (nextMemory != VK10.VK_NULL_HANDLE) {
                VK10.vkFreeMemory(nextDevice, nextMemory, null);
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
        releaseHandles();
        telemetry = Telemetry.inactive("released");
    }

    private void releaseHandles() {
        if (device != null && buffer != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyBuffer(device, buffer, null);
        }
        if (device != null && memory != VK10.VK_NULL_HANDLE) {
            VK10.vkFreeMemory(device, memory, null);
        }
        device = null;
        buffer = VK10.VK_NULL_HANDLE;
        memory = VK10.VK_NULL_HANDLE;
        allocationBytes = 0L;
    }

    public record Telemetry(
            boolean requested,
            boolean allocated,
            long logicalBytes,
            long allocationBytes,
            String state) {
        private static Telemetry inactive(String state) {
            return new Telemetry(false, false, 0L, 0L, state);
        }
    }
}
