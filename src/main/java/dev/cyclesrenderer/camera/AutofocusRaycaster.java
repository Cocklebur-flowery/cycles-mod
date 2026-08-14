package dev.cyclesrenderer.camera;

import dev.cyclesrenderer.config.CameraAutomationSettings;
import dev.cyclesrenderer.config.CyclesRenderSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/** Produces projection-aware focus samples from the same block scene visible to Cycles. */
public final class AutofocusRaycaster {
    public List<AutofocusController.FocusSample> sample(
            Minecraft minecraft,
            ClientLevel level,
            CameraRenderState camera,
            CyclesRenderSettings settings,
            float verticalFovRadians,
            float aspect) {
        CameraAutomationSettings.Autofocus autofocus =
                settings.cameraAutomation().autofocus();
        List<AutofocusSampling.ScreenSample> screenSamples = AutofocusSampling.samplePattern(
                autofocus.target() == CameraAutomationSettings.FocusTarget.AREA,
                autofocus.areaRadius());
        List<AutofocusController.FocusSample> focusSamples =
                new ArrayList<>(screenSamples.size());
        CollisionContext collision = collisionContext(minecraft.getCameraEntity());
        ClipContext.Fluid fluid = autofocus.includeFluids()
                ? ClipContext.Fluid.ANY
                : ClipContext.Fluid.NONE;
        Vec3 origin = camera.pos;

        for (AutofocusSampling.ScreenSample sample : screenSamples) {
            CameraProjection.direction(
                            settings,
                            verticalFovRadians,
                            aspect,
                            sample.u(),
                            sample.v())
                    .ifPresent(localDirection -> addFocusSample(
                            level,
                            camera,
                            settings.cameraType(),
                            autofocus.maximumDistance(),
                            origin,
                            collision,
                            fluid,
                            localDirection,
                            sample,
                            focusSamples));
        }
        return List.copyOf(focusSamples);
    }

    private static void addFocusSample(
            ClientLevel level,
            CameraRenderState camera,
            CyclesRenderSettings.CameraType cameraType,
            float maximumDistance,
            Vec3 origin,
            CollisionContext collision,
            ClipContext.Fluid fluid,
            CameraProjection.Direction localDirection,
            AutofocusSampling.ScreenSample screenSample,
            List<AutofocusController.FocusSample> output) {
        Vector3d worldDirection = new Vector3d(
                localDirection.x(), localDirection.y(), localDirection.z());
        camera.orientation.transform(worldDirection);
        Vec3 end = origin.add(
                worldDirection.x * maximumDistance,
                worldDirection.y * maximumDistance,
                worldDirection.z * maximumDistance);
        HitResult hit = level.clip(new ClipContext(
                origin,
                end,
                ClipContext.Block.OUTLINE,
                fluid,
                collision));
        if (hit.getType() == HitResult.Type.MISS) {
            return;
        }
        float radialDistance = (float) origin.distanceTo(hit.getLocation());
        float distance = AutofocusSampling.focusDistance(
                cameraType == CyclesRenderSettings.CameraType.PANORAMA,
                localDirection.z(),
                radialDistance);
        if (Float.isFinite(distance) && distance > 0.0F) {
            output.add(new AutofocusController.FocusSample(
                    distance, screenSample.weight(), screenSample.primary()));
        }
    }

    private static CollisionContext collisionContext(Entity entity) {
        return entity == null ? CollisionContext.empty() : CollisionContext.of(entity);
    }
}
