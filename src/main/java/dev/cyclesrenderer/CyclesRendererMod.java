package dev.cyclesrenderer;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
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
import org.joml.Vector4f;

@Mod(value = CyclesRendererMod.MOD_ID, dist = Dist.CLIENT)
public final class CyclesRendererMod {
    public static final String MOD_ID = "cyclesrenderer";

    private static final Vector4f TEST_FRAME_COLOR = new Vector4f(0.063F, 0.094F, 0.173F, 1.0F);
    private static final KeyMapping.Category KEY_CATEGORY = new KeyMapping.Category(
            Identifier.fromNamespaceAndPath(MOD_ID, "main"));
    private static final KeyMapping TOGGLE_TEST_FRAME = new KeyMapping(
            "key.cyclesrenderer.toggle_test_frame",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_F8,
            KEY_CATEGORY);

    private static boolean testFrameEnabled;

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
            testFrameEnabled = !testFrameEnabled;
        }
    }

    private static void onRenderLevelAfterLevel(RenderLevelStageEvent.AfterLevel event) {
        if (!testFrameEnabled) {
            return;
        }

        var mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        RenderSystem.getDevice()
                .createCommandEncoder()
                .clearColorAndDepthTextures(
                        mainTarget.getColorTexture(),
                        TEST_FRAME_COLOR,
                        mainTarget.getDepthTexture(),
                        0.0);
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
