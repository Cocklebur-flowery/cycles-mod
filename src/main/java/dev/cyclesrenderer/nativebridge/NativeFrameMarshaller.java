package dev.cyclesrenderer.nativebridge;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static dev.cyclesrenderer.nativebridge.NativeLayouts.CAMERA_LAYOUT;
import static dev.cyclesrenderer.nativebridge.NativeLayouts.FRAME_LAYOUT;
import static dev.cyclesrenderer.nativebridge.NativeLayouts.FRAME_VIEW_LAYOUT;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Encodes camera and frame request headers and owns borrowed native frame leases. */
final class NativeFrameMarshaller {
    private NativeFrameMarshaller() {
    }

    static void writeCamera(
            MemorySegment camera,
            int structVersion,
            int width,
            int height,
            long frameId,
            NativeBridge.CameraInput input) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid viewport " + width + "x" + height);
        }
        camera.set(JAVA_INT, 0L, Math.toIntExact(CAMERA_LAYOUT.byteSize()));
        camera.set(JAVA_INT, 4L, structVersion);
        camera.set(JAVA_LONG, 8L, frameId);
        camera.set(JAVA_INT, 16L, width);
        camera.set(JAVA_INT, 20L, height);
        camera.set(JAVA_DOUBLE, 24L, input.positionX());
        camera.set(JAVA_DOUBLE, 32L, input.positionY());
        camera.set(JAVA_DOUBLE, 40L, input.positionZ());
        camera.set(JAVA_FLOAT, 48L, input.rotationX());
        camera.set(JAVA_FLOAT, 52L, input.rotationY());
        camera.set(JAVA_FLOAT, 56L, input.rotationZ());
        camera.set(JAVA_FLOAT, 60L, input.rotationW());
        camera.set(JAVA_FLOAT, 64L, input.verticalFovRadians());
        camera.set(JAVA_FLOAT, 68L, input.depthFar());
        camera.set(JAVA_FLOAT, 72L, input.focusDistance());
        camera.set(JAVA_INT, 76L, input.flags());
    }

    static void prepareFrameInfo(
            MemorySegment frameInfo,
            int structVersion,
            long generation) {
        frameInfo.set(JAVA_INT, 0L, Math.toIntExact(FRAME_LAYOUT.byteSize()));
        frameInfo.set(JAVA_INT, 4L, structVersion);
        frameInfo.set(JAVA_LONG, 16L, generation);
    }

    static void prepareFrameView(MemorySegment frameView, int structVersion) {
        frameView.fill((byte) 0);
        frameView.set(JAVA_INT, 0L, Math.toIntExact(FRAME_VIEW_LAYOUT.byteSize()));
        frameView.set(JAVA_INT, 4L, structVersion);
    }

    static final class FrameLease implements AutoCloseable {
        private Arena arena;
        private Releaser releaser;
        private final long token;
        private final ByteBuffer pixels;
        private boolean closed;

        FrameLease(
                Arena arena,
                Releaser releaser,
                long token,
                ByteBuffer pixels) {
            this.arena = arena;
            this.releaser = releaser;
            this.token = token;
            this.pixels = pixels;
        }

        static FrameLease open(
                MemorySegment pointer,
                long pixelBytes,
                long token,
                Releaser releaser) {
            Arena arena = Arena.ofConfined();
            try {
                ByteBuffer pixels = pointer.reinterpret(pixelBytes, arena, ignored -> {})
                        .asByteBuffer()
                        .order(ByteOrder.nativeOrder());
                return new FrameLease(arena, releaser, token, pixels);
            } catch (Throwable error) {
                arena.close();
                releaser.release(token);
                throw error;
            }
        }

        ByteBuffer pixels() {
            return pixels;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            Arena currentArena = arena;
            Releaser currentReleaser = releaser;
            arena = null;
            releaser = null;
            try {
                currentArena.close();
            } finally {
                currentReleaser.release(token);
            }
        }
    }

    @FunctionalInterface
    interface Releaser {
        void release(long token);
    }
}
