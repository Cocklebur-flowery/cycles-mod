package dev.cyclesrenderer.nativebridge;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static dev.cyclesrenderer.nativebridge.NativeLayouts.VULKAN_INTEROP_BUFFER_LAYOUT;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Encodes Vulkan interop descriptors and decodes the generated state layout. */
final class NativeVulkanInteropMarshaller {
    private NativeVulkanInteropMarshaller() {
    }

    static MemorySegment writeBufferDescriptor(
            Arena arena,
            int structVersion,
            int pixelFormat,
            int width,
            int height,
            long allocationBytes,
            long memoryHandle,
            long readySemaphoreHandle,
            long releaseSemaphoreHandle,
            String deviceUuid,
            int slotCount,
            int slotStrideBytes,
            boolean reprojectionInputs) {
        if (width <= 0 || height <= 0 || allocationBytes <= 0L
                || memoryHandle == 0L || readySemaphoreHandle == 0L
                || releaseSemaphoreHandle == 0L || slotCount <= 0
                || slotStrideBytes <= 0) {
            throw new IllegalArgumentException("invalid Vulkan interop buffer descriptor");
        }
        byte[] uuid = parseDeviceUuid(deviceUuid);
        MemorySegment descriptor = arena.allocate(VULKAN_INTEROP_BUFFER_LAYOUT);
        descriptor.set(
                JAVA_INT, 0L, Math.toIntExact(VULKAN_INTEROP_BUFFER_LAYOUT.byteSize()));
        descriptor.set(JAVA_INT, 4L, structVersion);
        descriptor.set(JAVA_INT, 8L, width);
        descriptor.set(JAVA_INT, 12L, height);
        descriptor.set(JAVA_INT, 16L, pixelFormat);
        descriptor.set(JAVA_INT, 20L, reprojectionInputs ? 3 : 1);
        descriptor.set(JAVA_LONG, 24L, allocationBytes);
        descriptor.set(JAVA_LONG, 32L, memoryHandle);
        for (int index = 0; index < uuid.length; index++) {
            descriptor.set(JAVA_BYTE, 40L + index, uuid[index]);
        }
        descriptor.set(JAVA_INT, 56L, slotCount);
        descriptor.set(JAVA_INT, 60L, slotStrideBytes);
        descriptor.set(JAVA_LONG, 64L, readySemaphoreHandle);
        descriptor.set(JAVA_LONG, 72L, releaseSemaphoreHandle);
        return descriptor;
    }

    static void prepareState(MemorySegment state, int structVersion) {
        state.fill((byte) 0);
        state.set(
                JAVA_INT,
                VulkanInteropStateAbi.STRUCT_SIZE_OFFSET,
                Math.toIntExact(VulkanInteropStateAbi.BYTE_SIZE));
        state.set(
                JAVA_INT,
                VulkanInteropStateAbi.STRUCT_VERSION_OFFSET,
                structVersion);
    }

    static NativeBridge.VulkanInteropState decodeState(MemorySegment state) {
        return new NativeBridge.VulkanInteropState(
                state.get(JAVA_INT, VulkanInteropStateAbi.FLAGS_OFFSET),
                state.get(JAVA_INT, VulkanInteropStateAbi.WIDTH_OFFSET),
                state.get(JAVA_INT, VulkanInteropStateAbi.HEIGHT_OFFSET),
                state.get(JAVA_INT, VulkanInteropStateAbi.SAMPLE_COUNT_OFFSET),
                state.get(JAVA_LONG, VulkanInteropStateAbi.GENERATION_OFFSET),
                state.get(JAVA_LONG, VulkanInteropStateAbi.COMPLETED_FRAME_COUNT_OFFSET),
                state.get(JAVA_INT, VulkanInteropStateAbi.LAST_SYNC_MICROS_OFFSET),
                state.get(JAVA_INT, VulkanInteropStateAbi.EMA_SYNC_MICROS_OFFSET),
                state.get(JAVA_INT, VulkanInteropStateAbi.MAX_SYNC_MICROS_OFFSET),
                state.get(JAVA_INT, VulkanInteropStateAbi.SLOT_INDEX_OFFSET),
                state.get(JAVA_INT, VulkanInteropStateAbi.SLOT_COUNT_OFFSET),
                state.get(JAVA_INT, VulkanInteropStateAbi.READY_SLOT_COUNT_OFFSET),
                state.get(JAVA_LONG, VulkanInteropStateAbi.PRODUCER_WAIT_COUNT_OFFSET),
                state.get(JAVA_INT, VulkanInteropStateAbi.DEPTH_WIDTH_OFFSET),
                state.get(JAVA_INT, VulkanInteropStateAbi.DEPTH_HEIGHT_OFFSET));
    }

    private static byte[] parseDeviceUuid(String value) {
        if (value == null || value.length() != 32) {
            throw new IllegalArgumentException("invalid Vulkan device UUID: " + value);
        }
        byte[] result = new byte[16];
        for (int index = 0; index < result.length; index++) {
            int high = Character.digit(value.charAt(index * 2), 16);
            int low = Character.digit(value.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("invalid Vulkan device UUID: " + value);
            }
            result[index] = (byte) ((high << 4) | low);
        }
        return result;
    }
}
