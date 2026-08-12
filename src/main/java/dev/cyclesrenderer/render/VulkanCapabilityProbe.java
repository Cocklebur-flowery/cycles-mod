package dev.cyclesrenderer.render;

import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import dev.cyclesrenderer.mixin.GpuDeviceAccessor;
import dev.cyclesrenderer.mixin.GpuSurfaceAccessor;
import dev.cyclesrenderer.mixin.VulkanGpuSurfaceAccessor;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceIDProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class VulkanCapabilityProbe {
    private static final int MAX_EXTENSION_COUNT = 4_096;
    private static final int MAX_SURFACE_FORMAT_COUNT = 256;
    private static final String SWAPCHAIN_COLORSPACE = "VK_EXT_swapchain_colorspace";
    private static final String EXTERNAL_MEMORY_WIN32 = "VK_KHR_external_memory_win32";
    private static final String EXTERNAL_SEMAPHORE_WIN32 = "VK_KHR_external_semaphore_win32";
    private static final String HDR_METADATA = "VK_EXT_hdr_metadata";
    private static final String INTEROP_PROPERTY =
            "cyclesrenderer.experimentalVulkanInterop";
    private static final String INTEROP_ENVIRONMENT =
            "CYCLESRENDERER_VULKAN_INTEROP";
    private static final InteropRequest INTEROP_REQUEST = interopRequest();

    private static volatile Snapshot cached;
    private static volatile InteropBootstrap interopBootstrap =
            new InteropBootstrap(
                    INTEROP_REQUEST.requested(),
                    INTEROP_REQUEST.source(),
                    false,
                    false,
                    false,
                    false,
                    INTEROP_REQUEST.requested()
                            ? "awaiting device creation"
                            : "disabled");

    private VulkanCapabilityProbe() {
    }

    public static Collection<String> withRequestedInteropExtensions(
            Collection<String> requestedExtensions,
            com.mojang.blaze3d.vulkan.VulkanPhysicalDevice physicalDevice) {
        boolean memoryAvailable = physicalDevice.hasDeviceExtension(EXTERNAL_MEMORY_WIN32);
        boolean semaphoreAvailable =
                physicalDevice.hasDeviceExtension(EXTERNAL_SEMAPHORE_WIN32);
        if (!INTEROP_REQUEST.requested()) {
            interopBootstrap = new InteropBootstrap(
                    false,
                    INTEROP_REQUEST.source(),
                    true,
                    memoryAvailable,
                    semaphoreAvailable,
                    false,
                    "disabled");
            return requestedExtensions;
        }
        if (!memoryAvailable || !semaphoreAvailable) {
            String reason = !memoryAvailable && !semaphoreAvailable
                    ? "memory and semaphore extensions unavailable"
                    : !memoryAvailable
                            ? "memory extension unavailable"
                            : "semaphore extension unavailable";
            interopBootstrap = new InteropBootstrap(
                    true,
                    INTEROP_REQUEST.source(),
                    true,
                    memoryAvailable,
                    semaphoreAvailable,
                    false,
                    reason);
            return requestedExtensions;
        }

        // Keep the caller-owned set in sync with the collection passed to
        // vkCreateDevice. Minecraft later reuses that same set to populate
        // DeviceInfo, which is also our post-creation source of truth.
        requestedExtensions.add(EXTERNAL_MEMORY_WIN32);
        requestedExtensions.add(EXTERNAL_SEMAPHORE_WIN32);
        interopBootstrap = new InteropBootstrap(
                true,
                INTEROP_REQUEST.source(),
                true,
                true,
                true,
                true,
                "extensions requested");
        return requestedExtensions;
    }

    public static InteropBootstrap interopBootstrap() {
        return interopBootstrap;
    }

    public static Snapshot snapshot(Minecraft minecraft) {
        Snapshot current = cached;
        if (current != null) {
            return current;
        }
        synchronized (VulkanCapabilityProbe.class) {
            if (cached == null) {
                cached = probe(minecraft);
            }
            return cached;
        }
    }

    private static Snapshot probe(Minecraft minecraft) {
        try {
            GpuDeviceBackend deviceBackend =
                    ((GpuDeviceAccessor) (Object) RenderSystem.getDevice())
                            .cyclesrenderer$getBackend();
            GpuSurfaceBackend surfaceBackend =
                    ((GpuSurfaceAccessor) (Object) minecraft.windowSurface())
                            .cyclesrenderer$getBackend();
            if (!(deviceBackend instanceof VulkanDevice device)
                    || !(surfaceBackend instanceof VulkanGpuSurface surface)) {
                return Snapshot.unavailable("active graphics backend is not Vulkan");
            }

            VkPhysicalDevice physicalDevice = device.vkDevice().getPhysicalDevice();
            Set<String> supportedInstanceExtensions = supportedInstanceExtensions();
            Set<String> supportedDeviceExtensions =
                    supportedDeviceExtensions(physicalDevice);
            Set<String> enabledInstanceExtensions =
                    Set.copyOf(device.instance().getEnabledExtensions());
            Set<String> enabledDeviceExtensions = enabledDeviceExtensions();
            var deviceCapabilities = device.vkDevice().getCapabilities();
            long surfaceHandle = ((VulkanGpuSurfaceAccessor) (Object) surface)
                    .cyclesrenderer$getSurface();
            List<SurfaceFormat> surfaceFormats = surfaceFormats(physicalDevice, surfaceHandle);

            return new Snapshot(
                    true,
                    RenderSystem.getDevice().getDeviceInfo().name(),
                    physicalDeviceUuid(physicalDevice),
                    state(SWAPCHAIN_COLORSPACE,
                            supportedInstanceExtensions, enabledInstanceExtensions),
                    state(EXTERNAL_MEMORY_WIN32,
                            supportedDeviceExtensions,
                            deviceCapabilities.VK_KHR_external_memory_win32),
                    state(EXTERNAL_SEMAPHORE_WIN32,
                            supportedDeviceExtensions,
                            deviceCapabilities.VK_KHR_external_semaphore_win32),
                    state(HDR_METADATA,
                            supportedDeviceExtensions, enabledDeviceExtensions),
                    List.copyOf(surfaceFormats),
                    "");
        } catch (RuntimeException | LinkageError error) {
            return Snapshot.unavailable(error.getClass().getSimpleName() + ": "
                    + String.valueOf(error.getMessage()));
        }
    }

    private static InteropRequest interopRequest() {
        String property = System.getProperty(INTEROP_PROPERTY);
        if (property != null) {
            return new InteropRequest(Boolean.parseBoolean(property), "jvm-property");
        }
        String environment = System.getenv(INTEROP_ENVIRONMENT);
        if (environment != null) {
            return new InteropRequest(
                    "1".equals(environment) || Boolean.parseBoolean(environment),
                    "environment");
        }
        return new InteropRequest(true, "default-enabled");
    }

    private static Set<String> supportedInstanceExtensions() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.callocInt(1);
            check(VK10.vkEnumerateInstanceExtensionProperties((ByteBuffer) null, count, null),
                    "enumerate instance extensions");
            int extensionCount = checkedCount(
                    count.get(0), MAX_EXTENSION_COUNT, "instance extensions");
            try (VkExtensionProperties.Buffer properties =
                         VkExtensionProperties.calloc(extensionCount)) {
                check(VK10.vkEnumerateInstanceExtensionProperties(
                                (ByteBuffer) null, count, properties),
                        "read instance extensions");
                return extensionNames(properties, count.get(0));
            }
        }
    }

    private static Set<String> supportedDeviceExtensions(VkPhysicalDevice physicalDevice) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.callocInt(1);
            check(VK10.vkEnumerateDeviceExtensionProperties(
                            physicalDevice, (ByteBuffer) null, count, null),
                    "enumerate device extensions");
            int extensionCount = checkedCount(
                    count.get(0), MAX_EXTENSION_COUNT, "device extensions");
            try (VkExtensionProperties.Buffer properties =
                         VkExtensionProperties.calloc(extensionCount)) {
                check(VK10.vkEnumerateDeviceExtensionProperties(
                                physicalDevice, (ByteBuffer) null, count, properties),
                        "read device extensions");
                return extensionNames(properties, count.get(0));
            }
        }
    }

    private static Set<String> enabledDeviceExtensions() {
        Set<String> enabled = new HashSet<>();
        for (String extension : RenderSystem.getDevice().getDeviceInfo().underlyingExtensions()) {
            if (extension.endsWith(" (D)")) {
                enabled.add(extension.substring(0, extension.length() - 4));
            }
        }
        return Set.copyOf(enabled);
    }

    private static Set<String> extensionNames(
            VkExtensionProperties.Buffer properties,
            int count) {
        Set<String> result = new HashSet<>();
        int limit = Math.min(count, properties.capacity());
        for (int index = 0; index < limit; index++) {
            result.add(properties.get(index).extensionNameString());
        }
        return Set.copyOf(result);
    }

    private static String physicalDeviceUuid(VkPhysicalDevice physicalDevice) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceIDProperties id =
                    VkPhysicalDeviceIDProperties.calloc(stack).sType$Default();
            VkPhysicalDeviceProperties2 properties =
                    VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
            properties.pNext(id.address());
            VK11.vkGetPhysicalDeviceProperties2(physicalDevice, properties);
            ByteBuffer uuid = id.deviceUUID();
            StringBuilder result = new StringBuilder(32);
            for (int index = 0; index < 16; index++) {
                result.append(String.format(Locale.ROOT, "%02x", uuid.get(index) & 0xff));
            }
            return result.toString();
        }
    }

    private static List<SurfaceFormat> surfaceFormats(
            VkPhysicalDevice physicalDevice,
            long surface) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.callocInt(1);
            check(KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(
                            physicalDevice, surface, count, null),
                    "enumerate surface formats");
            int formatCount = checkedCount(
                    count.get(0), MAX_SURFACE_FORMAT_COUNT, "surface formats");
            try (VkSurfaceFormatKHR.Buffer formats =
                         VkSurfaceFormatKHR.calloc(formatCount)) {
                check(KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(
                                physicalDevice, surface, count, formats),
                        "read surface formats");
                int limit = Math.min(count.get(0), formats.capacity());
                List<SurfaceFormat> result = new ArrayList<>(limit);
                for (int index = 0; index < limit; index++) {
                    VkSurfaceFormatKHR format = formats.get(index);
                    result.add(new SurfaceFormat(format.format(), format.colorSpace()));
                }
                return result;
            }
        }
    }

    private static int checkedCount(int count, int maximum, String description) {
        if (count < 0 || count > maximum) {
            throw new IllegalStateException(
                    "invalid " + description + " count: " + count);
        }
        return count;
    }

    private static ExtensionState state(
            String name,
            Set<String> supported,
            Set<String> enabled) {
        return new ExtensionState(name, supported.contains(name), enabled.contains(name));
    }

    private static ExtensionState state(
            String name,
            Set<String> supported,
            boolean enabled) {
        return new ExtensionState(name, supported.contains(name), enabled);
    }

    private static void check(int result, String operation) {
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with VkResult " + result);
        }
    }

    public record ExtensionState(String name, boolean available, boolean enabled) {
        public String summary() {
            return (available ? "available" : "missing") + "/"
                    + (enabled ? "enabled" : "disabled");
        }
    }

    private record InteropRequest(boolean requested, String source) {
    }

    public record InteropBootstrap(
            boolean requested,
            String requestSource,
            boolean attempted,
            boolean memoryAvailable,
            boolean semaphoreAvailable,
            boolean extensionsRequested,
            String reason) {
        public String summary() {
            return "request=" + requested
                    + " source=" + requestSource
                    + " attempted=" + attempted
                    + " injected=" + extensionsRequested
                    + " reason=" + reason;
        }
    }

    public record SurfaceFormat(int format, int colorSpace) {
        public boolean hdrCandidate() {
            return colorSpace == 1_000_104_002
                    || colorSpace == 1_000_104_007
                    || colorSpace == 1_000_104_008
                    || colorSpace == 1_000_104_010;
        }

        public String summary() {
            return formatName(format) + "/" + colorSpaceName(colorSpace);
        }

        private static String formatName(int value) {
            return switch (value) {
                case 37 -> "RGBA8_UNORM";
                case 44 -> "BGRA8_UNORM";
                case 64 -> "A2B10G10R10_UNORM";
                case 97 -> "RGBA16_SFLOAT";
                default -> "format-" + value;
            };
        }

        private static String colorSpaceName(int value) {
            return switch (value) {
                case 0 -> "sRGB-nonlinear";
                case 1_000_104_002 -> "extended-sRGB-linear";
                case 1_000_104_007 -> "BT2020-linear";
                case 1_000_104_008 -> "HDR10-PQ";
                case 1_000_104_010 -> "HDR10-HLG";
                default -> "colorspace-" + value;
            };
        }
    }

    public record Snapshot(
            boolean vulkan,
            String deviceName,
            String physicalDeviceUuid,
            ExtensionState swapchainColorspace,
            ExtensionState externalMemoryWin32,
            ExtensionState externalSemaphoreWin32,
            ExtensionState hdrMetadata,
            List<SurfaceFormat> surfaceFormats,
            String error) {
        private static Snapshot unavailable(String error) {
            ExtensionState unavailable = new ExtensionState("unavailable", false, false);
            return new Snapshot(false, "unavailable", "unavailable",
                    unavailable, unavailable, unavailable, unavailable, List.of(), error);
        }

        public long hdrSurfaceFormatCount() {
            return surfaceFormats.stream().filter(SurfaceFormat::hdrCandidate).count();
        }

        public boolean uuidMatches(String cyclesDeviceUuid) {
            return physicalDeviceUuid.length() == 32
                    && physicalDeviceUuid.equalsIgnoreCase(cyclesDeviceUuid);
        }

        public boolean interopExtensionsAvailable() {
            return externalMemoryWin32.available()
                    && externalSemaphoreWin32.available();
        }

        public boolean interopExtensionsEnabled() {
            return externalMemoryWin32.enabled()
                    && externalSemaphoreWin32.enabled();
        }

        public boolean hdrSurfaceAdvertised() {
            return hdrSurfaceFormatCount() > 0;
        }

        public boolean hdrNegotiationEnabled() {
            return swapchainColorspace.enabled() && hdrSurfaceFormatCount() > 0;
        }

        public String surfaceFormatSummary() {
            if (surfaceFormats.isEmpty()) {
                return "none";
            }
            int limit = Math.min(surfaceFormats.size(), 3);
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < limit; index++) {
                if (index > 0) {
                    result.append(", ");
                }
                result.append(surfaceFormats.get(index).summary());
            }
            if (surfaceFormats.size() > limit) {
                result.append(" +").append(surfaceFormats.size() - limit);
            }
            return result.toString();
        }
    }
}
