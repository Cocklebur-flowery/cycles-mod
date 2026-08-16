package dev.cyclesrenderer;

import com.mojang.blaze3d.platform.InputConstants;
import dev.cyclesrenderer.client.CyclesSettingsScreen;
import dev.cyclesrenderer.config.CyclesClientConfig;
import dev.cyclesrenderer.render.CyclesRenderPipelines;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.FlipFrameEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
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
    private static final KeyMapping OPEN_SETTINGS = new KeyMapping(
            "key.cyclesrenderer.open_settings",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_F9,
            KEY_CATEGORY);
    private static final KeyMapping TOGGLE_DEBUG = new KeyMapping(
            "key.cyclesrenderer.toggle_debug",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_F10,
            KEY_CATEGORY);
    private static final CyclesRendererController CONTROLLER =
            new CyclesRendererController(LOGGER);

    private static ModContainer modContainer;

    public CyclesRendererMod(IEventBus modEventBus, ModContainer container) {
        modContainer = container;
        container.registerConfig(ModConfig.Type.CLIENT, CyclesClientConfig.SPEC);
        container.registerExtensionPoint(
                IConfigScreenFactory.class,
                (registeredContainer, parent) ->
                        new CyclesSettingsScreen(registeredContainer, parent));
        modEventBus.addListener(CyclesRendererMod::registerKeyMappings);
        modEventBus.addListener(CyclesRenderPipelines::register);
        modEventBus.addListener(CyclesRendererMod::addClientReloadListeners);
        modEventBus.addListener(CyclesRendererMod::onConfigLoading);
        modEventBus.addListener(CyclesRendererMod::onConfigReloading);
        NeoForge.EVENT_BUS.addListener(
                EventPriority.HIGHEST, CyclesRendererMod::onClientTickPre);
        NeoForge.EVENT_BUS.addListener(
                EventPriority.LOWEST, CyclesRendererMod::onClientTick);
        NeoForge.EVENT_BUS.addListener(
                EventPriority.HIGHEST, CyclesRendererMod::onRenderFramePre);
        NeoForge.EVENT_BUS.addListener(
                EventPriority.LOWEST, CyclesRendererMod::onRenderFramePost);
        NeoForge.EVENT_BUS.addListener(
                EventPriority.LOWEST, CyclesRendererMod::onFlipFrame);
        NeoForge.EVENT_BUS.addListener(CyclesRendererMod::onRenderLevelAfterLevel);
        NeoForge.EVENT_BUS.addListener(CyclesRendererMod::onRenderGui);
        NeoForge.EVENT_BUS.addListener(CyclesRendererMod::onChunkUnload);
        NeoForge.EVENT_BUS.addListener(CyclesRendererMod::onGameShuttingDown);
    }

    public static boolean ensureNativeBridgeReady() {
        return CONTROLLER.ensureNativeBridgeReady();
    }

    public static boolean isExperimentalRendererEnabled() {
        return CONTROLLER.isExperimentalRendererEnabled();
    }

    public static boolean shouldReplaceVanillaWorld() {
        return CONTROLLER.shouldReplaceVanillaWorld();
    }

    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.registerCategory(KEY_CATEGORY);
        event.register(TOGGLE_TEST_FRAME);
        event.register(OPEN_SETTINGS);
        event.register(TOGGLE_DEBUG);
    }

    private static void addClientReloadListeners(AddClientReloadListenersEvent event) {
        event.addListener(
                Identifier.fromNamespaceAndPath(MOD_ID, "scene_resources"),
                (ResourceManagerReloadListener) resourceManager ->
                        CONTROLLER.onResourcesReloaded());
    }

    private static void onConfigLoading(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == CyclesClientConfig.SPEC) {
            CyclesClientConfig.markReloaded();
        }
    }

    private static void onConfigReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == CyclesClientConfig.SPEC) {
            CyclesClientConfig.markReloaded();
        }
    }

    private static void onClientTickPre(ClientTickEvent.Pre event) {
        CONTROLLER.onClientTickPre();
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        CONTROLLER.onClientTickPost();
        while (OPEN_SETTINGS.consumeClick()) {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.gui.setScreen(new CyclesSettingsScreen(
                    modContainer,
                    minecraft.gui.screen()));
        }
        while (TOGGLE_DEBUG.consumeClick()) {
            CyclesClientConfig.setDebugOverlay(
                    !CyclesClientConfig.snapshot().debugOverlay());
        }
        CONTROLLER.handleRendererToggle(TOGGLE_TEST_FRAME);
    }

    private static void onRenderFramePre(RenderFrameEvent.Pre event) {
        CONTROLLER.onRenderFramePre(event);
    }

    private static void onRenderFramePost(RenderFrameEvent.Post event) {
        CONTROLLER.onRenderFramePost(event);
    }

    private static void onFlipFrame(FlipFrameEvent event) {
        CONTROLLER.onFlipFrame(event);
    }

    private static void onRenderLevelAfterLevel(RenderLevelStageEvent.AfterLevel event) {
        CONTROLLER.onRenderLevelAfterLevel(event);
    }

    private static void onRenderGui(RenderGuiEvent.Pre event) {
        CONTROLLER.onRenderGui(event);
    }

    private static void onChunkUnload(ChunkEvent.Unload event) {
        CONTROLLER.onChunkUnload(event);
    }

    private static void onGameShuttingDown(GameShuttingDownEvent event) {
        CONTROLLER.onGameShuttingDown();
    }
}
