package dev.cyclesrenderer.nativebridge;

import java.lang.foreign.MemorySegment;

import static dev.cyclesrenderer.nativebridge.NativeLayouts.PASS_DESCRIPTOR_LAYOUT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** Prepares and decodes the native render-pass descriptor response. */
final class NativePassDescriptorDecoder {
    private NativePassDescriptorDecoder() {
    }

    static void prepare(MemorySegment target, int structVersion) {
        target.fill((byte) 0);
        target.set(JAVA_INT, 0L, Math.toIntExact(PASS_DESCRIPTOR_LAYOUT.byteSize()));
        target.set(JAVA_INT, 4L, structVersion);
    }

    static NativeBridge.PassDescriptor decode(MemorySegment source) {
        return new NativeBridge.PassDescriptor(
                source.get(JAVA_INT, 8L),
                source.get(JAVA_INT, 12L),
                source.get(JAVA_INT, 16L),
                source.get(JAVA_INT, 20L),
                source.get(JAVA_INT, 24L),
                source.get(JAVA_INT, 28L));
    }
}
