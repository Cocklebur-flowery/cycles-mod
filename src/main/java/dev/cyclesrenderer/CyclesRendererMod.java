package dev.cyclesrenderer;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.cyclesrenderer.nativebridge.NativeBridge;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
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

    public CyclesRendererMod(IEventBus modEventBus) {
        modEventBus.addListener(CyclesRendererMod::registerKeyMappings);
        NeoForge.EVENT_BUS.addListener(CyclesRendererMod::onClientTick);
        NeoForge.EVENT_BUS.addListener(CyclesRendererMod::onRenderLevelAfterLevel);
        NeoForge.EVENT_BUS.addListener(CyclesRendererMod::onRenderGui);
    }

    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.registerCategory(KEY_CATEGORY);
        event.register(TOGGLE_TEST_FRAME);
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

        var mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        try {
            long frameId = nativeFrameId++;
            ByteBuffer pixels = NativeBridge.renderFrame(
                    mainTarget.width, mainTarget.height, frameId);
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

    private static void disableExperimentalRenderer() {
        testFrameEnabled = false;
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
