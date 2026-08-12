package dev.cyclesrenderer.scene;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

public final class LabPbrResources {
    private static final Identifier FORMAT_DECLARATION =
            Identifier.withDefaultNamespace("optifine/texture.properties");
    private static final String LAB_PBR_1_3 = "lab-pbr/1.3";

    private LabPbrResources() {
    }

    public static Discovery discover(
            ResourceManager resourceManager,
            Collection<TextureAtlasSprite> atlasSprites,
            boolean forceLabPbr13) {
        FormatDeclaration declaration = readFormatDeclaration(resourceManager);
        Format effectiveFormat = forceLabPbr13
                ? Format.LAB_PBR_1_3
                : declaration.format();
        Map<Identifier, CompanionSet> companions = new LinkedHashMap<>();
        int discoveryErrors = declaration.error().isEmpty() ? 0 : 1;
        int normalCount = 0;
        int specularCount = 0;

        for (TextureAtlasSprite sprite : atlasSprites) {
            Identifier spriteId = sprite.contents().name();
            if (companions.containsKey(spriteId)) {
                continue;
            }
            CompanionResource normal = null;
            CompanionResource specular = null;
            if (effectiveFormat == Format.LAB_PBR_1_3) {
                try {
                    normal = findCompanion(resourceManager, spriteId, "_n").orElse(null);
                    specular = findCompanion(resourceManager, spriteId, "_s").orElse(null);
                } catch (RuntimeException error) {
                    discoveryErrors++;
                }
            }
            if (normal != null) {
                normalCount++;
            }
            if (specular != null) {
                specularCount++;
            }
            companions.put(spriteId, new CompanionSet(normal, specular));
        }

        return new Discovery(
                effectiveFormat,
                declaration.rawFormat(),
                declaration.sourcePackId(),
                declaration.error(),
                Map.copyOf(companions),
                normalCount,
                specularCount,
                discoveryErrors);
    }

    public static Discovery empty() {
        return new Discovery(
                Format.NONE,
                "",
                "",
                "",
                Map.of(),
                0,
                0,
                0);
    }

    private static FormatDeclaration readFormatDeclaration(ResourceManager resourceManager) {
        Optional<Resource> resource = resourceManager.getResource(FORMAT_DECLARATION);
        if (resource.isEmpty()) {
            return new FormatDeclaration(Format.NONE, "", "", "");
        }

        Resource declaration = resource.orElseThrow();
        Properties properties = new Properties();
        try (InputStream input = declaration.open()) {
            properties.load(input);
        } catch (IOException error) {
            return new FormatDeclaration(
                    Format.UNSUPPORTED,
                    "",
                    declaration.sourcePackId(),
                    error.getClass().getSimpleName() + ": " + error.getMessage());
        }

        String rawFormat = properties.getProperty("format", "").trim();
        Format format = LAB_PBR_1_3.equalsIgnoreCase(rawFormat)
                ? Format.LAB_PBR_1_3
                : Format.UNSUPPORTED;
        return new FormatDeclaration(format, rawFormat, declaration.sourcePackId(), "");
    }

    private static Optional<CompanionResource> findCompanion(
            ResourceManager resourceManager,
            Identifier spriteId,
            String suffix) {
        Identifier companionId = Identifier.fromNamespaceAndPath(
                spriteId.getNamespace(),
                "textures/" + spriteId.getPath() + suffix + ".png");
        return resourceManager.getResource(companionId)
                .map(resource -> new CompanionResource(companionId, resource.sourcePackId()));
    }

    public enum Format {
        NONE,
        LAB_PBR_1_3,
        UNSUPPORTED
    }

    public record CompanionResource(Identifier identifier, String sourcePackId) {
    }

    public record CompanionSet(
            CompanionResource normal,
            CompanionResource specular) {
    }

    public record Discovery(
            Format format,
            String rawFormat,
            String declarationSourcePackId,
            String declarationError,
            Map<Identifier, CompanionSet> companions,
            int normalCount,
            int specularCount,
            int discoveryErrors) {
        public int spriteCount() {
            return companions.size();
        }

        public float normalCoverage() {
            return coverage(normalCount);
        }

        public float specularCoverage() {
            return coverage(specularCount);
        }

        public CompanionSet companions(Identifier spriteId) {
            return companions.get(spriteId);
        }

        private float coverage(int count) {
            return companions.isEmpty() ? 0.0F : (float) count / companions.size();
        }
    }

    private record FormatDeclaration(
            Format format,
            String rawFormat,
            String sourcePackId,
            String error) {
    }
}
