package dev.cyclesrenderer.nativebridge;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

import static dev.cyclesrenderer.nativebridge.NativeLayouts.COLOR_LUT_DESCRIPTOR_LAYOUT;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Prepares and decodes the native color-LUT response. */
final class NativeColorLutDecoder {
    private NativeColorLutDecoder() {
    }

    static void prepare(MemorySegment target, int structVersion) {
        target.fill((byte) 0);
        target.set(JAVA_INT, 0L, Math.toIntExact(COLOR_LUT_DESCRIPTOR_LAYOUT.byteSize()));
        target.set(JAVA_INT, 4L, structVersion);
    }

    static long byteCount(MemorySegment source) {
        long byteCount = source.get(JAVA_LONG, 32L);
        if (byteCount <= 0L || byteCount > Integer.MAX_VALUE || (byteCount & 15L) != 0L) {
            throw new IllegalStateException("invalid native color LUT byte count " + byteCount);
        }
        return byteCount;
    }

    static NativeBridge.ColorLut decode(MemorySegment source, ByteBuffer pixels) {
        pixels.clear();
        return new NativeBridge.ColorLut(
                new NativeBridge.ColorLutDescriptor(
                        source.get(JAVA_INT, 8L),
                        source.get(JAVA_INT, 12L),
                        source.get(JAVA_INT, 16L),
                        source.get(JAVA_INT, 20L),
                        source.get(JAVA_INT, 24L),
                        source.get(JAVA_INT, 28L),
                        source.get(JAVA_LONG, 32L),
                        source.get(JAVA_FLOAT, 40L),
                        source.get(JAVA_FLOAT, 44L),
                        source.get(JAVA_FLOAT, 48L),
                        source.get(JAVA_INT, 52L),
                        source.get(JAVA_INT, 56L),
                        source.get(JAVA_INT, 60L),
                        source.get(JAVA_INT, 64L)),
                pixels);
    }
}
