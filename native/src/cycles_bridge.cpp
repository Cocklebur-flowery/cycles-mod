#include "cycles_bridge.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstring>
#include <limits>
#include <new>
#include <vector>

struct CyclesBridgeRenderer {
    CyclesBridgeVoxelScene scene{};
    std::vector<std::uint32_t> packed_voxels;
    bool scene_uploaded = false;
};

namespace {

constexpr std::uint32_t kAbiVersion = 2;
constexpr std::uint32_t kStructVersion = 1;
constexpr char kBuildInfo[] = "cyclesrenderer-native/voxel-cpu;abi=2";
constexpr double kDirectionEpsilon = 1.0e-10;
constexpr double kRayStartEpsilon = 1.0e-7;
constexpr std::uint32_t kTargetRenderWidth = 480;
constexpr std::uint32_t kTargetRenderHeight = 270;

static_assert(sizeof(CyclesBridgeCamera) == 80);
static_assert(offsetof(CyclesBridgeCamera, frame_id) == 8);
static_assert(offsetof(CyclesBridgeCamera, position_x) == 24);
static_assert(offsetof(CyclesBridgeCamera, rotation_x) == 48);
static_assert(offsetof(CyclesBridgeCamera, vertical_fov_radians) == 64);
static_assert(sizeof(CyclesBridgeVoxelScene) == 40);
static_assert(offsetof(CyclesBridgeVoxelScene, origin_x) == 8);
static_assert(offsetof(CyclesBridgeVoxelScene, size_x) == 20);

struct Vec3 {
    double x;
    double y;
    double z;
};

struct RayBoxHit {
    bool hit;
    double distance;
    int normal_axis;
    int normal_sign;
};

struct Rgba {
    std::uint8_t red;
    std::uint8_t green;
    std::uint8_t blue;
    std::uint8_t alpha;
};

std::uint8_t to_byte(std::uint64_t value) {
    return static_cast<std::uint8_t>(value & 0xFFU);
}

bool finite_camera(const CyclesBridgeCamera& camera) {
    return std::isfinite(camera.position_x)
        && std::isfinite(camera.position_y)
        && std::isfinite(camera.position_z)
        && std::isfinite(camera.rotation_x)
        && std::isfinite(camera.rotation_y)
        && std::isfinite(camera.rotation_z)
        && std::isfinite(camera.rotation_w)
        && std::isfinite(camera.vertical_fov_radians)
        && std::isfinite(camera.depth_far);
}

bool expected_voxel_count(const CyclesBridgeVoxelScene& scene, std::uint64_t& output_count) {
    if (scene.size_x == 0 || scene.size_y == 0 || scene.size_z == 0) {
        return false;
    }

    const std::uint64_t xy = static_cast<std::uint64_t>(scene.size_x) * scene.size_y;
    if (xy > std::numeric_limits<std::uint64_t>::max() / scene.size_z) {
        return false;
    }
    output_count = xy * scene.size_z;
    return output_count <= std::numeric_limits<std::size_t>::max();
}

Vec3 normalized(Vec3 value) {
    const double length = std::sqrt(value.x * value.x + value.y * value.y + value.z * value.z);
    if (length <= kDirectionEpsilon || !std::isfinite(length)) {
        return {0.0, 0.0, -1.0};
    }
    return {value.x / length, value.y / length, value.z / length};
}

Vec3 rotate_by_quaternion(Vec3 value, const CyclesBridgeCamera& camera) {
    double qx = camera.rotation_x;
    double qy = camera.rotation_y;
    double qz = camera.rotation_z;
    double qw = camera.rotation_w;
    const double quaternion_length = std::sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
    if (quaternion_length <= kDirectionEpsilon || !std::isfinite(quaternion_length)) {
        return value;
    }

    qx /= quaternion_length;
    qy /= quaternion_length;
    qz /= quaternion_length;
    qw /= quaternion_length;

    const double tx = 2.0 * (qy * value.z - qz * value.y);
    const double ty = 2.0 * (qz * value.x - qx * value.z);
    const double tz = 2.0 * (qx * value.y - qy * value.x);
    return {
        value.x + qw * tx + (qy * tz - qz * ty),
        value.y + qw * ty + (qz * tx - qx * tz),
        value.z + qw * tz + (qx * ty - qy * tx),
    };
}

RayBoxHit intersect_scene_bounds(
    const CyclesBridgeVoxelScene& scene,
    const Vec3& origin,
    const Vec3& direction,
    double maximum_distance) {
    const double minimum[3] = {
        static_cast<double>(scene.origin_x),
        static_cast<double>(scene.origin_y),
        static_cast<double>(scene.origin_z),
    };
    const double maximum[3] = {
        minimum[0] + scene.size_x,
        minimum[1] + scene.size_y,
        minimum[2] + scene.size_z,
    };
    const double ray_origin[3] = {origin.x, origin.y, origin.z};
    const double ray_direction[3] = {direction.x, direction.y, direction.z};

    double near_distance = 0.0;
    double far_distance = maximum_distance;
    int near_axis = -1;
    int near_sign = 0;
    for (int axis = 0; axis < 3; ++axis) {
        if (std::abs(ray_direction[axis]) <= kDirectionEpsilon) {
            if (ray_origin[axis] < minimum[axis] || ray_origin[axis] >= maximum[axis]) {
                return {false, 0.0, -1, 0};
            }
            continue;
        }

        double first = (minimum[axis] - ray_origin[axis]) / ray_direction[axis];
        double second = (maximum[axis] - ray_origin[axis]) / ray_direction[axis];
        int entry_sign = -1;
        if (first > second) {
            std::swap(first, second);
            entry_sign = 1;
        }
        if (first > near_distance) {
            near_distance = first;
            near_axis = axis;
            near_sign = entry_sign;
        }
        far_distance = std::min(far_distance, second);
        if (far_distance < near_distance) {
            return {false, 0.0, -1, 0};
        }
    }
    return {far_distance >= 0.0, std::max(near_distance, 0.0), near_axis, near_sign};
}

std::size_t voxel_index(
    const CyclesBridgeVoxelScene& scene,
    std::int32_t world_x,
    std::int32_t world_y,
    std::int32_t world_z) {
    const auto local_x = static_cast<std::uint32_t>(world_x - scene.origin_x);
    const auto local_y = static_cast<std::uint32_t>(world_y - scene.origin_y);
    const auto local_z = static_cast<std::uint32_t>(world_z - scene.origin_z);
    return static_cast<std::size_t>(local_x)
        + static_cast<std::size_t>(scene.size_x)
            * (static_cast<std::size_t>(local_z)
                + static_cast<std::size_t>(scene.size_z) * local_y);
}

bool inside_scene(
    const CyclesBridgeVoxelScene& scene,
    std::int32_t world_x,
    std::int32_t world_y,
    std::int32_t world_z) {
    return world_x >= scene.origin_x
        && world_y >= scene.origin_y
        && world_z >= scene.origin_z
        && static_cast<std::uint64_t>(world_x - scene.origin_x) < scene.size_x
        && static_cast<std::uint64_t>(world_y - scene.origin_y) < scene.size_y
        && static_cast<std::uint64_t>(world_z - scene.origin_z) < scene.size_z;
}

double initial_boundary_distance(double origin, double direction, std::int32_t voxel, int step) {
    if (step == 0) {
        return std::numeric_limits<double>::infinity();
    }
    const double boundary = static_cast<double>(voxel + (step > 0 ? 1 : 0));
    return (boundary - origin) / direction;
}

double face_brightness(int normal_axis, int normal_sign) {
    if (normal_axis == 1) {
        return normal_sign > 0 ? 1.0 : 0.48;
    }
    if (normal_axis == 0) {
        return 0.78;
    }
    return 0.64;
}

Rgba unpack_shaded(std::uint32_t packed, int normal_axis, int normal_sign, double distance) {
    const double brightness = face_brightness(normal_axis, normal_sign);
    const double fog = std::clamp(1.0 - distance / 160.0, 0.55, 1.0);
    const double scale = brightness * fog;
    const auto scale_channel = [scale](std::uint32_t value) {
        return static_cast<std::uint8_t>(std::clamp(value * scale, 0.0, 255.0));
    };
    return {
        scale_channel(packed & 0xFFU),
        scale_channel((packed >> 8U) & 0xFFU),
        scale_channel((packed >> 16U) & 0xFFU),
        0xFFU,
    };
}

Rgba sky_color(const Vec3& direction) {
    const double blend = std::clamp(direction.y * 0.5 + 0.5, 0.0, 1.0);
    const auto interpolate = [blend](double low, double high) {
        return static_cast<std::uint8_t>(low + (high - low) * blend);
    };
    return {
        interpolate(190.0, 78.0),
        interpolate(211.0, 132.0),
        interpolate(232.0, 194.0),
        0xFFU,
    };
}

Rgba trace_voxel_ray(
    const CyclesBridgeRenderer& renderer,
    const Vec3& origin,
    const Vec3& direction,
    double maximum_distance) {
    const CyclesBridgeVoxelScene& scene = renderer.scene;
    const RayBoxHit bounds_hit = intersect_scene_bounds(scene, origin, direction, maximum_distance);
    if (!bounds_hit.hit) {
        return sky_color(direction);
    }

    double distance = bounds_hit.distance + kRayStartEpsilon;
    const Vec3 start = {
        origin.x + direction.x * distance,
        origin.y + direction.y * distance,
        origin.z + direction.z * distance,
    };
    std::int32_t voxel_x = static_cast<std::int32_t>(std::floor(start.x));
    std::int32_t voxel_y = static_cast<std::int32_t>(std::floor(start.y));
    std::int32_t voxel_z = static_cast<std::int32_t>(std::floor(start.z));

    const int step_x = direction.x > kDirectionEpsilon ? 1 : (direction.x < -kDirectionEpsilon ? -1 : 0);
    const int step_y = direction.y > kDirectionEpsilon ? 1 : (direction.y < -kDirectionEpsilon ? -1 : 0);
    const int step_z = direction.z > kDirectionEpsilon ? 1 : (direction.z < -kDirectionEpsilon ? -1 : 0);
    double next_x = initial_boundary_distance(origin.x, direction.x, voxel_x, step_x);
    double next_y = initial_boundary_distance(origin.y, direction.y, voxel_y, step_y);
    double next_z = initial_boundary_distance(origin.z, direction.z, voxel_z, step_z);
    const double delta_x = step_x == 0 ? std::numeric_limits<double>::infinity() : std::abs(1.0 / direction.x);
    const double delta_y = step_y == 0 ? std::numeric_limits<double>::infinity() : std::abs(1.0 / direction.y);
    const double delta_z = step_z == 0 ? std::numeric_limits<double>::infinity() : std::abs(1.0 / direction.z);
    int normal_axis = bounds_hit.normal_axis;
    int normal_sign = bounds_hit.normal_sign;

    while (inside_scene(scene, voxel_x, voxel_y, voxel_z) && distance <= maximum_distance) {
        const std::uint32_t packed = renderer.packed_voxels[
            voxel_index(scene, voxel_x, voxel_y, voxel_z)];
        if ((packed >> 24U) != 0U) {
            return unpack_shaded(packed, normal_axis, normal_sign, distance);
        }

        if (next_x <= next_y && next_x <= next_z) {
            distance = next_x;
            next_x += delta_x;
            voxel_x += step_x;
            normal_axis = 0;
            normal_sign = -step_x;
        } else if (next_y <= next_z) {
            distance = next_y;
            next_y += delta_y;
            voxel_y += step_y;
            normal_axis = 1;
            normal_sign = -step_y;
        } else {
            distance = next_z;
            next_z += delta_z;
            voxel_z += step_z;
            normal_axis = 2;
            normal_sign = -step_z;
        }
    }

    return sky_color(direction);
}

void write_pixel(std::uint8_t* rgba, std::uint32_t width, std::uint32_t x, std::uint32_t y, Rgba color) {
    const std::uint64_t offset = (static_cast<std::uint64_t>(y) * width + x) * 4U;
    rgba[offset] = color.red;
    rgba[offset + 1U] = color.green;
    rgba[offset + 2U] = color.blue;
    rgba[offset + 3U] = color.alpha;
}

}  // namespace

