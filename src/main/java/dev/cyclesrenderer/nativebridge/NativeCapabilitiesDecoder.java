package dev.cyclesrenderer.nativebridge;

import java.lang.foreign.MemorySegment;

import static dev.cyclesrenderer.nativebridge.NativeLayouts.CAPABILITIES_LAYOUT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Prepares and decodes the native capabilities response. */
final class NativeCapabilitiesDecoder {
    private NativeCapabilitiesDecoder() {
    }

    static void prepare(MemorySegment target, int structVersion) {
        target.fill((byte) 0);
        target.set(JAVA_INT, 0L, Math.toIntExact(CAPABILITIES_LAYOUT.byteSize()));
        target.set(JAVA_INT, 4L, structVersion);
    }

    static NativeBridge.Capabilities decode(MemorySegment source) {
        return new NativeBridge.Capabilities(
                source.get(JAVA_LONG, 8L),
                source.get(JAVA_LONG, 16L),
                source.get(JAVA_INT, 24L),
                source.get(JAVA_INT, 28L),
                source.get(JAVA_INT, 32L),
                source.get(JAVA_INT, 36L),
                source.get(JAVA_INT, 40L),
                source.get(JAVA_INT, 44L),
                source.get(JAVA_INT, 48L),
                source.get(JAVA_INT, 52L),
                source.get(JAVA_INT, 56L));
    }
}
