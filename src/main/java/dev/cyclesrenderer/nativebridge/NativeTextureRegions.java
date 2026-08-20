package dev.cyclesrenderer.nativebridge;

/** Stages texture updates on the initialized native bridge session. */
public final class NativeTextureRegions {
    private NativeTextureRegions() {
    }

    public static void stage(TextureRegionUpdate update) {
        NativeBridgeSession state = NativeBridge.requireState();
        try {
            state.stageTextureRegion(update);
        } catch (Throwable error) {
            NativeBridge.rethrowFatalError(error);
            throw new IllegalStateException(
                    "native texture region stage failed: " + NativeBridge.describe(error), error);
        }
    }
}
