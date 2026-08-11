package dev.cyclesrenderer.nativebridge;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

public final class NativeBridge {
    public static final int ABI_VERSION = 1;

    private static final String LIBRARY_PATH_PROPERTY = "cyclesrenderer.nativeLibrary";
    private static final int STATUS_OK = 0;
    private static final int TEST_WIDTH = 16;
    private static final int TEST_HEIGHT = 16;
    private static final long TEST_FRAME_ID = 7L;
    private static final int TEST_FRAME_BYTES = TEST_WIDTH * TEST_HEIGHT * 4;
    private static final int BUILD_INFO_BYTES = 128;

    private NativeBridge() {
    }

    public static ProbeResult probe() {
        String configuredPath = System.getProperty(LIBRARY_PATH_PROPERTY);
        if (configuredPath == null || configuredPath.isBlank()) {
            return ProbeResult.failure("missing system property " + LIBRARY_PATH_PROPERTY);
        }

        Path libraryPath = Path.of(configuredPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(libraryPath)) {
            return ProbeResult.failure("native library not found at " + libraryPath);
        }

        try (Arena libraryArena = Arena.ofConfined(); Arena dataArena = Arena.ofConfined()) {
            Linker linker = Linker.nativeLinker();
            SymbolLookup symbols = SymbolLookup.libraryLookup(libraryPath, libraryArena);
            MethodHandle abiVersion = linker.downcallHandle(
                    symbols.findOrThrow("cycles_bridge_abi_version"),
                    FunctionDescriptor.of(JAVA_INT));
            MethodHandle writeBuildInfo = linker.downcallHandle(
                    symbols.findOrThrow("cycles_bridge_write_build_info"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            MethodHandle fillTestFrame = linker.downcallHandle(
                    symbols.findOrThrow("cycles_bridge_fill_test_frame"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG));

            int actualAbiVersion = (int) abiVersion.invokeExact();
            if (actualAbiVersion != ABI_VERSION) {
                return ProbeResult.failure(
                        "ABI mismatch: Java=" + ABI_VERSION + ", native=" + actualAbiVersion);
            }

            MemorySegment buildInfoBuffer = dataArena.allocate(BUILD_INFO_BYTES);
            int buildInfoStatus = (int) writeBuildInfo.invokeExact(buildInfoBuffer, BUILD_INFO_BYTES);
            if (buildInfoStatus != STATUS_OK) {
                return ProbeResult.failure("build info call returned status " + buildInfoStatus);
            }
            String buildInfo = buildInfoBuffer.getString(0, StandardCharsets.UTF_8);

            MemorySegment frameBuffer = dataArena.allocate(TEST_FRAME_BYTES);
            int frameStatus = (int) fillTestFrame.invokeExact(
                    frameBuffer, TEST_WIDTH, TEST_HEIGHT, TEST_FRAME_ID);
            if (frameStatus != STATUS_OK) {
                return ProbeResult.failure("test frame call returned status " + frameStatus);
            }

            long checksum = verifyTestFrame(frameBuffer.asByteBuffer());
            return ProbeResult.success(
                    buildInfo + ", verified " + TEST_WIDTH + "x" + TEST_HEIGHT
                            + " RGBA frame, checksum=" + Long.toUnsignedString(checksum));
        } catch (Throwable error) {
            if (error instanceof Error fatalError && !(fatalError instanceof LinkageError)) {
                throw fatalError;
            }
            String detail = error.getMessage();
            return ProbeResult.failure(
                    error.getClass().getSimpleName() + (detail == null ? "" : ": " + detail));
        }
    }

    private static long verifyTestFrame(ByteBuffer pixels) {
        long checksum = 0;
        for (int y = 0; y < TEST_HEIGHT; y++) {
            for (int x = 0; x < TEST_WIDTH; x++) {
                int offset = (y * TEST_WIDTH + x) * 4;
                int expectedRed = (x * 17 + (int) TEST_FRAME_ID) & 0xFF;
                int expectedGreen = (y * 17 + (int) (TEST_FRAME_ID * 3)) & 0xFF;
                int expectedBlue = ((x ^ y) * 15 + (int) (TEST_FRAME_ID * 5)) & 0xFF;
                int[] expected = {expectedRed, expectedGreen, expectedBlue, 0xFF};

                for (int channel = 0; channel < expected.length; channel++) {
                    int actual = Byte.toUnsignedInt(pixels.get(offset + channel));
                    if (actual != expected[channel]) {
                        throw new IllegalStateException(
                                "test frame mismatch at (" + x + "," + y + ") channel " + channel
                                        + ": expected " + expected[channel] + ", got " + actual);
                    }
                    checksum = (checksum * 31 + actual) & 0xFFFFFFFFL;
                }
            }
        }
        return checksum;
    }

    public record ProbeResult(boolean success, String message) {
        private static ProbeResult success(String message) {
            return new ProbeResult(true, message);
        }

        private static ProbeResult failure(String message) {
            return new ProbeResult(false, message);
        }
    }
}
