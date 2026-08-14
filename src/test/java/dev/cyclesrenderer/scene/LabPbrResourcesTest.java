package dev.cyclesrenderer.scene;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class LabPbrResourcesTest {
    @Test
    void resolvesStandardSpriteCompanionsBelowTextures() {
        assertEquals(
                List.of("textures/block/stone_n.png"),
                LabPbrCompanionPaths.candidates("block/stone", "_n"));
    }

    @Test
    void resolvesOptifineCtmCompanionsFromResourceRootFirst() {
        assertEquals(
                List.of(
                        "optifine/ctm/default/glass/0_s.png",
                        "textures/optifine/ctm/default/glass/0_s.png"),
                LabPbrCompanionPaths.candidates(
                        "optifine/ctm/default/glass/0", "_s"));
    }

    @Test
    void resolvesLegacyMcpatcherCtmCompanionsFromResourceRootFirst() {
        assertEquals(
                List.of(
                        "mcpatcher/ctm/stone/2_n.png",
                        "textures/mcpatcher/ctm/stone/2_n.png"),
                LabPbrCompanionPaths.candidates("mcpatcher/ctm/stone/2", "_n"));
    }
}