std::uint32_t cycles_bridge_abi_version() {
    return kAbiVersion;
}

std::uint32_t cycles_bridge_write_build_info(char* output, std::uint32_t capacity) {
    if (output == nullptr) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    if (capacity < sizeof(kBuildInfo)) {
        return CYCLES_BRIDGE_STATUS_BUFFER_TOO_SMALL;
    }

    std::memcpy(output, kBuildInfo, sizeof(kBuildInfo));
    return CYCLES_BRIDGE_STATUS_OK;
}

std::uint32_t cycles_bridge_create_renderer(CyclesBridgeRenderer** output_renderer) {
    if (output_renderer == nullptr) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    *output_renderer = new (std::nothrow) CyclesBridgeRenderer();
    return *output_renderer == nullptr
        ? CYCLES_BRIDGE_STATUS_OUT_OF_MEMORY
        : CYCLES_BRIDGE_STATUS_OK;
}

void cycles_bridge_destroy_renderer(CyclesBridgeRenderer* renderer) {
    delete renderer;
}

std::uint32_t cycles_bridge_upload_voxel_scene(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeVoxelScene* scene,
    const std::uint32_t* packed_voxels,
    std::uint64_t voxel_count) {
    if (renderer == nullptr || scene == nullptr || packed_voxels == nullptr
        || scene->struct_size < sizeof(CyclesBridgeVoxelScene)
        || scene->struct_version != kStructVersion) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }

    std::uint64_t expected_count = 0;
    if (!expected_voxel_count(*scene, expected_count) || voxel_count != expected_count) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }

    try {
        renderer->packed_voxels.assign(packed_voxels, packed_voxels + voxel_count);
        renderer->scene = *scene;
        renderer->scene_uploaded = true;
        return CYCLES_BRIDGE_STATUS_OK;
    } catch (const std::bad_alloc&) {
        return CYCLES_BRIDGE_STATUS_OUT_OF_MEMORY;
    }
}

