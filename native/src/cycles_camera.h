#pragma once

#include "cycles_bridge.h"
#include "scene_update.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <string>
#include <utility>

#include "scene/camera.h"
#include "scene/scene.h"
#include "session/buffers.h"
#include "session/session.h"
#include "util/transform.h"
#include "util/types.h"

#if defined(CYCLESRENDERER_DLSS_EXPERIMENTAL)
namespace ccl {
void request_dlss_history_reset();
}
#endif

namespace cyclesrenderer::camera_adapter {

using SceneRequest = scene::SceneSnapshot;

constexpr std::uint32_t kMaximumRenderWidth = 3840;
constexpr std::uint32_t kMaximumRenderHeight = 2160;
constexpr float kDegreesToRadians = 0.01745329251994329577F;

struct CameraRequest {
    CyclesBridgeCamera camera{};
    std::uint32_t render_width = 0;
    std::uint32_t render_height = 0;
    int sample_count = 1;
    std::uint32_t sampling_state = CYCLES_BRIDGE_SAMPLING_INTERACTIVE;
    bool preserve_pass_cache = false;
    std::uint64_t revision = 0;
};

inline bool finite_camera(const CyclesBridgeCamera& camera) {
    return std::isfinite(camera.position_x)
        && std::isfinite(camera.position_y)
        && std::isfinite(camera.position_z)
        && std::isfinite(camera.rotation_x)
        && std::isfinite(camera.rotation_y)
        && std::isfinite(camera.rotation_z)
        && std::isfinite(camera.rotation_w)
        && std::isfinite(camera.vertical_fov_radians)
        && std::isfinite(camera.depth_far)
        && ((camera.flags & CYCLES_BRIDGE_CAMERA_FOCUS_DISTANCE_VALID) == 0U
            || std::isfinite(camera.focus_distance));
}

inline bool valid_camera(const CyclesBridgeCamera& camera, std::string& error) {
    if (camera.viewport_width == 0 || camera.viewport_height == 0
        || !finite_camera(camera)
        || camera.vertical_fov_radians <= 0.0F
        || camera.vertical_fov_radians >= 3.14159265F
        || camera.depth_far <= 0.0F
        || (camera.flags & ~CYCLES_BRIDGE_CAMERA_FOCUS_DISTANCE_VALID) != 0U
        || ((camera.flags & CYCLES_BRIDGE_CAMERA_FOCUS_DISTANCE_VALID) != 0U
            && (camera.focus_distance < 0.01F
                || camera.focus_distance > 1000000.0F))) {
        error = "invalid camera";
        return false;
    }
    return true;
}

inline std::pair<std::uint32_t, std::uint32_t> render_dimensions(
    std::uint32_t viewport_width,
    std::uint32_t viewport_height,
    const CyclesBridgeRenderSettings& settings,
    std::uint32_t sampling_state) {
    const std::uint32_t percentage_value = settings.dynamic_resolution != 0U
            && sampling_state != CYCLES_BRIDGE_SAMPLING_STILL
        ? std::min(
            settings.resolution_percentage,
            std::clamp(settings.interactive_resolution_percentage, 1U, 100U))
        : settings.resolution_percentage;
    const double percentage = static_cast<double>(percentage_value) / 100.0;
    const std::uint32_t requested_width = std::clamp(
        static_cast<std::uint32_t>(std::floor(settings.render_width * percentage)),
        1U,
        kMaximumRenderWidth);
    const std::uint32_t requested_height = std::clamp(
        static_cast<std::uint32_t>(std::floor(settings.render_height * percentage)),
        1U,
        kMaximumRenderHeight);
    if (settings.resolution_mode == 1U) {
        return {requested_width, requested_height};
    }
    const double scale = std::min({
        1.0,
        static_cast<double>(requested_width) / viewport_width,
        static_cast<double>(requested_height) / viewport_height,
    });
    return {
        std::max(1U, static_cast<std::uint32_t>(std::floor(viewport_width * scale))),
        std::max(1U, static_cast<std::uint32_t>(std::floor(viewport_height * scale))),
    };
}

inline bool nearly_equal(double first, double second, double tolerance) {
    return std::abs(first - second) <= tolerance;
}

inline bool same_camera(
    const CameraRequest& current,
    const CameraRequest& requested,
    bool compare_minecraft_fov,
    bool compare_minecraft_far) {
    const CyclesBridgeCamera& first = current.camera;
    const CyclesBridgeCamera& second = requested.camera;
    return current.render_width == requested.render_width
        && current.render_height == requested.render_height
        && nearly_equal(first.position_x, second.position_x, 1.0e-5)
        && nearly_equal(first.position_y, second.position_y, 1.0e-5)
        && nearly_equal(first.position_z, second.position_z, 1.0e-5)
        && nearly_equal(first.rotation_x, second.rotation_x, 1.0e-6)
        && nearly_equal(first.rotation_y, second.rotation_y, 1.0e-6)
        && nearly_equal(first.rotation_z, second.rotation_z, 1.0e-6)
        && nearly_equal(first.rotation_w, second.rotation_w, 1.0e-6)
        && (!compare_minecraft_fov
            || nearly_equal(first.vertical_fov_radians, second.vertical_fov_radians, 1.0e-6))
        && (!compare_minecraft_far
            || nearly_equal(first.depth_far, second.depth_far, 1.0e-3))
        && first.flags == second.flags
        && ((first.flags & CYCLES_BRIDGE_CAMERA_FOCUS_DISTANCE_VALID) == 0U
            || nearly_equal(first.focus_distance, second.focus_distance, 1.0e-4));
}

inline ccl::Transform camera_transform(
    const CyclesBridgeCamera& camera,
    const CyclesBridgeScene& scene,
    const CyclesBridgeRenderSettings& settings) {
    double qx = camera.rotation_x;
    double qy = camera.rotation_y;
    double qz = camera.rotation_z;
    double qw = camera.rotation_w;
    const double length = std::sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
    if (length <= 1.0e-12 || !std::isfinite(length)) {
        qx = qy = qz = 0.0;
        qw = 1.0;
    } else {
        qx /= length;
        qy /= length;
        qz /= length;
        qw /= length;
    }

    const float xx = static_cast<float>(qx * qx);
    const float yy = static_cast<float>(qy * qy);
    const float zz = static_cast<float>(qz * qz);
    const float xy = static_cast<float>(qx * qy);
    const float xz = static_cast<float>(qx * qz);
    const float yz = static_cast<float>(qy * qz);
    const float xw = static_cast<float>(qx * qw);
    const float yw = static_cast<float>(qy * qw);
    const float zw = static_cast<float>(qz * qw);
    const float px = static_cast<float>(camera.position_x - scene.origin_x);
    const float py = static_cast<float>(camera.position_y - scene.origin_y);
    const float pz = static_cast<float>(camera.position_z - scene.origin_z);

    const ccl::Transform minecraft_transform = {
        ccl::make_float4(1.0F - 2.0F * (yy + zz), 2.0F * (xy - zw), 2.0F * (xz + yw), px),
        ccl::make_float4(2.0F * (xy + zw), 1.0F - 2.0F * (xx + zz), 2.0F * (yz - xw), py),
        ccl::make_float4(2.0F * (xz - yw), 2.0F * (yz + xw), 1.0F - 2.0F * (xx + yy), pz),
    };

    // Minecraft and Blender cameras both point down local -Z. Match Blender's
    // own Cycles adapter so every panorama subtype has the same visual heading
    // as its Blender counterpart (mirror ball has a distinct convention).
    if (settings.camera_type == CYCLES_BRIDGE_CAMERA_PANORAMA) {
        if (settings.panorama_type == CYCLES_BRIDGE_PANORAMA_MIRRORBALL) {
            return ccl::transform_clear_scale(
                minecraft_transform
                * ccl::make_transform(
                    1.0F, 0.0F, 0.0F, 0.0F,
                    0.0F, 0.0F, 1.0F, 0.0F,
                    0.0F, 1.0F, 0.0F, 0.0F));
        }
        return ccl::transform_clear_scale(
            minecraft_transform
            * ccl::make_transform(
                0.0F, -1.0F, 0.0F, 0.0F,
                0.0F, 0.0F, 1.0F, 0.0F,
                -1.0F, 0.0F, 0.0F, 0.0F));
    }
    return ccl::transform_clear_scale(
        minecraft_transform * ccl::transform_scale(1.0F, 1.0F, -1.0F));
}

inline ccl::BufferParams configure_camera(
    ccl::Session& session,
    const SceneRequest& scene_request,
    const CameraRequest& camera_request,
    const CyclesBridgeRenderSettings& settings) {
    ccl::Camera* camera = session.scene->camera;
    const bool panorama = settings.camera_type == CYCLES_BRIDGE_CAMERA_PANORAMA;
    camera->set_camera_type(panorama ? ccl::CAMERA_PANORAMA : ccl::CAMERA_PERSPECTIVE);
    camera->set_panorama_type(static_cast<ccl::PanoramaType>(settings.panorama_type));
    camera->set_full_width(static_cast<int>(camera_request.render_width));
    camera->set_full_height(static_cast<int>(camera_request.render_height));
    const float aspect = static_cast<float>(camera_request.render_width)
        / static_cast<float>(std::max(1U, camera_request.render_height));
    const float vertical_fov = settings.projection_mode
            == CYCLES_BRIDGE_PROJECTION_PHYSICAL_LENS
        ? 2.0F * std::atan(
            settings.sensor_width_mm / (2.0F * settings.focal_length_mm * aspect))
        : camera_request.camera.vertical_fov_radians;
    camera->set_fov(vertical_fov);
    camera->set_fisheye_fov(settings.fisheye_fov_degrees * kDegreesToRadians);
    camera->set_fisheye_lens(settings.fisheye_lens_mm);
    camera->set_latitude_min(settings.latitude_min_degrees * kDegreesToRadians);
    camera->set_latitude_max(settings.latitude_max_degrees * kDegreesToRadians);
    camera->set_longitude_min(settings.longitude_min_degrees * kDegreesToRadians);
    camera->set_longitude_max(settings.longitude_max_degrees * kDegreesToRadians);
    camera->set_fisheye_polynomial_k0(settings.fisheye_polynomial_k0);
    camera->set_fisheye_polynomial_k1(settings.fisheye_polynomial_k1);
    camera->set_fisheye_polynomial_k2(settings.fisheye_polynomial_k2);
    camera->set_fisheye_polynomial_k3(settings.fisheye_polynomial_k3);
    camera->set_fisheye_polynomial_k4(settings.fisheye_polynomial_k4);
    camera->set_central_cylindrical_range_u_min(
        settings.central_cylindrical_longitude_min_degrees * kDegreesToRadians);
    camera->set_central_cylindrical_range_u_max(
        settings.central_cylindrical_longitude_max_degrees * kDegreesToRadians);
    camera->set_central_cylindrical_range_v_min(
        settings.central_cylindrical_height_min / settings.central_cylindrical_radius);
    camera->set_central_cylindrical_range_v_max(
        settings.central_cylindrical_height_max / settings.central_cylindrical_radius);
    camera->set_sensorwidth(settings.sensor_width_mm);
    camera->set_sensorheight(settings.sensor_width_mm / aspect);
    const float near_clip = settings.camera_clip_near;
    const float requested_far_clip = settings.camera_clip_far > 0.0F
        ? settings.camera_clip_far
        : camera_request.camera.depth_far;
    camera->set_nearclip(near_clip);
    camera->set_farclip(std::max(near_clip + 0.001F, requested_far_clip));
    const bool physical_depth_of_field = settings.depth_of_field != 0U
        && settings.depth_of_field_mode
            == CYCLES_BRIDGE_DEPTH_OF_FIELD_PHYSICAL;
    const float aperture_size = physical_depth_of_field
        ? (settings.focal_length_mm / 1000.0F) / (2.0F * settings.f_stop)
        : 0.0F;
    const float focus_distance =
        (camera_request.camera.flags & CYCLES_BRIDGE_CAMERA_FOCUS_DISTANCE_VALID) != 0U
        ? camera_request.camera.focus_distance
        : settings.focus_distance;
#if defined(CYCLESRENDERER_DLSS_EXPERIMENTAL)
    if (!nearly_equal(camera->get_focaldistance(), focus_distance, 1.0e-5)) {
        ccl::request_dlss_history_reset();
    }
#endif
    camera->set_focaldistance(focus_distance);
    camera->set_aperturesize(aperture_size);
    camera->set_blades(settings.aperture_blades);
    camera->set_bladesrotation(
        settings.aperture_rotation_degrees * 3.14159265358979323846F / 180.0F);
    camera->set_aperture_ratio(settings.aperture_ratio);
    const CyclesBridgeSceneResources& resources = scene_request.resources->resources;
    CyclesBridgeScene scene{};
    scene.origin_x = resources.origin_x;
    scene.origin_y = resources.origin_y;
    scene.origin_z = resources.origin_z;
    const ccl::Transform transform = camera_transform(
        camera_request.camera, scene, settings);
    camera->set_matrix(transform);
    ccl::array<ccl::Transform> motion = camera->get_motion();
    motion.resize(2, transform);
    motion[1] = transform;
    camera->set_motion(motion);
    camera->set_use_perspective_motion(false);
    camera->compute_auto_viewplane();
    if (panorama) {
        camera->set_viewplane_left(settings.camera_shift_x);
        camera->set_viewplane_right(1.0F + settings.camera_shift_x);
        camera->set_viewplane_bottom(settings.camera_shift_y);
        camera->set_viewplane_top(1.0F + settings.camera_shift_y);
    } else {
        const float fit_aspect = std::max(aspect, 1.0F / aspect);
        const float radius_x = aspect >= 1.0F ? aspect : 1.0F;
        const float radius_y = aspect >= 1.0F ? 1.0F : 1.0F / aspect;
        const float offset_x = 2.0F * fit_aspect * settings.camera_shift_x;
        const float offset_y = 2.0F * fit_aspect * settings.camera_shift_y;
        camera->set_viewplane_left(-radius_x + offset_x);
        camera->set_viewplane_right(radius_x + offset_x);
        camera->set_viewplane_bottom(-radius_y + offset_y);
        camera->set_viewplane_top(radius_y + offset_y);
    }
    camera->need_flags_update = true;
    camera->need_device_update = true;

    ccl::BufferParams buffer;
    buffer.width = static_cast<int>(camera_request.render_width);
    buffer.height = static_cast<int>(camera_request.render_height);
    buffer.full_width = buffer.width;
    buffer.full_height = buffer.height;
    return buffer;
}

}  // namespace cyclesrenderer::camera_adapter
