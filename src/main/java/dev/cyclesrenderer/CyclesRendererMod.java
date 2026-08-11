package dev.cyclesrenderer;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.cyclesrenderer.nativebridge.NativeBridge;
import dev.cyclesrenderer.scene.ClientRenderSnapshot;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

@Mod(value = CyclesRendererMod.MOD_ID, dist = Dist.CLIENT)
public final class CyclesRendererMod {
    public static final String MOD_ID = "cyclesrenderer";

    private static final Logger LOGGER = LoggerFactory.getLogger(CyclesRendererMod.class);
    private static final KeyMapping.Category KEY_CATEGORY = new KeyMapping.Category(
            Identifier.fromNamespaceAndPath(MOD_ID, "main"));
    private static final KeyMapping TOGGLE_TEST_FRAME = new KeyMapping(
            "key.cyclesrenderer.toggle_test_frame",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_F8,
            KEY_CATEGORY);

    private static boolean testFrameEnabled;
    private static long nativeFrameId;
    private static ClientRenderSnapshot renderSnapshot;
    private static ClientLevel snapshotLevel;
    private static volatile long resourceRevision;

    public CyclesRendererMod(IEventBus modEventBus) {
        modEventBus.addListener(CyclesRendererMod::registerKeyMappings);
        modEventBus.addListener(CyclesRendererMod::addClientReloadListeners);
        NeoForge.EVENT_BUS.addListener(CyclesRendererMod::onClientTick);
        NeoForge.EVENT_BUS.addListener(CyclesRendererMod::onRenderLevelAfterLevel);
        NeoForge.EVENT_BUS.addListener(CyclesRendererMod::onRenderGui);
    }

    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.registerCategory(KEY_CATEGORY);
        event.register(TOGGLE_TEST_FRAME);
    }

    private static void addClientReloadListeners(AddClientReloadListenersEvent event) {
        event.addListener(
                Identifier.fromNamespaceAndPath(MOD_ID, "scene_resources"),
                (ResourceManagerReloadListener) resourceManager -> resourceRevision++);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        while (TOGGLE_TEST_FRAME.consumeClick()) {
            if (testFrameEnabled) {
                disableExperimentalRenderer();
                continue;
            }

            NativeBridge.ProbeResult probe = NativeBridge.probe();
            if (!probe.success()) {
                LOGGER.error("Native renderer bridge probe failed: {}", probe.message());
                continue;
            }

            LOGGER.info("Native renderer bridge ready: {}", probe.message());
            nativeFrameId = 0L;
            renderSnapshot = null;
            snapshotLevel = null;
            testFrameEnabled = true;
        }
    }

    public static boolean isExperimentalRendererEnabled() {
        return testFrameEnabled;
    }

    private static void onRenderLevelAfterLevel(RenderLevelStageEvent.AfterLevel event) {
        if (!testFrameEnabled) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        CameraRenderState camera = event.getLevelRenderState().cameraRenderState;
        if (level == null || !camera.initialized) {
            return;
        }

        var mainTarget = minecraft.gameRenderer.mainRenderTarget();
        try {
            refreshVoxelSceneIfNeeded(level, camera);
            long frameId = nativeFrameId++;
            ByteBuffer pixels = NativeBridge.renderFrame(
                    mainTarget.width,
                    mainTarget.height,
                    frameId,
                    createCameraInput(camera));
            RenderSystem.getDevice()
                    .createCommandEncoder()
                    .writeToTexture(
                            mainTarget.getColorTexture(),
                            pixels,
                            0,
                            0,
                            0,
                            0,
                            mainTarget.width,
                            mainTarget.height);
            if (frameId == 0L) {
                LOGGER.info("Native RGBA frame upload active: {}x{}", mainTarget.width, mainTarget.height);
            }
        } catch (RuntimeException error) {
            LOGGER.error("Native frame rendering failed; restoring the vanilla renderer", error);
            disableExperimentalRenderer();
        }
    }

    private static void refreshVoxelSceneIfNeeded(
            ClientLevel level,
            CameraRenderState camera) {
        long currentResourceRevision = resourceRevision;
        if (snapshotLevel == level
                && renderSnapshot != null
                && renderSnapshot.isCurrentFor(camera.pos, currentResourceRevision)) {
            return;
        }

        long captureStart = System.nanoTime();
        ClientRenderSnapshot capturedSnapshot = ClientRenderSnapshot.capture(
                level, camera.pos, currentResourceRevision);
        NativeBridge.uploadScene(capturedSnapshot);
        renderSnapshot = capturedSnapshot;
        snapshotLevel = level;
        long captureMilliseconds = (System.nanoTime() - captureStart) / 1_000_000L;
        LOGGER.info(
                "Uploaded model scene: origin=({}, {}, {}), vertices={}, triangles={}, "
                        + "materials={}, textures={}, quads={}, skippedTranslucent={}, "
                        + "unsupportedTints={}, skippedModelBlocks={}, capture={} ms",
                capturedSnapshot.originX(),
                capturedSnapshot.originY(),
                capturedSnapshot.originZ(),
                capturedSnapshot.vertexCount(),
                capturedSnapshot.triangleCount(),
                capturedSnapshot.materials().length,
                capturedSnapshot.textures().length,
                capturedSnapshot.quadCount(),
                capturedSnapshot.skippedTranslucentQuadCount(),
                capturedSnapshot.unsupportedTintQuadCount(),
                capturedSnapshot.skippedModelBlockCount(),
                captureMilliseconds);
    }

    private static NativeBridge.CameraInput createCameraInput(CameraRenderState camera) {
        float projectionScaleY = camera.projectionMatrix.m11();
        if (!Float.isFinite(projectionScaleY) || projectionScaleY <= 0.0F) {
            throw new IllegalStateException("invalid camera projection scale: " + projectionScaleY);
        }
        float verticalFovRadians = 2.0F * (float) Math.atan(1.0F / projectionScaleY);
        return new NativeBridge.CameraInput(
                camera.pos.x,
                camera.pos.y,
                camera.pos.z,
                camera.orientation.x(),
                camera.orientation.y(),
                camera.orientation.z(),
                camera.orientation.w(),
                verticalFovRadians,
                Math.max(camera.depthFar, 1.0F));
    }

    private static void disableExperimentalRenderer() {
        testFrameEnabled = false;
        renderSnapshot = null;
        snapshotLevel = null;
        NativeBridge.close();
    }

    private static void onRenderGui(RenderGuiEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!testFrameEnabled || minecraft.level == null) {
            return;
        }

        GuiGraphicsExtractor graphics = event.getGuiGraphics();
        graphics.centeredText(
                minecraft.font,
                Component.translatable("message.cyclesrenderer.test_frame"),
                graphics.guiWidth() / 2,
                12,
                0xFFFFFFFF);
    }
}
