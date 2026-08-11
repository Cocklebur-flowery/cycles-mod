package dev.cyclesrenderer;

import com.mojang.blaze3d.platform.InputConstants;
import dev.cyclesrenderer.nativebridge.NativeBridge;
import dev.cyclesrenderer.render.CyclesFramePresenter;
import dev.cyclesrenderer.scene.DistantHorizonsSceneProvider;
import dev.cyclesrenderer.scene.SectionGeometryCollector;
import dev.cyclesrenderer.scene.SectionSceneManager;
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
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static boolean nativeBridgeReady;
    private static long nativeFrameId;
    private static long lastStatsLogNanos;
    private static volatile long resourceRevision;
    private static final SectionSceneManager SCENE_MANAGER = new SectionSceneManager();
    private static final CyclesFramePresenter FRAME_PRESENTER = new CyclesFramePresenter();

    public CyclesRendererMod(IEventBus modEventBus) {
        modEventBus.addListener(CyclesRendererMod::registerKeyMappings);
        modEventBus.addListener(CyclesRendererMod::addClientReloadListeners);
        NeoForge.EVENT_BUS.addListener(CyclesRendererMod::onClientTick);
        NeoForge.EVENT_BUS.addListener(CyclesRendererMod::onRenderLevelAfterLevel);
        NeoForge.EVENT_BUS.addListener(CyclesRendererMod::onRenderGui);
        NeoForge.EVENT_BUS.addListener(CyclesRendererMod::onChunkUnload);
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

            if (!nativeBridgeReady) {
                NativeBridge.ProbeResult probe = NativeBridge.probe();
                if (!probe.success()) {
                    LOGGER.error("Native renderer bridge probe failed: {}", probe.message());
                    continue;
                }
                nativeBridgeReady = true;
                LOGGER.info("Native renderer bridge ready: {}", probe.message());
            }

            nativeFrameId = 0L;
            lastStatsLogNanos = 0L;
            SCENE_MANAGER.reset();
            FRAME_PRESENTER.reset();
            SectionGeometryCollector.setEnabled(true);
            testFrameEnabled = true;
            LOGGER.info("Experimental renderer enabled; building the streamed Cycles scene");
        }
    }

    public static boolean isExperimentalRendererEnabled() {
        return testFrameEnabled;
    }

    public static boolean shouldReplaceVanillaWorld() {
        return testFrameEnabled && FRAME_PRESENTER.hasFrame();
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
            SectionSceneManager.UpdateResult update = SCENE_MANAGER.update(
                    minecraft,
                    level,
                    camera.pos,
                    resourceRevision);
            if (update.reset()) {
                FRAME_PRESENTER.reset();
            }
            long frameId = nativeFrameId++;
            NativeBridge.RenderedFrame frame = NativeBridge.renderFrame(
                    mainTarget.width,
                    mainTarget.height,
                    frameId,
                    createCameraInput(camera));
            FRAME_PRESENTER.update(frame);
            FRAME_PRESENTER.present(mainTarget);

            long now = System.nanoTime();
            if ((update.reset() || update.committed())
                    && now - lastStatsLogNanos >= 2_000_000_000L) {
                lastStatsLogNanos = now;
                LOGGER.info(
                        "Cycles streamed scene: sections={}, vertices={}, triangles={}, "
                                + "viewDistance={}, accepted={}, uploaded={}, removed={}, "
                                + "frame={}x{}@{} sample(s); {}",
                        update.activeSections(),
                        update.vertices(),
                        update.triangles(),
                        update.viewDistance(),
                        update.acceptedSections(),
                        update.uploadedSections(),
                        update.removedSections(),
                        frame.width(),
                        frame.height(),
                        frame.sampleCount(),
                        NativeBridge.rendererInfo());
            }
        } catch (RuntimeException error) {
            LOGGER.error("Native frame rendering failed; restoring the vanilla renderer", error);
            disableExperimentalRenderer();
            NativeBridge.close();
            nativeBridgeReady = false;
        }
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
        SectionGeometryCollector.setEnabled(false);
        SCENE_MANAGER.reset();
        FRAME_PRESENTER.reset();
        DistantHorizonsSceneProvider.reset();
        LOGGER.info("Experimental renderer suspended; native bridge kept warm");
    }

    private static void onChunkUnload(ChunkEvent.Unload event) {
        if (testFrameEnabled && event.getLevel() instanceof ClientLevel level) {
            SCENE_MANAGER.onChunkUnload(level, event.getChunk().getPos());
        }
    }

    private static void onRenderGui(RenderGuiEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!testFrameEnabled || minecraft.level == null) {
            return;
        }

        GuiGraphicsExtractor graphics = event.getGuiGraphics();
        Component status = FRAME_PRESENTER.hasFrame()
                ? Component.translatable("message.cyclesrenderer.test_frame")
                : Component.literal("Cycles 正在构建场景 — 按 F8 返回原版");
        graphics.centeredText(
                minecraft.font,
                status,
                graphics.guiWidth() / 2,
                12,
                0xFFFFFFFF);
    }
}
