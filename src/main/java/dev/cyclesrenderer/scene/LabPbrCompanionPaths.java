package dev.cyclesrenderer.scene;

import java.util.List;

final class LabPbrCompanionPaths {
    private static final String OPTIFINE_CTM = "optifine/ctm/";
    private static final String MCPATCHER_CTM = "mcpatcher/ctm/";

    private LabPbrCompanionPaths() {
    }

    static List<String> candidates(String spritePath, String suffix) {
        String companionPath = spritePath + suffix + ".png";
        if (spritePath.startsWith(OPTIFINE_CTM)
                || spritePath.startsWith(MCPATCHER_CTM)) {
            return List.of(companionPath, "textures/" + companionPath);
        }
        return List.of("textures/" + companionPath);
    }
}
