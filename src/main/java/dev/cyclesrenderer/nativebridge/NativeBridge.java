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
    private static final int BUILD_INFO_BYTES = 128;

    private static BridgeState bridgeState;

    private NativeBridge() {
    }

    public static ProbeResult probe() {
        close();

        String configuredPath = System.getProperty(LIBRARY_PATH_PROPERTY);
        if (configuredPath == null || configuredPath.isBlank()) {
            return ProbeResult.failure("missing system property " + LIBRARY_PATH_PROPERTY);
        }

        Path libraryPath = Path.of(configuredPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(libraryPath)) {
            return ProbeResult.failure("native library not found at " + libraryPath);
        }

        BridgeState loadedState = null;
        try {
            loadedState = BridgeState.open(libraryPath);
            ByteBuffer testFrame = loadedState.fillFrame(TEST_WIDTH, TEST_HEIGHT, TEST_FRAME_ID);
            long checksum = verifyTestFrame(testFrame);
            bridgeState = loadedState;
            return ProbeResult.success(
                    loadedState.buildInfo() + ", verified " + TEST_WIDTH + "x" + TEST_HEIGHT
                            + " RGBA frame, checksum=" + Long.toUnsignedString(checksum));
        } catch (Throwable error) {
            if (loadedState != null) {
                loadedState.close();
            }
            rethrowFatalError(error);
            return ProbeResult.failure(describe(error));
        }
    }

    public static ByteBuffer renderFrame(int width, int height, long frameId) {
        BridgeState state = bridgeState;
        if (state == null) {
            throw new IllegalStateException("native bridge is not initialized");
        }

        try {
            return state.fillFrame(width, height, frameId);
        } catch (Throwable error) {
            rethrowFatalError(error);
            throw new IllegalStateException("native frame call failed: " + describe(error), error);
        }
    }

    public static void close() {
        BridgeState state = bridgeState;
        bridgeState = null;
        if (state != null) {
            state.close();
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

    private static void rethrowFatalError(Throwable error) {
        if (error instanceof Error fatalError && !(fatalError instanceof LinkageError)) {
            throw fatalError;
        }
    }

    private static String describe(Throwable error) {
        String detail = error.getMessage();
        return error.getClass().getSimpleName() + (detail == null ? "" : ": " + detail);
    }

    private static final class BridgeState implements AutoCloseable {
        private final Arena libraryArena;
        private final MethodHandle fillTestFrame;
        private final String buildInfo;

        private Arena frameArena;
        private MemorySegment frameSegment;
        private ByteBuffer framePixels;
        private int frameBytes;

        private BridgeState(Arena libraryArena, MethodHandle fillTestFrame, String buildInfo) {
            this.libraryArena = libraryArena;
            this.fillTestFrame = fillTestFrame;
            this.buildInfo = buildInfo;
        }

        private static BridgeState open(Path libraryPath) throws Throwable {
            Arena libraryArena = Arena.ofConfined();
            try {
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
                    throw new IllegalStateException(
                            "ABI mismatch: Java=" + ABI_VERSION + ", native=" + actualAbiVersion);
                }

                String buildInfo;
                try (Arena dataArena = Arena.ofConfined()) {
                    MemorySegment buildInfoBuffer = dataArena.allocate(BUILD_INFO_BYTES);
                    int buildInfoStatus = (int) writeBuildInfo.invokeExact(
                            buildInfoBuffer, BUILD_INFO_BYTES);
                    if (buildInfoStatus != STATUS_OK) {
                        throw new IllegalStateException(
                                "build info call returned status " + buildInfoStatus);
                    }
                    buildInfo = buildInfoBuffer.getString(0, StandardCharsets.UTF_8);
                }

                return new BridgeState(libraryArena, fillTestFrame, buildInfo);
            } catch (Throwable error) {
                libraryArena.close();
                throw error;
            }
        }

        private ByteBuffer fillFrame(int width, int height, long frameId) throws Throwable {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException(
                        "frame dimensions must be positive, got " + width + "x" + height);
            }

            long byteCount = Math.multiplyExact(Math.multiplyExact((long) width, height), 4L);
            if (byteCount > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("frame is too large: " + width + "x" + height);
            }

            int requiredBytes = (int) byteCount;
            if (frameSegment == null || frameBytes != requiredBytes) {
                if (frameArena != null) {
                    frameArena.close();
                }
                frameArena = Arena.ofConfined();
                frameSegment = frameArena.allocate(requiredBytes, 16);
                framePixels = frameSegment.asByteBuffer();
                frameBytes = requiredBytes;
            }

            int frameStatus = (int) fillTestFrame.invokeExact(
                    frameSegment, width, height, frameId);
            if (frameStatus != STATUS_OK) {
                throw new IllegalStateException("test frame call returned status " + frameStatus);
            }

            framePixels.clear();
            return framePixels;
        }

        private String buildInfo() {
            return buildInfo;
        }

        @Override
        public void close() {
            if (frameArena != null) {
                frameArena.close();
                frameArena = null;
                frameSegment = null;
                framePixels = null;
                frameBytes = 0;
            }
            libraryArena.close();
        }
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