std::uint32_t cycles_bridge_render(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeCamera* camera,
    std::uint8_t* rgba,
    std::uint64_t rgba_capacity) {
    if (renderer == nullptr || camera == nullptr || rgba == nullptr
        || camera->struct_size < sizeof(CyclesBridgeCamera)
        || camera->struct_version != kStructVersion
        || camera->viewport_width == 0 || camera->viewport_height == 0
        || !finite_camera(*camera)
        || camera->vertical_fov_radians <= 0.0F
        || camera->vertical_fov_radians >= 3.14159265F
        || camera->depth_far <= 0.0F) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    if (!renderer->scene_uploaded) {
        return CYCLES_BRIDGE_STATUS_UNINITIALIZED;
    }

    const std::uint64_t pixel_count = static_cast<std::uint64_t>(camera->viewport_width)
        * camera->viewport_height;
    if (pixel_count > std::numeric_limits<std::uint64_t>::max() / 4U) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    const std::uint64_t required_capacity = pixel_count * 4U;
    if (rgba_capacity < required_capacity) {
        return CYCLES_BRIDGE_STATUS_BUFFER_TOO_SMALL;
    }

    const std::uint32_t horizontal_step =
        (camera->viewport_width + kTargetRenderWidth - 1U) / kTargetRenderWidth;
    const std::uint32_t vertical_step =
        (camera->viewport_height + kTargetRenderHeight - 1U) / kTargetRenderHeight;
    const std::uint32_t sample_step = std::max({1U, horizontal_step, vertical_step});
    const double aspect = static_cast<double>(camera->viewport_width) / camera->viewport_height;
    const double tangent_half_fov = std::tan(camera->vertical_fov_radians * 0.5);
    const Vec3 origin = {camera->position_x, camera->position_y, camera->position_z};

    for (std::uint32_t block_y = 0; block_y < camera->viewport_height; block_y += sample_step) {
        const std::uint32_t sample_y = block_y + std::min(
            sample_step / 2U, camera->viewport_height - block_y - 1U);
        const double screen_y = 2.0
            * (static_cast<double>(sample_y) + 0.5) / camera->viewport_height - 1.0;
        for (std::uint32_t block_x = 0; block_x < camera->viewport_width; block_x += sample_step) {
            const std::uint32_t sample_x = block_x + std::min(
                sample_step / 2U, camera->viewport_width - block_x - 1U);
            const double screen_x = 2.0
                * (static_cast<double>(sample_x) + 0.5) / camera->viewport_width - 1.0;
            const Vec3 camera_direction = normalized({
                screen_x * aspect * tangent_half_fov,
                screen_y * tangent_half_fov,
                -1.0,
            });
            const Vec3 world_direction = normalized(rotate_by_quaternion(camera_direction, *camera));
            const Rgba color = trace_voxel_ray(
                *renderer, origin, world_direction, camera->depth_far);

            const std::uint32_t end_y = std::min(block_y + sample_step, camera->viewport_height);
            const std::uint32_t end_x = std::min(block_x + sample_step, camera->viewport_width);
            for (std::uint32_t y = block_y; y < end_y; ++y) {
                for (std::uint32_t x = block_x; x < end_x; ++x) {
                    write_pixel(rgba, camera->viewport_width, x, y, color);
                }
            }
        }
    }
    return CYCLES_BRIDGE_STATUS_OK;
}

std::uint32_t cycles_bridge_fill_test_frame(
    std::uint8_t* rgba,
    std::uint32_t width,
    std::uint32_t height,
    std::uint64_t frame_id) {
    if (rgba == nullptr || width == 0 || height == 0) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }

    constexpr std::uint64_t kChannels = 4;
    if (static_cast<std::uint64_t>(width) * height
        > std::numeric_limits<std::uint64_t>::max() / kChannels) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }

    for (std::uint32_t y = 0; y < height; ++y) {
        for (std::uint32_t x = 0; x < width; ++x) {
            const std::uint64_t offset = (static_cast<std::uint64_t>(y) * width + x) * kChannels;
            rgba[offset] = to_byte(static_cast<std::uint64_t>(x) * 17 + frame_id);
            rgba[offset + 1] = to_byte(static_cast<std::uint64_t>(y) * 17 + frame_id * 3);
            rgba[offset + 2] = to_byte(static_cast<std::uint64_t>(x ^ y) * 15 + frame_id * 5);
            rgba[offset + 3] = 0xFFU;
        }
    }

    return CYCLES_BRIDGE_STATUS_OK;
}
