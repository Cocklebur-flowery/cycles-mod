package dev.cyclesrenderer.render;

import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.cyclesrenderer.mixin.GpuDeviceAccessor;
import dev.cyclesrenderer.nativebridge.NativeBridge;
import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRExternalMemoryWin32;
import org.lwjgl.vulkan.KHRExternalSemaphoreWin32;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK12;
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
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceExternalBufferInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import java.nio.LongBuffer;

final class VulkanSharedAllocation implements AutoCloseable {
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
    private boolean nativeBound;

    Initialization initialize(
            Minecraft minecraft,
            int capacityWidth,
            int capacityHeight,
            long logicalBytes,
            int slotCount,
            int slotStrideBytes,
            boolean reprojectionInputs) {
        VulkanCapabilityProbe.InteropBootstrap bootstrap =
                VulkanCapabilityProbe.interopBootstrap();
        if (!bootstrap.extensionsRequested()) {
            return Initialization.inactive("device extensions not requested");
        }

        VulkanCapabilityProbe.Snapshot capabilities =
                VulkanCapabilityProbe.snapshot(minecraft);
        if (!capabilities.interopExtensionsEnabled()) {
            return Initialization.inactive("device extensions not enabled");
        }

        long exportedHandle = 0L;
        long readyHandle = 0L;
        long releaseHandle = 0L;
        try {
            GpuDeviceBackend backend =
                    ((GpuDeviceAccessor) (Object) RenderSystem.getDevice())
                            .cyclesrenderer$getBackend();
            if (!(backend instanceof VulkanDevice activeVulkanDevice)) {
                return Initialization.inactive("active graphics backend is not Vulkan");
            }
            long ringBytes = Math.multiplyExact(logicalBytes, slotCount);
            allocate(activeVulkanDevice, ringBytes);
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
                        slotCount,
                        slotStrideBytes,
                        reprojectionInputs);
            } finally {
                // The NativeBridge call owns all three handles on every return path.
                exportedHandle = 0L;
                readyHandle = 0L;
                releaseHandle = 0L;
            }
            nativeBound = true;
            return Initialization.bound(allocationBytes);
        } catch (RuntimeException | LinkageError error) {
            closeExportedHandle(exportedHandle);
            closeExportedHandle(readyHandle);
            closeExportedHandle(releaseHandle);
            releaseHandles();
            return Initialization.inactive(
                    error.getClass().getSimpleName() + ": "
                            + String.valueOf(error.getMessage()));
        }
    }

    private static void closeExportedHandle(long handle) {
        if (handle != 0L) {
            NativeBridge.closeWin32Handle(handle);
        }
    }

    boolean nativeBound() {
        return nativeBound;
    }

    boolean allocated() {
        return device != null;
    }

    VulkanDevice vulkanDevice() {
        if (vulkanDevice == null) {
            throw new IllegalStateException("external buffer is not allocated");
        }
        return vulkanDevice;
    }

    long buffer() {
        if (buffer == VK10.VK_NULL_HANDLE) {
            throw new IllegalStateException("external buffer is not allocated");
        }
        return buffer;
    }

    long readySemaphore() {
        return readySemaphore;
    }

    long releaseSemaphore() {
        return releaseSemaphore;
    }

    long allocationBytes() {
        return allocationBytes;
    }

    void unbindNative() {
        if (nativeBound && NativeBridge.isReady()) {
            NativeBridge.VulkanInteropState state = NativeBridge.vulkanInteropState();
            if (state.sessionAttached()) {
                throw new IllegalStateException(
                        "native renderer must close before Vulkan interop memory");
            }
            NativeBridge.unbindVulkanInteropBuffer();
        }
        nativeBound = false;
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

    private void allocate(VulkanDevice nextVulkanDevice, long requestedBytes) {
        VkDevice nextDevice = nextVulkanDevice.vkDevice();
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
            vulkanDevice = nextVulkanDevice;
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
        releaseHandles();
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

    record Initialization(boolean nativeBound, long allocationBytes, String state) {
        private static Initialization bound(long allocationBytes) {
            return new Initialization(true, allocationBytes, "bound-native");
        }

        private static Initialization inactive(String state) {
            return new Initialization(false, 0L, state);
        }
    }
}
