package dev.cyclesrenderer.scene;

import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks the exact image frame selected by Minecraft's atlas animation state. */
public final class LabPbrAnimationFrames {
    private static final ConcurrentHashMap<Identifier, FrameState> CURRENT =
            new ConcurrentHashMap<>();

    private LabPbrAnimationFrames() {
    }

    public static void record(SpriteContents contents, int imageFrame) {
        if (imageFrame < 0) {
            return;
        }
        CURRENT.put(contents.name(), new FrameState(contents, imageFrame));
    }

    public static int currentImageFrame(TextureAtlasSprite sprite) {
        SpriteContents contents = sprite.contents();
        FrameState state = CURRENT.get(contents.name());
        if (state == null) {
            return 0;
        }
        SpriteContents recordedContents = state.contents().get();
        if (recordedContents != contents) {
            CURRENT.remove(contents.name(), state);
            return 0;
        }
        return state.imageFrame();
    }

    private record FrameState(WeakReference<SpriteContents> contents, int imageFrame) {
        private FrameState(SpriteContents contents, int imageFrame) {
            this(new WeakReference<>(contents), imageFrame);
        }
    }
}
