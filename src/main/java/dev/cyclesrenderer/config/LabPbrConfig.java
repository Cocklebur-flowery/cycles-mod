package dev.cyclesrenderer.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/** Owns advanced LabPBR surface controls outside the legacy aggregate config class. */
final class LabPbrConfig {
    private final ModConfigSpec.DoubleValue wetness;
    private final ModConfigSpec.DoubleValue subsurfaceScale;
    private final ModConfigSpec.DoubleValue heightStrength;
    private final ModConfigSpec.DoubleValue heightDistance;
    private final ModConfigSpec.EnumValue<CyclesRenderSettings.HeightMappingMode> heightMappingMode;
    private final ModConfigSpec.IntValue parallaxSteps;

    private LabPbrConfig(
            ModConfigSpec.DoubleValue wetness,
            ModConfigSpec.DoubleValue subsurfaceScale,
            ModConfigSpec.DoubleValue heightStrength,
            ModConfigSpec.DoubleValue heightDistance,
            ModConfigSpec.EnumValue<CyclesRenderSettings.HeightMappingMode> heightMappingMode,
            ModConfigSpec.IntValue parallaxSteps) {
        this.wetness = wetness;
        this.subsurfaceScale = subsurfaceScale;
        this.heightStrength = heightStrength;
        this.heightDistance = heightDistance;
        this.heightMappingMode = heightMappingMode;
        this.parallaxSteps = parallaxSteps;
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
        ModConfigSpec.EnumValue<CyclesRenderSettings.HeightMappingMode> heightMappingMode = builder
                .translation("config.cyclesrenderer.materials.heightMappingMode")
                .comment("Height-map shading method; parallax offsets every LabPBR texture consistently.")
                .defineEnum("heightMappingMode", CyclesRenderSettings.HeightMappingMode.BUMP);
        ModConfigSpec.IntValue parallaxSteps = builder
                .translation("config.cyclesrenderer.materials.parallaxSteps")
                .comment("Fixed ray-march steps used by parallax occlusion mapping.")
                .defineInRange("parallaxSteps", 16, 4, 64);
        return new LabPbrConfig(
                wetness, subsurfaceScale, heightStrength, heightDistance,
                heightMappingMode, parallaxSteps);
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

    CyclesRenderSettings.HeightMappingMode heightMappingMode() {
        return heightMappingMode.get();
    }

    int parallaxSteps() {
        return parallaxSteps.get();
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
        options.add(CyclesClientConfig.enumOption(
                "materials.heightMappingMode", CyclesClientConfig.Category.MATERIALS,
                "config.cyclesrenderer.materials.heightMappingMode", heightMappingMode,
                CyclesRenderSettings.HeightMappingMode.values()));
        options.add(CyclesClientConfig.intOption(
                "materials.parallaxSteps", CyclesClientConfig.Category.MATERIALS,
                "config.cyclesrenderer.materials.parallaxSteps", parallaxSteps,
                4, 64, 1));
    }
}
