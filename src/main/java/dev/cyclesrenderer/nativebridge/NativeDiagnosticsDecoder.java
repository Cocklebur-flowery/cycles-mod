package dev.cyclesrenderer.nativebridge;

import java.lang.foreign.MemorySegment;

import static dev.cyclesrenderer.nativebridge.NativeLayouts.DIAGNOSTICS_LAYOUT;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Prepares and decodes the native diagnostics response. */
final class NativeDiagnosticsDecoder {
    private NativeDiagnosticsDecoder() {
    }

    static void prepare(MemorySegment target, int structVersion) {
        target.fill((byte) 0);
        target.set(JAVA_INT, 0L, Math.toIntExact(DIAGNOSTICS_LAYOUT.byteSize()));
        target.set(JAVA_INT, 4L, structVersion);
    }

    static NativeBridge.Diagnostics decode(MemorySegment source, int devicePhaseCount) {
        return new NativeBridge.Diagnostics(
                source.get(JAVA_LONG, 8L),
                source.get(JAVA_LONG, 16L),
                source.get(JAVA_LONG, 24L),
                source.get(JAVA_LONG, 32L),
                source.get(JAVA_INT, 40L),
                source.get(JAVA_INT, 44L),
                source.get(JAVA_INT, 48L),
                source.get(JAVA_INT, 52L),
                source.get(JAVA_INT, 56L),
                source.get(JAVA_INT, 60L),
                source.get(JAVA_INT, 64L),
                source.get(JAVA_INT, 68L),
                source.get(JAVA_INT, 72L),
                source.get(JAVA_INT, 76L) != 0,
                source.get(JAVA_INT, 80L),
                source.get(JAVA_INT, 84L),
                source.get(JAVA_INT, 88L),
                source.get(JAVA_INT, 92L),
                source.get(JAVA_INT, 96L),
                source.get(JAVA_INT, 100L),
                source.get(JAVA_FLOAT, 104L),
                source.get(JAVA_INT, 108L),
                source.get(JAVA_LONG, 112L),
                source.get(JAVA_LONG, 120L),
                source.get(JAVA_LONG, 128L),
                source.get(JAVA_LONG, 136L),
                source.get(JAVA_INT, 144L),
                source.get(JAVA_INT, 148L),
                source.get(JAVA_INT, 152L),
                source.get(JAVA_INT, 156L),
                source.get(JAVA_INT, 160L),
                source.get(JAVA_INT, 164L),
                source.get(JAVA_INT, 168L),
                source.get(JAVA_INT, 172L),
                source.get(JAVA_LONG, 176L),
                source.get(JAVA_LONG, 184L),
                source.get(JAVA_LONG, 192L),
                source.get(JAVA_INT, 200L),
                source.get(JAVA_INT, 204L),
                source.get(JAVA_INT, 208L),
                source.get(JAVA_INT, 212L),
                source.get(JAVA_INT, 216L),
                source.get(JAVA_INT, 220L),
                source.get(JAVA_INT, 224L),
                source.get(JAVA_INT, 228L),
                source.get(JAVA_INT, 232L),
                source.get(JAVA_INT, 236L),
                source.get(JAVA_LONG, 240L),
                source.get(JAVA_LONG, 248L),
                source.get(JAVA_LONG, 256L),
                source.get(JAVA_LONG, 264L),
                source.get(JAVA_INT, 272L),
                source.get(JAVA_INT, 276L),
                source.get(JAVA_INT, 280L),
                source.get(JAVA_INT, 284L),
                source.get(JAVA_LONG, 288L),
                source.get(JAVA_INT, 296L),
                source.get(JAVA_INT, 300L),
                source.get(JAVA_INT, 304L),
                source.get(JAVA_INT, 308L),
                source.get(JAVA_INT, 312L),
                source.get(JAVA_INT, 316L),
                source.get(JAVA_INT, 320L),
                source.get(JAVA_INT, 324L),
                source.get(JAVA_INT, 328L),
                source.get(JAVA_FLOAT, 332L),
                source.get(JAVA_FLOAT, 336L),
                source.get(JAVA_INT, 340L),
                source.get(JAVA_FLOAT, 344L),
                source.get(JAVA_INT, 348L) != 0,
                source.get(JAVA_FLOAT, 352L),
                source.get(JAVA_FLOAT, 356L),
                source.get(JAVA_FLOAT, 360L),
                source.get(JAVA_INT, 364L),
                source.get(JAVA_FLOAT, 368L),
                source.get(JAVA_FLOAT, 372L),
                source.get(JAVA_INT, 376L) != 0
                        ? uuidString(source.asSlice(380L, 16L))
                        : "unavailable",
                source.get(JAVA_LONG, 400L),
                source.get(JAVA_LONG, 408L),
                source.get(JAVA_INT, 416L),
                source.get(JAVA_INT, 420L),
                source.get(JAVA_INT, 424L),
                source.get(JAVA_INT, 428L),
                source.get(JAVA_INT, 432L),
                source.get(JAVA_INT, 436L),
                source.get(JAVA_INT, 440L),
                source.get(JAVA_INT, 444L),
                source.get(JAVA_INT, 448L),
                source.get(JAVA_INT, 452L),
                source.get(JAVA_INT, 456L),
                source.get(JAVA_INT, 460L),
                source.get(JAVA_INT, 464L),
                source.get(JAVA_INT, 468L),
                source.get(JAVA_INT, 472L),
                source.get(JAVA_INT, 488L),
                source.get(JAVA_INT, 492L),
                source.get(JAVA_INT, 496L),
                source.get(JAVA_INT, 504L),
                source.get(JAVA_INT, 508L),
                source.get(JAVA_INT, 512L),
                source.get(JAVA_INT, 516L),
                intArray(source, 520L, devicePhaseCount),
                intArray(source, 552L, devicePhaseCount),
                intArray(source, 584L, devicePhaseCount),
                source.get(JAVA_FLOAT, 616L),
                source.get(JAVA_FLOAT, 620L),
                source.get(JAVA_INT, 624L),
                source.get(JAVA_INT, 628L),
                source.get(JAVA_INT, 632L),
                source.get(JAVA_INT, 636L),
                source.get(JAVA_INT, 640L),
                source.get(JAVA_INT, 644L),
                source.get(JAVA_INT, 648L),
                source.get(JAVA_INT, 652L),
                source.get(JAVA_INT, 656L),
                source.get(JAVA_INT, 660L),
                source.get(JAVA_INT, 664L),
                source.get(JAVA_INT, 668L));
    }

    private static int[] intArray(MemorySegment source, long offset, int count) {
        int[] result = new int[count];
        for (int index = 0; index < count; index++) {
            result[index] = source.get(JAVA_INT, offset + index * Integer.BYTES);
        }
        return result;
    }

    private static String uuidString(MemorySegment bytes) {
        StringBuilder result = new StringBuilder(32);
        for (long index = 0; index < bytes.byteSize(); index++) {
            result.append(String.format(
                    java.util.Locale.ROOT,
                    "%02x",
                    bytes.get(JAVA_BYTE, index) & 0xff));
        }
        return result.toString();
    }
}
