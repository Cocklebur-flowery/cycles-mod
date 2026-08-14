#pragma once

#include <cstdint>

#if defined(_WIN32)
#if defined(CYCLES_BRIDGE_BUILD)
#define CYCLES_BRIDGE_API __declspec(dllexport)
#else
#define CYCLES_BRIDGE_API __declspec(dllimport)
#endif
#else
#define CYCLES_BRIDGE_API
#endif

extern "C" {

enum CyclesBridgeStatus : std::uint32_t {
    CYCLES_BRIDGE_STATUS_OK = 0,
    CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT = 1,
    CYCLES_BRIDGE_STATUS_BUFFER_TOO_SMALL = 2,
    CYCLES_BRIDGE_STATUS_OUT_OF_MEMORY = 3,
    CYCLES_BRIDGE_STATUS_UNINITIALIZED = 4,
    CYCLES_BRIDGE_STATUS_RENDER_ERROR = 5,
};

enum CyclesBridgeDeviceUpdatePhase : std::uint32_t {
    CYCLES_BRIDGE_DEVICE_PHASE_SHADER_BACKGROUND = 0,
    CYCLES_BRIDGE_DEVICE_PHASE_OBJECT = 1,
    CYCLES_BRIDGE_DEVICE_PHASE_MESH_GEOMETRY = 2,
    CYCLES_BRIDGE_DEVICE_PHASE_IMAGE_VOLUME = 3,
    CYCLES_BRIDGE_DEVICE_PHASE_LIGHT = 4,
    CYCLES_BRIDGE_DEVICE_PHASE_INTEGRATOR_FILM = 5,
    CYCLES_BRIDGE_DEVICE_PHASE_FINALIZE = 6,
    CYCLES_BRIDGE_DEVICE_PHASE_UNCLASSIFIED = 7,
    CYCLES_BRIDGE_DEVICE_PHASE_COUNT = 8,
};

enum CyclesBridgeMaterialFlags : std::uint32_t {
    CYCLES_BRIDGE_MATERIAL_CUTOUT = 1U << 0U,
    CYCLES_BRIDGE_MATERIAL_BLEND = 1U << 1U,
    CYCLES_BRIDGE_MATERIAL_TRANSMISSION = 1U << 2U,
    CYCLES_BRIDGE_MATERIAL_WATER = 1U << 3U,
};

enum CyclesBridgePbrFormat : std::uint32_t {
    CYCLES_BRIDGE_PBR_NONE = 0,
    CYCLES_BRIDGE_PBR_LAB_1_3 = 1,
};

enum CyclesBridgeHeightMappingMode : std::uint32_t {
    CYCLES_BRIDGE_HEIGHT_MAPPING_BUMP = 0,
    CYCLES_BRIDGE_HEIGHT_MAPPING_PARALLAX_OCCLUSION = 1,
};

enum CyclesBridgeTextureRole : std::uint32_t {
    CYCLES_BRIDGE_TEXTURE_COLOR_SRGB = 0,
    CYCLES_BRIDGE_TEXTURE_DATA_LINEAR = 1,
};

enum CyclesBridgeTextureIndex : std::uint32_t {
    CYCLES_BRIDGE_TEXTURE_INDEX_INVALID = 0xFFFFFFFFU,
};

enum CyclesBridgeFrameFlags : std::uint32_t {
    CYCLES_BRIDGE_FRAME_READY = 1U << 0U,
    CYCLES_BRIDGE_FRAME_UPDATED = 1U << 1U,
};

enum CyclesBridgePixelFormat : std::uint32_t {
    CYCLES_BRIDGE_PIXEL_FORMAT_UNKNOWN = 0,
    CYCLES_BRIDGE_PIXEL_FORMAT_RGBA8_UNORM = 1,
    CYCLES_BRIDGE_PIXEL_FORMAT_RGBA16_FLOAT = 2,
    CYCLES_BRIDGE_PIXEL_FORMAT_RGBA32_FLOAT = 3,
};

enum CyclesBridgeViewTransform : std::uint32_t {
    CYCLES_BRIDGE_VIEW_TRANSFORM_STANDARD = 0,
    CYCLES_BRIDGE_VIEW_TRANSFORM_RAW = 1,
    CYCLES_BRIDGE_VIEW_TRANSFORM_AGX = 2,
    CYCLES_BRIDGE_VIEW_TRANSFORM_KHRONOS_PBR_NEUTRAL = 3,
    CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_2 = 4,
    CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_1_3 = 5,
    CYCLES_BRIDGE_VIEW_TRANSFORM_FILMIC = 6,
    CYCLES_BRIDGE_VIEW_TRANSFORM_FILMIC_LOG = 7,
    CYCLES_BRIDGE_VIEW_TRANSFORM_FALSE_COLOR = 8,
    CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_1_3_HDR_1000 = 9,
    CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_1_3_HDR_2000 = 10,
    CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_1_3_HDR_4000 = 11,
    CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_2_HDR_500 = 12,
    CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_2_HDR_1000 = 13,
    CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_2_HDR_2000 = 14,
    CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_2_HDR_4000 = 15,
    CYCLES_BRIDGE_VIEW_TRANSFORM_AGX_HDR_1000 = 16,
};

enum CyclesBridgeDisplayDevice : std::uint32_t {
    CYCLES_BRIDGE_DISPLAY_SRGB = 0,
    CYCLES_BRIDGE_DISPLAY_P3 = 1,
    CYCLES_BRIDGE_DISPLAY_REC1886 = 2,
    CYCLES_BRIDGE_DISPLAY_REC2020 = 3,
    CYCLES_BRIDGE_DISPLAY_REC2100_PQ = 4,
    CYCLES_BRIDGE_DISPLAY_REC2100_HLG = 5,
};

enum CyclesBridgeColorLook : std::uint32_t {
    CYCLES_BRIDGE_COLOR_LOOK_NONE = 0,
    CYCLES_BRIDGE_COLOR_LOOK_AGX_PUNCHY = 1,
    CYCLES_BRIDGE_COLOR_LOOK_AGX_VERY_HIGH_CONTRAST = 2,
    CYCLES_BRIDGE_COLOR_LOOK_AGX_HIGH_CONTRAST = 3,
    CYCLES_BRIDGE_COLOR_LOOK_AGX_MEDIUM_HIGH_CONTRAST = 4,
    CYCLES_BRIDGE_COLOR_LOOK_AGX_BASE_CONTRAST = 5,
    CYCLES_BRIDGE_COLOR_LOOK_AGX_MEDIUM_LOW_CONTRAST = 6,
    CYCLES_BRIDGE_COLOR_LOOK_AGX_LOW_CONTRAST = 7,
    CYCLES_BRIDGE_COLOR_LOOK_AGX_VERY_LOW_CONTRAST = 8,
    CYCLES_BRIDGE_COLOR_LOOK_AGX_GREYSCALE = 9,
    CYCLES_BRIDGE_COLOR_LOOK_FILMIC_VERY_HIGH_CONTRAST = 10,
    CYCLES_BRIDGE_COLOR_LOOK_FILMIC_HIGH_CONTRAST = 11,
    CYCLES_BRIDGE_COLOR_LOOK_FILMIC_MEDIUM_HIGH_CONTRAST = 12,
    CYCLES_BRIDGE_COLOR_LOOK_FILMIC_MEDIUM_CONTRAST = 13,
    CYCLES_BRIDGE_COLOR_LOOK_FILMIC_MEDIUM_LOW_CONTRAST = 14,
    CYCLES_BRIDGE_COLOR_LOOK_FILMIC_LOW_CONTRAST = 15,
    CYCLES_BRIDGE_COLOR_LOOK_FILMIC_VERY_LOW_CONTRAST = 16,
    CYCLES_BRIDGE_COLOR_LOOK_FALSE_COLOR_PUNCHY = 17,
    CYCLES_BRIDGE_COLOR_LOOK_FALSE_COLOR_VERY_HIGH_CONTRAST = 18,
    CYCLES_BRIDGE_COLOR_LOOK_FALSE_COLOR_HIGH_CONTRAST = 19,
    CYCLES_BRIDGE_COLOR_LOOK_FALSE_COLOR_MEDIUM_HIGH_CONTRAST = 20,
    CYCLES_BRIDGE_COLOR_LOOK_FALSE_COLOR_BASE_CONTRAST = 21,
    CYCLES_BRIDGE_COLOR_LOOK_FALSE_COLOR_MEDIUM_LOW_CONTRAST = 22,
    CYCLES_BRIDGE_COLOR_LOOK_FALSE_COLOR_LOW_CONTRAST = 23,
    CYCLES_BRIDGE_COLOR_LOOK_FALSE_COLOR_VERY_LOW_CONTRAST = 24,
    CYCLES_BRIDGE_COLOR_LOOK_FALSE_COLOR_GREYSCALE = 25,
    CYCLES_BRIDGE_COLOR_LOOK_ACES_1_3_GAMUT_COMPRESSION = 26,
    CYCLES_BRIDGE_COLOR_LOOK_ACES_2_GAMUT_COMPRESSION = 27,
};

enum CyclesBridgeWorkingSpace : std::uint32_t {
    CYCLES_BRIDGE_WORKING_SPACE_LINEAR_REC709 = 0,
    CYCLES_BRIDGE_WORKING_SPACE_LINEAR_REC2020 = 1,
    CYCLES_BRIDGE_WORKING_SPACE_ACESCG = 2,
};

enum CyclesBridgeColorConfigState : std::uint32_t {
    CYCLES_BRIDGE_COLOR_CONFIG_UNAVAILABLE = 0,
    CYCLES_BRIDGE_COLOR_CONFIG_READY = 1,
    CYCLES_BRIDGE_COLOR_CONFIG_ERROR = 2,
};

enum CyclesBridgeColorLutFlags : std::uint32_t {
    CYCLES_BRIDGE_COLOR_LUT_SCENE_LINEAR_INPUT = 1U << 0U,
    CYCLES_BRIDGE_COLOR_LUT_DISPLAY_REFERRED_OUTPUT = 1U << 1U,
    CYCLES_BRIDGE_COLOR_LUT_FLATTENED_RED_MAJOR = 1U << 2U,
};

enum CyclesBridgeColorLutInterpolation : std::uint32_t {
    CYCLES_BRIDGE_COLOR_LUT_INTERPOLATION_TRILINEAR = 1,
};

enum CyclesBridgeFrameVariant : std::uint32_t {
    CYCLES_BRIDGE_FRAME_VARIANT_RAW = 0,
    CYCLES_BRIDGE_FRAME_VARIANT_DENOISED = 1,
};

enum CyclesBridgePassSemantic : std::uint32_t {
    CYCLES_BRIDGE_PASS_SEMANTIC_COLOR = 1,
    CYCLES_BRIDGE_PASS_SEMANTIC_DEPTH = 2,
    CYCLES_BRIDGE_PASS_SEMANTIC_NORMAL = 3,
    CYCLES_BRIDGE_PASS_SEMANTIC_SCALAR = 4,
};

enum CyclesBridgePassDescriptorFlags : std::uint32_t {
    CYCLES_BRIDGE_PASS_DISPLAYABLE = 1U << 0U,
    CYCLES_BRIDGE_PASS_COLOR_MANAGED = 1U << 1U,
    CYCLES_BRIDGE_PASS_DENOISE_RESULT = 1U << 2U,
    CYCLES_BRIDGE_PASS_DEBUG = 1U << 3U,
    CYCLES_BRIDGE_PASS_CACHE_RAW = 1U << 4U,
    CYCLES_BRIDGE_PASS_CACHE_DENOISED = 1U << 5U,
};

enum CyclesBridgeCapabilityFlags : std::uint64_t {
    CYCLES_BRIDGE_CAPABILITY_SETTINGS = 1ULL << 0U,
    CYCLES_BRIDGE_CAPABILITY_PASS_VIEWER = 1ULL << 1U,
    CYCLES_BRIDGE_CAPABILITY_DENOISE = 1ULL << 2U,
    CYCLES_BRIDGE_CAPABILITY_OPTIX_COMPILED = 1ULL << 3U,
    CYCLES_BRIDGE_CAPABILITY_CUDA_COMPILED = 1ULL << 4U,
    CYCLES_BRIDGE_CAPABILITY_OIDN_COMPILED = 1ULL << 5U,
    CYCLES_BRIDGE_CAPABILITY_OCIO_COMPILED = 1ULL << 6U,
    CYCLES_BRIDGE_CAPABILITY_DLSS_EXPERIMENTAL_COMPILED = 1ULL << 7U,
};

enum CyclesBridgeDeviceMask : std::uint32_t {
    CYCLES_BRIDGE_DEVICE_OPTIX = 1U << 0U,
    CYCLES_BRIDGE_DEVICE_CUDA = 1U << 1U,
    CYCLES_BRIDGE_DEVICE_CPU = 1U << 2U,
};

enum CyclesBridgeDenoiserMask : std::uint32_t {
    CYCLES_BRIDGE_DENOISER_OPTIX = 1U << 0U,
    CYCLES_BRIDGE_DENOISER_OPENIMAGEDENOISE = 1U << 1U,
    CYCLES_BRIDGE_DENOISER_DLSS_EXPERIMENTAL = 1U << 2U,
};

enum CyclesBridgeDlssQualityMode : std::uint32_t {
    CYCLES_BRIDGE_DLSS_QUALITY_DLAA = 0,
    CYCLES_BRIDGE_DLSS_QUALITY_QUALITY = 1,
    CYCLES_BRIDGE_DLSS_QUALITY_BALANCED = 2,
    CYCLES_BRIDGE_DLSS_QUALITY_PERFORMANCE = 3,
    CYCLES_BRIDGE_DLSS_QUALITY_ULTRA_PERFORMANCE = 4,
};

enum CyclesBridgeDenoiserScheduleReason : std::uint32_t {
    CYCLES_BRIDGE_DENOISER_SCHEDULE_DISABLED = 0,
    CYCLES_BRIDGE_DENOISER_SCHEDULE_INTERACTIVE = 1,
    CYCLES_BRIDGE_DENOISER_SCHEDULE_SETTLING = 2,
    CYCLES_BRIDGE_DENOISER_SCHEDULE_DEBUG_PASS = 3,
    CYCLES_BRIDGE_DENOISER_SCHEDULE_STILL = 4,
    CYCLES_BRIDGE_DENOISER_SCHEDULE_REALTIME = 5,
};

enum CyclesBridgePass : std::uint32_t {
    CYCLES_BRIDGE_PASS_COMBINED = 0,
    CYCLES_BRIDGE_PASS_DEPTH = 1,
    CYCLES_BRIDGE_PASS_NORMAL = 2,
    CYCLES_BRIDGE_PASS_DIFFUSE_COLOR = 3,
    CYCLES_BRIDGE_PASS_EMISSION = 4,
    CYCLES_BRIDGE_PASS_ROUGHNESS = 5,
    CYCLES_BRIDGE_PASS_SAMPLE_COUNT = 6,
    CYCLES_BRIDGE_PASS_COUNT = 7,
};

enum CyclesBridgeResetLevel : std::uint32_t {
    CYCLES_BRIDGE_RESET_NONE = 0,
    CYCLES_BRIDGE_RESET_ACCUMULATION = 1,
    CYCLES_BRIDGE_RESET_BUFFER = 2,
    CYCLES_BRIDGE_RESET_SESSION = 3,
};

enum CyclesBridgeSamplingState : std::uint32_t {
    CYCLES_BRIDGE_SAMPLING_IDLE = 0,
    CYCLES_BRIDGE_SAMPLING_INTERACTIVE = 1,
    CYCLES_BRIDGE_SAMPLING_SETTLING = 2,
    CYCLES_BRIDGE_SAMPLING_STILL = 3,
};

enum CyclesBridgeSamplingPattern : std::uint32_t {
    CYCLES_BRIDGE_SAMPLING_PATTERN_SOBOL_BURLEY = 0,
    CYCLES_BRIDGE_SAMPLING_PATTERN_TABULATED_SOBOL = 1,
    CYCLES_BRIDGE_SAMPLING_PATTERN_BLUE_NOISE_PURE = 2,
    CYCLES_BRIDGE_SAMPLING_PATTERN_BLUE_NOISE_FIRST = 3,
    CYCLES_BRIDGE_SAMPLING_PATTERN_BLUE_NOISE_ROUND = 4,
};

enum CyclesBridgeProjectionMode : std::uint32_t {
    CYCLES_BRIDGE_PROJECTION_MINECRAFT_FOV = 0,
    CYCLES_BRIDGE_PROJECTION_PHYSICAL_LENS = 1,
};

enum CyclesBridgeCameraType : std::uint32_t {
    CYCLES_BRIDGE_CAMERA_PERSPECTIVE = 0,
    CYCLES_BRIDGE_CAMERA_PANORAMA = 1,
};

enum CyclesBridgeCameraFlags : std::uint32_t {
    CYCLES_BRIDGE_CAMERA_FOCUS_DISTANCE_VALID = 1U << 0U,
};

// Values intentionally match ccl::PanoramaType in Cycles 5.2.
enum CyclesBridgePanoramaType : std::uint32_t {
    CYCLES_BRIDGE_PANORAMA_EQUIRECTANGULAR = 0,
    CYCLES_BRIDGE_PANORAMA_FISHEYE_EQUIDISTANT = 1,
    CYCLES_BRIDGE_PANORAMA_FISHEYE_EQUISOLID = 2,
    CYCLES_BRIDGE_PANORAMA_MIRRORBALL = 3,
    CYCLES_BRIDGE_PANORAMA_FISHEYE_LENS_POLYNOMIAL = 4,
    CYCLES_BRIDGE_PANORAMA_EQUIANGULAR_CUBEMAP_FACE = 5,
    CYCLES_BRIDGE_PANORAMA_CENTRAL_CYLINDRICAL = 6,
};

enum CyclesBridgeVulkanInteropFlags : std::uint32_t {
    CYCLES_BRIDGE_VULKAN_INTEROP_OWNERSHIP_TRANSFER = 1U << 0U,
};

enum CyclesBridgeVulkanInteropStateFlags : std::uint32_t {
    CYCLES_BRIDGE_VULKAN_INTEROP_BOUND = 1U << 0U,
    CYCLES_BRIDGE_VULKAN_INTEROP_ACTIVE = 1U << 1U,
    CYCLES_BRIDGE_VULKAN_INTEROP_FRAME_READY = 1U << 2U,
    CYCLES_BRIDGE_VULKAN_INTEROP_FAILED = 1U << 3U,
    CYCLES_BRIDGE_VULKAN_INTEROP_SESSION_ATTACHED = 1U << 4U,
    CYCLES_BRIDGE_VULKAN_INTEROP_FRAME_ACQUIRED = 1U << 5U,
    CYCLES_BRIDGE_VULKAN_INTEROP_TIMELINE_SYNC = 1U << 6U,
};

struct CyclesBridgeCamera {
    std::uint32_t struct_size;
    std::uint32_t struct_version;
    std::uint64_t frame_id;
    std::uint32_t viewport_width;
    std::uint32_t viewport_height;
    double position_x;
    double position_y;
    double position_z;
    float rotation_x;
    float rotation_y;
    float rotation_z;
    float rotation_w;
    float vertical_fov_radians;
    float depth_far;
    float focus_distance;
    std::uint32_t flags;
};

struct CyclesBridgeScene {
    std::uint32_t struct_size;
    std::uint32_t struct_version;
    std::int32_t origin_x;
    std::int32_t origin_y;
    std::int32_t origin_z;
    std::uint32_t vertex_count;
    std::uint32_t triangle_count;
    std::uint32_t material_count;
    std::uint32_t texture_count;
    std::uint32_t texture_byte_count;
    std::uint32_t reserved[2];
};

struct CyclesBridgeSceneResources {
    std::uint32_t struct_size;
    std::uint32_t struct_version;
    std::int32_t origin_x;
    std::int32_t origin_y;
    std::int32_t origin_z;
    std::uint32_t material_count;
    std::uint32_t texture_count;
    std::uint32_t texture_byte_count;
    std::uint32_t reserved[4];
};

struct CyclesBridgeSection {
    std::uint32_t struct_size;
    std::uint32_t struct_version;
    std::int64_t section_id;
    std::int32_t origin_x;
    std::int32_t origin_y;
    std::int32_t origin_z;
    std::uint32_t vertex_count;
    std::uint32_t triangle_count;
    std::uint32_t reserved[2];
};

struct CyclesBridgeFrame {
    std::uint32_t struct_size;
    std::uint32_t struct_version;
    std::uint32_t width;
    std::uint32_t height;
    std::uint64_t generation;
    std::uint32_t pixel_byte_count;
    std::uint32_t flags;
    std::uint32_t sample_count;
    std::uint32_t reserved;
};

struct CyclesBridgeFrameView {
    std::uint32_t struct_size;
    std::uint32_t struct_version;
    std::uint32_t width;
    std::uint32_t height;
    std::uint64_t generation;
    std::uint32_t sample_count;
    std::uint32_t pixel_format;
    std::uint64_t pixel_byte_count;
    std::uint64_t token;
    const std::uint8_t* pixels;
    std::uint32_t flags;
    std::uint32_t reserved[3];
};

struct CyclesBridgeRenderSettings {
    std::uint32_t struct_size;
    std::uint32_t struct_version;
    std::uint64_t revision;
    std::uint32_t device_policy;
    std::uint32_t resolution_mode;
    std::uint32_t render_width;
    std::uint32_t render_height;
    std::uint32_t resolution_percentage;
    std::uint32_t interactive_samples;
    std::uint32_t still_samples;
    std::uint32_t stationary_delay_millis;
    std::uint32_t adaptive_sampling;
    std::uint32_t minimum_samples;
    float noise_threshold;
    std::uint32_t interactive_time_limit_millis;
    std::uint32_t still_time_limit_millis;
    std::uint32_t minimum_bounce;
    std::uint32_t maximum_bounce;
    std::uint32_t diffuse_bounces;
    std::uint32_t glossy_bounces;
    std::uint32_t transmission_bounces;
    std::uint32_t volume_bounces;
    std::uint32_t transparent_bounces;
    float clamp_direct;
    float clamp_indirect;
    float filter_glossy;
    std::uint32_t reflective_caustics;
    std::uint32_t refractive_caustics;
    std::uint32_t pixel_filter;
    float filter_width;
    std::int32_t seed;
    std::uint32_t denoiser_mode;
    std::uint32_t denoiser_start_sample;
    std::uint32_t denoiser_input;
    std::uint32_t denoiser_prefilter;
    std::uint32_t denoiser_quality;
    std::uint32_t denoiser_use_gpu;
    float exposure_ev;
    float gamma;
    std::uint32_t view_transform;
    std::uint32_t active_pass;
    std::uint32_t debug_overlay;
    std::uint32_t dynamic_resolution;
    std::uint32_t interactive_resolution_percentage;
    std::uint32_t pass_cache_megabytes;
    std::uint32_t sampling_pattern;
    float camera_clip_near;
    float camera_clip_far;
    std::uint32_t projection_mode;
    float focal_length_mm;
    float sensor_width_mm;
    std::uint32_t depth_of_field;
    float focus_distance;
    float f_stop;
    std::uint32_t aperture_blades;
    float aperture_rotation_degrees;
    float aperture_ratio;
    std::uint32_t atmosphere_sun_disc;
    float atmosphere_sun_size_degrees;
    float atmosphere_sun_intensity;
    float atmosphere_sun_elevation_degrees;
    float atmosphere_sun_rotation_degrees;
    float atmosphere_altitude_meters;
    float atmosphere_air_density;
    float atmosphere_aerosol_density;
    float atmosphere_ozone_density;
    float pbr_normal_strength;
    float pbr_emission_scale;
    std::uint32_t working_space;
    std::uint32_t dlss_quality_mode;
    std::uint32_t dlss_reserved;
    std::uint32_t camera_type;
    std::uint32_t panorama_type;
    float fisheye_fov_degrees;
    float fisheye_lens_mm;
    float latitude_min_degrees;
    float latitude_max_degrees;
    float longitude_min_degrees;
    float longitude_max_degrees;
    float fisheye_polynomial_k0;
    float fisheye_polynomial_k1;
    float fisheye_polynomial_k2;
    float fisheye_polynomial_k3;
    float fisheye_polynomial_k4;
    float central_cylindrical_longitude_min_degrees;
    float central_cylindrical_longitude_max_degrees;
    float central_cylindrical_height_min;
    float central_cylindrical_height_max;
    float central_cylindrical_radius;
    float camera_shift_x;
    float camera_shift_y;
    float pbr_wetness;
    float pbr_subsurface_scale;
    float pbr_height_strength;
    float pbr_height_distance;
    std::uint32_t pbr_height_mapping_mode;
    std::uint32_t pbr_parallax_steps;
};

struct CyclesBridgePassDescriptor {
    std::uint32_t struct_size;
    std::uint32_t struct_version;
    std::uint32_t pass_id;
    std::uint32_t source_component_count;
    std::uint32_t display_component_count;
    std::uint32_t pixel_format;
    std::uint32_t semantic;
    std::uint32_t flags;
    std::uint32_t reserved[8];
};

struct CyclesBridgeCapabilities {
    std::uint32_t struct_size;
    std::uint32_t struct_version;
    std::uint64_t capability_flags;
    std::uint64_t pass_mask;
    std::uint32_t denoiser_mask;
    std::uint32_t device_mask;
    std::uint32_t maximum_width;
    std::uint32_t maximum_height;
    std::uint32_t device_count;
    std::uint32_t color_transform_mask;
    std::uint32_t color_lut_edge_length;
    std::uint32_t color_lut_pixel_format;
    std::uint32_t color_config_state;
    std::uint32_t reserved;
};

struct CyclesBridgeColorLutDescriptor {
    std::uint32_t struct_size;
    std::uint32_t struct_version;
    std::uint32_t view_transform;
    std::uint32_t edge_length;
    std::uint32_t width;
    std::uint32_t height;
    std::uint32_t pixel_format;
    std::uint32_t flags;
    std::uint64_t pixel_byte_count;
    float shaper_log2_min;
    float shaper_log2_max;
    float shaper_epsilon;
    std::uint32_t interpolation;
    std::uint32_t color_look;
    std::uint32_t working_space;
    std::uint32_t display_device;
    std::uint32_t reserved;
};

struct CyclesBridgeDiagnostics {
    std::uint32_t struct_size;
    std::uint32_t struct_version;
    std::uint64_t settings_revision;
    std::uint64_t scene_revision;
    std::uint64_t camera_revision;
    std::uint64_t frame_generation;
    std::uint32_t state_code;
    std::uint32_t device_type;
    std::uint32_t effective_denoiser;
    std::uint32_t active_pass;
    std::uint32_t width;
    std::uint32_t height;
    std::uint32_t sample_count;
    std::uint32_t section_count;
    std::uint32_t reset_level;
    std::uint32_t frame_ready;
    std::uint32_t active_frame_leases;
    std::uint32_t peak_frame_leases;
    std::uint32_t frame_slot_count;
    std::uint32_t dropped_display_updates;
    std::uint32_t target_sample_count;
    std::uint32_t sampling_state;
    float sample_rate;
    std::uint32_t settling_remaining_millis;
    std::uint64_t produced_frame_count;
    std::uint64_t copied_frame_count;
    std::uint64_t copied_byte_count;
    std::uint64_t unchanged_poll_count;
    std::uint32_t last_convert_micros;
    std::uint32_t ema_convert_micros;
    std::uint32_t max_convert_micros;
    std::uint32_t last_copy_micros;
    std::uint32_t ema_copy_micros;
    std::uint32_t max_copy_micros;
    std::uint32_t frame_age_micros;
    std::uint32_t sampling_transition_count;
    std::uint64_t scene_commit_count;
    std::uint64_t scene_delta_count;
    std::uint64_t render_start_count;
    std::uint32_t last_scene_commit_micros;
    std::uint32_t ema_scene_commit_micros;
    std::uint32_t max_scene_commit_micros;
    std::uint32_t last_scene_delta_micros;
    std::uint32_t ema_scene_delta_micros;
    std::uint32_t max_scene_delta_micros;
    std::uint32_t last_render_start_micros;
    std::uint32_t ema_render_start_micros;
    std::uint32_t max_render_start_micros;
    std::uint32_t frame_pixel_format;
    std::uint64_t cached_raw_pass_mask;
    std::uint64_t cached_denoised_pass_mask;
    std::uint64_t pass_cache_bytes;
    std::uint64_t pass_cache_budget_bytes;
    std::uint32_t pass_cache_entry_count;
    std::uint32_t pass_cache_eviction_count;
    std::uint32_t pass_cache_hit_count;
    std::uint32_t active_frame_variant;
    std::uint64_t registered_pass_mask;
    std::uint32_t pass_registry_rebuild_count;
    std::uint32_t pass_registry_hit_count;
    std::uint32_t selected_denoiser;
    std::uint32_t denoiser_scheduled;
    std::uint32_t effective_denoiser_start_sample;
    std::uint32_t denoiser_schedule_reason;
    std::uint32_t denoiser_schedule_run_count;
    std::uint32_t denoiser_schedule_skip_count;
    std::uint32_t sampling_pattern;
    float effective_camera_clip_near;
    float effective_camera_clip_far;
    std::uint32_t projection_mode;
    float vertical_fov_radians;
    std::uint32_t depth_of_field;
    float focus_distance;
    float f_stop;
    float aperture_size;
    std::uint32_t aperture_blades;
    float aperture_rotation_radians;
    float aperture_ratio;
    std::uint32_t device_uuid_valid;
    std::uint8_t device_uuid[16];
    std::uint32_t reserved;
    std::uint64_t scene_timing_revision;
    std::uint64_t scene_timing_count;
    std::uint32_t last_scene_queue_micros;
    std::uint32_t ema_scene_queue_micros;
    std::uint32_t max_scene_queue_micros;
    std::uint32_t last_reset_wait_micros;
    std::uint32_t ema_reset_wait_micros;
    std::uint32_t max_reset_wait_micros;
    std::uint32_t last_device_update_micros;
    std::uint32_t ema_device_update_micros;
    std::uint32_t max_device_update_micros;
    std::uint32_t last_geometry_update_micros;
    std::uint32_t ema_geometry_update_micros;
    std::uint32_t max_geometry_update_micros;
    std::uint32_t last_bvh_update_micros;
    std::uint32_t ema_bvh_update_micros;
    std::uint32_t max_bvh_update_micros;
    std::uint32_t phase_reserved[3];
    std::uint32_t last_scene_first_frame_micros;
    std::uint32_t ema_scene_first_frame_micros;
    std::uint32_t max_scene_first_frame_micros;
    std::uint32_t timing_reserved;
    std::uint32_t camera_type;
    std::uint32_t panorama_type;
    std::uint32_t active_device_phase;
    std::uint32_t active_device_phase_micros;
    std::uint32_t last_device_phase_micros[CYCLES_BRIDGE_DEVICE_PHASE_COUNT];
    std::uint32_t ema_device_phase_micros[CYCLES_BRIDGE_DEVICE_PHASE_COUNT];
    std::uint32_t max_device_phase_micros[CYCLES_BRIDGE_DEVICE_PHASE_COUNT];
    float camera_shift_x;
    float camera_shift_y;
    std::uint32_t last_render_configure_micros;
    std::uint32_t ema_render_configure_micros;
    std::uint32_t max_render_configure_micros;
    std::uint32_t last_render_reset_micros;
    std::uint32_t ema_render_reset_micros;
    std::uint32_t max_render_reset_micros;
    std::uint32_t last_render_prepare_micros;
    std::uint32_t ema_render_prepare_micros;
    std::uint32_t max_render_prepare_micros;
    std::uint32_t last_session_start_micros;
    std::uint32_t ema_session_start_micros;
    std::uint32_t max_session_start_micros;
};

struct CyclesBridgeVulkanInteropBuffer {
    std::uint32_t struct_size;
    std::uint32_t struct_version;
    // Capacity dimensions used for diagnostics. allocation_byte_count is the
    // authoritative limit; active frames may use any dimensions that fit.
    std::uint32_t width;
    std::uint32_t height;
    std::uint32_t pixel_format;
    std::uint32_t flags;
    std::uint64_t allocation_byte_count;
    std::uint64_t memory_handle;
    std::uint8_t device_uuid[16];
    std::uint32_t slot_count;
    std::uint32_t slot_stride_bytes;
    std::uint64_t ready_semaphore_handle;
    std::uint64_t release_semaphore_handle;
};

struct CyclesBridgeVulkanInteropState {
    std::uint32_t struct_size;
    std::uint32_t struct_version;
    std::uint32_t flags;
    std::uint32_t width;
    std::uint32_t height;
    std::uint32_t sample_count;
    std::uint64_t generation;
    std::uint64_t completed_frame_count;
    std::uint32_t last_sync_micros;
    std::uint32_t ema_sync_micros;
    std::uint32_t max_sync_micros;
    std::uint32_t slot_index;
    std::uint32_t slot_count;
    std::uint32_t ready_slot_count;
    std::uint64_t producer_wait_count;
};

struct CyclesBridgeVertex {
    float position_x;
    float position_y;
    float position_z;
    float normal_x;
    float normal_y;
    float normal_z;
    float texture_u;
    float texture_v;
    std::uint32_t packed_rgba;
    std::uint32_t reserved;
};

struct CyclesBridgeTriangle {
    std::uint32_t vertex_0;
    std::uint32_t vertex_1;
    std::uint32_t vertex_2;
    std::uint32_t material_index;
};

struct CyclesBridgeMaterial {
    std::uint32_t texture_index;
    std::uint32_t flags;
    float emission_strength;
    float alpha_cutoff;
    std::uint32_t normal_texture_index;
    std::uint32_t material_texture_index;
    std::uint32_t pbr_format;
    std::uint32_t auxiliary_texture_index;
};

struct CyclesBridgeTexture {
    std::uint32_t width;
    std::uint32_t height;
    std::uint32_t pixel_offset;
    std::uint32_t pixel_size;
    std::uint32_t role;
    std::uint32_t reserved[3];
};

struct CyclesBridgeRenderer;

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_abi_version();

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_write_build_info(
    char* output,
    std::uint32_t capacity);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_fill_test_frame(
    std::uint8_t* rgba,
    std::uint32_t width,
    std::uint32_t height,
    std::uint64_t frame_id);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_create_renderer(
    CyclesBridgeRenderer** output_renderer);

CYCLES_BRIDGE_API void cycles_bridge_destroy_renderer(
    CyclesBridgeRenderer* renderer);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_write_renderer_info(
    const CyclesBridgeRenderer* renderer,
    char* output,
    std::uint32_t capacity);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_query_capabilities(
    const CyclesBridgeRenderer* renderer,
    CyclesBridgeCapabilities* capabilities);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_write_color_management_info(
    const CyclesBridgeRenderer* renderer,
    char* output,
    std::uint32_t capacity);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_query_color_lut(
    const CyclesBridgeRenderer* renderer,
    std::uint32_t display_device,
    std::uint32_t view_transform,
    std::uint32_t color_look,
    std::uint32_t working_space,
    CyclesBridgeColorLutDescriptor* descriptor,
    float* rgba,
    std::uint64_t rgba_capacity);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_query_pass_descriptor(
    std::uint32_t pass_id,
    CyclesBridgePassDescriptor* descriptor);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_apply_settings(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeRenderSettings* settings);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_query_diagnostics(
    const CyclesBridgeRenderer* renderer,
    CyclesBridgeDiagnostics* diagnostics);

// The native bridge takes ownership of all non-zero Win32 handles in the
// descriptor whenever descriptor is non-null, including error returns.
CYCLES_BRIDGE_API std::uint32_t cycles_bridge_bind_vulkan_interop_buffer(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeVulkanInteropBuffer* descriptor);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_unbind_vulkan_interop_buffer(
    CyclesBridgeRenderer* renderer);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_query_vulkan_interop_state(
    const CyclesBridgeRenderer* renderer,
    CyclesBridgeVulkanInteropState* state);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_acquire_vulkan_interop_frame(
    CyclesBridgeRenderer* renderer,
    std::uint64_t previous_generation,
    CyclesBridgeVulkanInteropState* state);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_release_vulkan_interop_frame(
    CyclesBridgeRenderer* renderer,
    std::uint64_t generation);

CYCLES_BRIDGE_API void cycles_bridge_close_win32_handle(
    std::uint64_t handle);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_upload_scene(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeScene* scene,
    const CyclesBridgeVertex* vertices,
    const CyclesBridgeTriangle* triangles,
    const CyclesBridgeMaterial* materials,
    const CyclesBridgeTexture* textures,
    const std::uint8_t* texture_pixels);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_reset_scene(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeSceneResources* resources,
    const CyclesBridgeMaterial* materials,
    const CyclesBridgeTexture* textures,
    const std::uint8_t* texture_pixels);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_upsert_section(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeSection* section,
    const CyclesBridgeVertex* vertices,
    const CyclesBridgeTriangle* triangles);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_remove_section(
    CyclesBridgeRenderer* renderer,
    std::int64_t section_id);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_commit_scene(
    CyclesBridgeRenderer* renderer);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_render(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeCamera* camera,
    std::uint8_t* rgba,
    std::uint64_t rgba_capacity);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_update_camera(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeCamera* camera);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_acquire_frame(
    CyclesBridgeRenderer* renderer,
    std::uint64_t previous_generation,
    CyclesBridgeFrameView* frame_view);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_release_frame(
    CyclesBridgeRenderer* renderer,
    std::uint64_t token);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_render_frame(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeCamera* camera,
    CyclesBridgeFrame* frame,
    std::uint8_t* rgba,
    std::uint64_t rgba_capacity);

}
