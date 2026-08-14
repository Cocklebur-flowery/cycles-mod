package dev.cyclesrenderer.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/** Owns advanced LabPBR surface controls outside the legacy aggregate config class. */
final class LabPbrConfig {
    private final ModConfigSpec.DoubleValue wetness;
    private final ModConfigSpec.DoubleValue subsurfaceScale;
    private final ModConfigSpec.DoubleValue heightStrength;
    private final ModConfigSpec.DoubleValue heightDistance;

    private LabPbrConfig(
            ModConfigSpec.DoubleValue wetness,
            ModConfigSpec.DoubleValue subsurfaceScale,
            ModConfigSpec.DoubleValue heightStrength,
            ModConfigSpec.DoubleValue heightDistance) {
        this.wetness = wetness;
        this.subsurfaceScale = subsurfaceScale;
        this.heightStrength = heightStrength;
        this.heightDistance = heightDistance;
    }

    static LabPbrConfig define(ModConfigSpec.Builder builder) {
        ModConfigSpec.DoubleValue wetness = builder
                .translation("config.cyclesrenderer.materials.wetness")
                .comment("Global wetness applied only to porous LabPBR texels.")
                .defineInRange("wetness", 0.0D, 0.0D, 1.0D);
        ModConfigSpec.DoubleValue subsurfaceScale = builder
                .translation("config.cyclesrenderer.materials.subsurfaceScale")
                .comment("Subsurface scattering distance in Minecraft block units.")
                .defineInRange("subsurfaceScale", 0.005D, 0.0D, 1.0D);
        ModConfigSpec.DoubleValue heightStrength = builder
                .translation("config.cyclesrenderer.materials.heightStrength")
                .comment("Strength of LabPBR height-map bump normals.")
                .defineInRange("heightStrength", 1.0D, 0.0D, 4.0D);
        ModConfigSpec.DoubleValue heightDistance = builder
                .translation("config.cyclesrenderer.materials.heightDistance")
                .comment("Maximum LabPBR bump distance in Minecraft block units.")
                .defineInRange("heightDistance", 0.05D, 0.0D, 1.0D);
        return new LabPbrConfig(wetness, subsurfaceScale, heightStrength, heightDistance);
    }

    float wetness() {
        return wetness.get().floatValue();
    }

    float subsurfaceScale() {
        return subsurfaceScale.get().floatValue();
    }

    float heightStrength() {
        return heightStrength.get().floatValue();
    }

    float heightDistance() {
        return heightDistance.get().floatValue();
    }

    void appendOptions(List<CyclesClientConfig.ConfigOption<?>> options) {
        options.add(CyclesClientConfig.doubleOption(
                "materials.wetness", CyclesClientConfig.Category.MATERIALS,
                "config.cyclesrenderer.materials.wetness", wetness,
                0.0D, 1.0D, 0.01D));
        options.add(CyclesClientConfig.doubleOption(
                "materials.subsurfaceScale", CyclesClientConfig.Category.MATERIALS,
                "config.cyclesrenderer.materials.subsurfaceScale", subsurfaceScale,
                0.0D, 1.0D, 0.001D));
        options.add(CyclesClientConfig.doubleOption(
                "materials.heightStrength", CyclesClientConfig.Category.MATERIALS,
                "config.cyclesrenderer.materials.heightStrength", heightStrength,
                0.0D, 4.0D, 0.05D));
        options.add(CyclesClientConfig.doubleOption(
                "materials.heightDistance", CyclesClientConfig.Category.MATERIALS,
                "config.cyclesrenderer.materials.heightDistance", heightDistance,
                0.0D, 1.0D, 0.005D));
    }
}
