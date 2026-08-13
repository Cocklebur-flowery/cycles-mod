#include "color_management.h"

#include <Windows.h>

#include <OpenColorIO/OpenColorIO.h>

#include <array>
#include <atomic>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <limits>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {

namespace OCIO = OCIO_NAMESPACE;

constexpr std::uint32_t kLutEdgeLength = 64;
constexpr float kShaperLog2Min = -10.0F;
constexpr float kShaperLog2Max = 16.0F;
constexpr float kShaperEpsilon = 1.0F / 1024.0F;

struct DisplayDefinition final {
    std::uint32_t id;
    const char* name;
};

struct ViewDefinition final {
    std::uint32_t display;
    std::uint32_t id;
    const char* name;
};

struct LookDefinition final {
    std::uint32_t id;
    const char* name;
};

struct WorkingSpaceDefinition final {
    std::uint32_t id;
    const char* name;
};

constexpr std::array<DisplayDefinition, 6> kDisplays = {{
    {CYCLES_BRIDGE_DISPLAY_SRGB, "sRGB"},
    {CYCLES_BRIDGE_DISPLAY_P3, "Display P3"},
    {CYCLES_BRIDGE_DISPLAY_REC1886, "Rec.1886"},
    {CYCLES_BRIDGE_DISPLAY_REC2020, "Rec.2020"},
    {CYCLES_BRIDGE_DISPLAY_REC2100_PQ, "Rec.2100-PQ"},
    {CYCLES_BRIDGE_DISPLAY_REC2100_HLG, "Rec.2100-HLG"},
}};

constexpr std::array<ViewDefinition, 50> kOcioViews = {{
    {CYCLES_BRIDGE_DISPLAY_SRGB, CYCLES_BRIDGE_VIEW_TRANSFORM_STANDARD, "Standard"},
    {CYCLES_BRIDGE_DISPLAY_SRGB, CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_1_3, "ACES 1.3"},
    {CYCLES_BRIDGE_DISPLAY_SRGB, CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_2, "ACES 2.0"},
    {CYCLES_BRIDGE_DISPLAY_SRGB, CYCLES_BRIDGE_VIEW_TRANSFORM_KHRONOS_PBR_NEUTRAL, "Khronos PBR Neutral"},
    {CYCLES_BRIDGE_DISPLAY_SRGB, CYCLES_BRIDGE_VIEW_TRANSFORM_AGX, "AgX"},
    {CYCLES_BRIDGE_DISPLAY_SRGB, CYCLES_BRIDGE_VIEW_TRANSFORM_FILMIC, "Filmic"},
    {CYCLES_BRIDGE_DISPLAY_SRGB, CYCLES_BRIDGE_VIEW_TRANSFORM_FILMIC_LOG, "Filmic Log"},
    {CYCLES_BRIDGE_DISPLAY_SRGB, CYCLES_BRIDGE_VIEW_TRANSFORM_FALSE_COLOR, "False Color"},
    {CYCLES_BRIDGE_DISPLAY_SRGB, CYCLES_BRIDGE_VIEW_TRANSFORM_RAW, "Raw"},
    {CYCLES_BRIDGE_DISPLAY_P3, CYCLES_BRIDGE_VIEW_TRANSFORM_STANDARD, "Standard"},
    {CYCLES_BRIDGE_DISPLAY_P3, CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_1_3, "ACES 1.3"},
    {CYCLES_BRIDGE_DISPLAY_P3, CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_2, "ACES 2.0"},
    {CYCLES_BRIDGE_DISPLAY_P3, CYCLES_BRIDGE_VIEW_TRANSFORM_AGX, "AgX"},
    {CYCLES_BRIDGE_DISPLAY_P3, CYCLES_BRIDGE_VIEW_TRANSFORM_FALSE_COLOR, "False Color"},
    {CYCLES_BRIDGE_DISPLAY_P3, CYCLES_BRIDGE_VIEW_TRANSFORM_RAW, "Raw"},
    {CYCLES_BRIDGE_DISPLAY_REC1886, CYCLES_BRIDGE_VIEW_TRANSFORM_STANDARD, "Standard"},
    {CYCLES_BRIDGE_DISPLAY_REC1886, CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_1_3, "ACES 1.3"},
    {CYCLES_BRIDGE_DISPLAY_REC1886, CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_2, "ACES 2.0"},
    {CYCLES_BRIDGE_DISPLAY_REC1886, CYCLES_BRIDGE_VIEW_TRANSFORM_AGX, "AgX"},
    {CYCLES_BRIDGE_DISPLAY_REC1886, CYCLES_BRIDGE_VIEW_TRANSFORM_FALSE_COLOR, "False Color"},
    {CYCLES_BRIDGE_DISPLAY_REC1886, CYCLES_BRIDGE_VIEW_TRANSFORM_RAW, "Raw"},
    {CYCLES_BRIDGE_DISPLAY_REC2020, CYCLES_BRIDGE_VIEW_TRANSFORM_STANDARD, "Standard"},
    {CYCLES_BRIDGE_DISPLAY_REC2020, CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_1_3, "ACES 1.3"},
    {CYCLES_BRIDGE_DISPLAY_REC2020, CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_2, "ACES 2.0"},
    {CYCLES_BRIDGE_DISPLAY_REC2020, CYCLES_BRIDGE_VIEW_TRANSFORM_AGX, "AgX"},
    {CYCLES_BRIDGE_DISPLAY_REC2020, CYCLES_BRIDGE_VIEW_TRANSFORM_FALSE_COLOR, "False Color"},
    {CYCLES_BRIDGE_DISPLAY_REC2020, CYCLES_BRIDGE_VIEW_TRANSFORM_RAW, "Raw"},
    {CYCLES_BRIDGE_DISPLAY_REC2100_PQ, CYCLES_BRIDGE_VIEW_TRANSFORM_STANDARD, "Standard"},
    {CYCLES_BRIDGE_DISPLAY_REC2100_PQ, CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_1_3_HDR_1000, "ACES 1.3 - HDR 1000 nits"},
    {CYCLES_BRIDGE_DISPLAY_REC2100_PQ, CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_1_3_HDR_2000, "ACES 1.3 - HDR 2000 nits"},
    {CYCLES_BRIDGE_DISPLAY_REC2100_PQ, CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_1_3_HDR_4000, "ACES 1.3 - HDR 4000 nits"},
    {CYCLES_BRIDGE_DISPLAY_REC2100_PQ, CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_1_3, "ACES 1.3 - SDR"},
    {CYCLES_BRIDGE_DISPLAY_REC2100_PQ, CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_2_HDR_500, "ACES 2.0 - HDR 500 nits"},
    {CYCLES_BRIDGE_DISPLAY_REC2100_PQ, CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_2_HDR_1000, "ACES 2.0 - HDR 1000 nits"},
    {CYCLES_BRIDGE_DISPLAY_REC2100_PQ, CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_2_HDR_2000, "ACES 2.0 - HDR 2000 nits"},
    {CYCLES_BRIDGE_DISPLAY_REC2100_PQ, CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_2_HDR_4000, "ACES 2.0 - HDR 4000 nits"},
    {CYCLES_BRIDGE_DISPLAY_REC2100_PQ, CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_2, "ACES 2.0 - SDR"},
    {CYCLES_BRIDGE_DISPLAY_REC2100_PQ, CYCLES_BRIDGE_VIEW_TRANSFORM_AGX_HDR_1000, "AgX - HDR 1000 nits"},
    {CYCLES_BRIDGE_DISPLAY_REC2100_PQ, CYCLES_BRIDGE_VIEW_TRANSFORM_AGX, "AgX - SDR"},
    {CYCLES_BRIDGE_DISPLAY_REC2100_PQ, CYCLES_BRIDGE_VIEW_TRANSFORM_FALSE_COLOR, "False Color"},
    {CYCLES_BRIDGE_DISPLAY_REC2100_PQ, CYCLES_BRIDGE_VIEW_TRANSFORM_RAW, "Raw"},
    {CYCLES_BRIDGE_DISPLAY_REC2100_HLG, CYCLES_BRIDGE_VIEW_TRANSFORM_STANDARD, "Standard"},
    {CYCLES_BRIDGE_DISPLAY_REC2100_HLG, CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_1_3_HDR_1000, "ACES 1.3 - HDR 1000 nits"},
    {CYCLES_BRIDGE_DISPLAY_REC2100_HLG, CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_1_3, "ACES 1.3 - SDR"},
    {CYCLES_BRIDGE_DISPLAY_REC2100_HLG, CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_2_HDR_1000, "ACES 2.0 - HDR 1000 nits"},
    {CYCLES_BRIDGE_DISPLAY_REC2100_HLG, CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_2, "ACES 2.0 - SDR"},
    {CYCLES_BRIDGE_DISPLAY_REC2100_HLG, CYCLES_BRIDGE_VIEW_TRANSFORM_AGX_HDR_1000, "AgX - HDR 1000 nits"},
    {CYCLES_BRIDGE_DISPLAY_REC2100_HLG, CYCLES_BRIDGE_VIEW_TRANSFORM_AGX, "AgX - SDR"},
    {CYCLES_BRIDGE_DISPLAY_REC2100_HLG, CYCLES_BRIDGE_VIEW_TRANSFORM_FALSE_COLOR, "False Color"},
    {CYCLES_BRIDGE_DISPLAY_REC2100_HLG, CYCLES_BRIDGE_VIEW_TRANSFORM_RAW, "Raw"},
}};

constexpr std::array<LookDefinition, 9> kAgxLooks = {{
    {CYCLES_BRIDGE_COLOR_LOOK_AGX_PUNCHY, "AgX - Punchy"},
    {CYCLES_BRIDGE_COLOR_LOOK_AGX_VERY_HIGH_CONTRAST, "AgX - Very High Contrast"},
    {CYCLES_BRIDGE_COLOR_LOOK_AGX_HIGH_CONTRAST, "AgX - High Contrast"},
    {CYCLES_BRIDGE_COLOR_LOOK_AGX_MEDIUM_HIGH_CONTRAST, "AgX - Medium High Contrast"},
    {CYCLES_BRIDGE_COLOR_LOOK_AGX_BASE_CONTRAST, "AgX - Base Contrast"},
    {CYCLES_BRIDGE_COLOR_LOOK_AGX_MEDIUM_LOW_CONTRAST, "AgX - Medium Low Contrast"},
    {CYCLES_BRIDGE_COLOR_LOOK_AGX_LOW_CONTRAST, "AgX - Low Contrast"},
    {CYCLES_BRIDGE_COLOR_LOOK_AGX_VERY_LOW_CONTRAST, "AgX - Very Low Contrast"},
    {CYCLES_BRIDGE_COLOR_LOOK_AGX_GREYSCALE, "AgX - Greyscale"},
}};

constexpr std::array<LookDefinition, 7> kFilmicLooks = {{
    {CYCLES_BRIDGE_COLOR_LOOK_FILMIC_VERY_HIGH_CONTRAST, "Very High Contrast"},
    {CYCLES_BRIDGE_COLOR_LOOK_FILMIC_HIGH_CONTRAST, "High Contrast"},
    {CYCLES_BRIDGE_COLOR_LOOK_FILMIC_MEDIUM_HIGH_CONTRAST, "Medium High Contrast"},
    {CYCLES_BRIDGE_COLOR_LOOK_FILMIC_MEDIUM_CONTRAST, "Medium Contrast"},
    {CYCLES_BRIDGE_COLOR_LOOK_FILMIC_MEDIUM_LOW_CONTRAST, "Medium Low Contrast"},
    {CYCLES_BRIDGE_COLOR_LOOK_FILMIC_LOW_CONTRAST, "Low Contrast"},
    {CYCLES_BRIDGE_COLOR_LOOK_FILMIC_VERY_LOW_CONTRAST, "Very Low Contrast"},
}};

constexpr std::array<LookDefinition, 9> kFalseColorLooks = {{
    {CYCLES_BRIDGE_COLOR_LOOK_FALSE_COLOR_PUNCHY, "False Color - Punchy"},
    {CYCLES_BRIDGE_COLOR_LOOK_FALSE_COLOR_VERY_HIGH_CONTRAST, "False Color - Very High Contrast"},
    {CYCLES_BRIDGE_COLOR_LOOK_FALSE_COLOR_HIGH_CONTRAST, "False Color - High Contrast"},
    {CYCLES_BRIDGE_COLOR_LOOK_FALSE_COLOR_MEDIUM_HIGH_CONTRAST, "False Color - Medium High Contrast"},
    {CYCLES_BRIDGE_COLOR_LOOK_FALSE_COLOR_BASE_CONTRAST, "False Color - Base Contrast"},
    {CYCLES_BRIDGE_COLOR_LOOK_FALSE_COLOR_MEDIUM_LOW_CONTRAST, "False Color - Medium Low Contrast"},
    {CYCLES_BRIDGE_COLOR_LOOK_FALSE_COLOR_LOW_CONTRAST, "False Color - Low Contrast"},
    {CYCLES_BRIDGE_COLOR_LOOK_FALSE_COLOR_VERY_LOW_CONTRAST, "False Color - Very Low Contrast"},
    {CYCLES_BRIDGE_COLOR_LOOK_FALSE_COLOR_GREYSCALE, "False Color - Greyscale"},
}};

constexpr std::array<LookDefinition, 2> kAcesLooks = {{
    {CYCLES_BRIDGE_COLOR_LOOK_ACES_1_3_GAMUT_COMPRESSION, "ACES 1.3 - Reference Gamut Compression"},
    {CYCLES_BRIDGE_COLOR_LOOK_ACES_2_GAMUT_COMPRESSION, "ACES 2.0 - Reference Gamut Compression"},
}};

constexpr std::array<WorkingSpaceDefinition, 3> kWorkingSpaces = {{
    {CYCLES_BRIDGE_WORKING_SPACE_LINEAR_REC709, "Linear Rec.709"},
    {CYCLES_BRIDGE_WORKING_SPACE_LINEAR_REC2020, "Linear Rec.2020"},
    {CYCLES_BRIDGE_WORKING_SPACE_ACESCG, "ACEScg"},
}};

std::string wide_to_utf8(const std::wstring& value) {
    if (value.empty()) {
        return {};
    }
    const int size = WideCharToMultiByte(
        CP_UTF8, 0, value.data(), static_cast<int>(value.size()), nullptr, 0, nullptr, nullptr);
    if (size <= 0) {
        throw std::runtime_error("failed to convert the native module path to UTF-8");
    }
    std::string result(static_cast<std::size_t>(size), '\0');
    WideCharToMultiByte(
        CP_UTF8,
        0,
        value.data(),
        static_cast<int>(value.size()),
        result.data(),
        size,
        nullptr,
        nullptr);
    return result;
}

void color_module_anchor() {}

std::filesystem::path color_config_path() {
    HMODULE module = nullptr;
    if (!GetModuleHandleExW(
            GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
            reinterpret_cast<LPCWSTR>(&color_module_anchor),
            &module)) {
        throw std::runtime_error("failed to locate cyclesrenderer_native.dll");
    }

    std::wstring path(32768, L'\0');
    const DWORD length = GetModuleFileNameW(module, path.data(), static_cast<DWORD>(path.size()));
    if (length == 0 || length >= path.size()) {
        throw std::runtime_error("failed to read the native module path");
    }
    path.resize(length);
    return std::filesystem::path(path).parent_path() / "color" / "ocio" / "config.ocio";
}

const char* display_name(std::uint32_t display_device) {
    for (const DisplayDefinition& display : kDisplays) {
        if (display.id == display_device) {
            return display.name;
        }
    }
    return nullptr;
}

const char* view_name(std::uint32_t display_device, std::uint32_t view_transform) {
    for (const ViewDefinition& view : kOcioViews) {
        if (view.display == display_device && view.id == view_transform) {
            return view.name;
        }
    }
    return nullptr;
}

const char* look_name(std::uint32_t color_look) {
    if (color_look == CYCLES_BRIDGE_COLOR_LOOK_NONE) {
        return nullptr;
    }
    for (const LookDefinition& look : kAgxLooks) {
        if (look.id == color_look) {
            return look.name;
        }
    }
    for (const LookDefinition& look : kFilmicLooks) {
        if (look.id == color_look) {
            return look.name;
        }
    }
    for (const LookDefinition& look : kFalseColorLooks) {
        if (look.id == color_look) {
            return look.name;
        }
    }
    for (const LookDefinition& look : kAcesLooks) {
        if (look.id == color_look) {
            return look.name;
        }
    }
    return nullptr;
}

bool look_compatible(std::uint32_t view_transform, std::uint32_t color_look) {
    if (color_look == CYCLES_BRIDGE_COLOR_LOOK_NONE) {
        return true;
    }
    if (color_look <= CYCLES_BRIDGE_COLOR_LOOK_AGX_GREYSCALE) {
        return view_transform == CYCLES_BRIDGE_VIEW_TRANSFORM_AGX
            || view_transform == CYCLES_BRIDGE_VIEW_TRANSFORM_AGX_HDR_1000;
    }
    if (color_look <= CYCLES_BRIDGE_COLOR_LOOK_FILMIC_VERY_LOW_CONTRAST) {
        return view_transform == CYCLES_BRIDGE_VIEW_TRANSFORM_FILMIC
            || view_transform == CYCLES_BRIDGE_VIEW_TRANSFORM_FILMIC_LOG;
    }
    if (color_look <= CYCLES_BRIDGE_COLOR_LOOK_FALSE_COLOR_GREYSCALE) {
        return view_transform == CYCLES_BRIDGE_VIEW_TRANSFORM_FALSE_COLOR;
    }
    if (color_look == CYCLES_BRIDGE_COLOR_LOOK_ACES_1_3_GAMUT_COMPRESSION) {
        return view_transform == CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_1_3
            || (view_transform >= CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_1_3_HDR_1000
                && view_transform <= CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_1_3_HDR_4000);
    }
    return color_look == CYCLES_BRIDGE_COLOR_LOOK_ACES_2_GAMUT_COMPRESSION
        && (view_transform == CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_2
            || (view_transform >= CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_2_HDR_500
                && view_transform <= CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_2_HDR_4000));
}

const char* working_space_name(std::uint32_t working_space) {
    for (const WorkingSpaceDefinition& definition : kWorkingSpaces) {
        if (definition.id == working_space) {
            return definition.name;
        }
    }
    return nullptr;
}

std::uint64_t pipeline_key(
    std::uint32_t display_device,
    std::uint32_t working_space,
    std::uint32_t view_transform,
    std::uint32_t color_look) {
    return (static_cast<std::uint64_t>(display_device) << 56U)
        | (static_cast<std::uint64_t>(working_space) << 48U)
        | (static_cast<std::uint64_t>(view_transform) << 32U)
        | color_look;
}

float unshape(std::uint32_t index) {
    const float unit = static_cast<float>(index) / static_cast<float>(kLutEdgeLength - 1U);
    const float exponent = kShaperLog2Min + unit * (kShaperLog2Max - kShaperLog2Min);
    return std::exp2(exponent) - kShaperEpsilon;
}

}  // namespace

class ColorManagement::Impl final {
 public:
    Impl() {
        transform_mask_ = (1U << CYCLES_BRIDGE_VIEW_TRANSFORM_STANDARD)
            | (1U << CYCLES_BRIDGE_VIEW_TRANSFORM_RAW);
        try {
            config_path_ = color_config_path();
            config_ = OCIO::Config::CreateFromFile(wide_to_utf8(config_path_.wstring()).c_str());
            for (const WorkingSpaceDefinition& working_space : kWorkingSpaces) {
                for (const ViewDefinition& view : kOcioViews) {
                    if (view.id == CYCLES_BRIDGE_VIEW_TRANSFORM_RAW) {
                        transform_mask_ |= 1U << view.id;
                        continue;
                    }
                    build_processor(
                        view.display,
                        working_space.id,
                        view.id,
                        CYCLES_BRIDGE_COLOR_LOOK_NONE);
                    transform_mask_ |= 1U << view.id;
                    const auto build_compatible = [&](const auto& looks) {
                        for (const LookDefinition& look : looks) {
                            if (look_compatible(view.id, look.id)) {
                                build_processor(
                                    view.display,
                                    working_space.id,
                                    view.id,
                                    look.id);
                            }
                        }
                    };
                    build_compatible(kAgxLooks);
                    build_compatible(kFilmicLooks);
                    build_compatible(kFalseColorLooks);
                    build_compatible(kAcesLooks);
                }
            }
            std::string activation_error;
            if (!activate_working_space(
                    CYCLES_BRIDGE_WORKING_SPACE_LINEAR_REC709,
                    activation_error)) {
                throw std::runtime_error(activation_error);
            }
            state_ = CYCLES_BRIDGE_COLOR_CONFIG_READY;
        } catch (const std::exception& exception) {
            state_ = CYCLES_BRIDGE_COLOR_CONFIG_ERROR;
            error_ = exception.what();
        } catch (...) {
            state_ = CYCLES_BRIDGE_COLOR_CONFIG_ERROR;
            error_ = "unknown OpenColorIO initialization failure";
        }
    }

    [[nodiscard]] std::uint32_t state() const {
        return state_;
    }

    [[nodiscard]] std::uint32_t transform_mask() const {
        return transform_mask_;
    }

    [[nodiscard]] std::string info() const {
        std::ostringstream output;
        output << "state=";
        if (state_ == CYCLES_BRIDGE_COLOR_CONFIG_READY) {
            output << "ready";
        } else if (state_ == CYCLES_BRIDGE_COLOR_CONFIG_ERROR) {
            output << "error";
        } else {
            output << "unavailable";
        }
        output << ";config=" << wide_to_utf8(config_path_.wstring())
               << ";displays=6;edge=" << kLutEdgeLength
               << ";working=" << working_space_name(active_working_space_.load())
               << ";mask=0x" << std::hex << transform_mask_;
        if (!error_.empty()) {
            output << ";error=" << error_;
        }
        return output.str();
    }

    bool activate_working_space(
        std::uint32_t working_space,
        std::string& error) {
        const char* name = working_space_name(working_space);
        if (name == nullptr) {
            error = "unknown scene-linear working space";
            return false;
        }
        try {
            std::lock_guard lock(activation_mutex_);
            OCIO::ConfigRcPtr activated = config_->createEditableCopy();
            activated->setRole(OCIO::ROLE_SCENE_LINEAR, name);
            activated->validate();
            OCIO::SetCurrentConfig(activated);
            active_working_space_.store(working_space);
            return true;
        } catch (const std::exception& exception) {
            error = exception.what();
            return false;
        } catch (...) {
            error = "unknown OpenColorIO working-space activation failure";
            return false;
        }
    }

    bool query_lut(
        std::uint32_t display_device,
        std::uint32_t view_transform,
        std::uint32_t color_look,
        std::uint32_t working_space,
        CyclesBridgeColorLutDescriptor& descriptor,
        float* rgba,
        std::uint64_t rgba_capacity,
        std::string& error) const {
        if (!look_compatible(view_transform, color_look)) {
            color_look = CYCLES_BRIDGE_COLOR_LOOK_NONE;
        }
        const std::uint64_t key = pipeline_key(
            display_device, working_space, view_transform, color_look);
        const auto processor = processors_.find(key);
        if (processor == processors_.end()) {
            error = display_name(display_device) == nullptr
                ? "the requested display device is unavailable"
                : view_name(display_device, view_transform) == nullptr
                ? "the requested view transform does not use an OCIO LUT"
                : working_space_name(working_space) == nullptr
                    ? "the requested working space is unavailable"
                : (color_look != CYCLES_BRIDGE_COLOR_LOOK_NONE
                        && look_name(color_look) == nullptr)
                    ? "the requested OCIO look is unavailable"
                    : "the requested OCIO view transform is unavailable";
            return false;
        }

        descriptor = {};
        descriptor.struct_size = sizeof(descriptor);
        descriptor.struct_version = 1;
        descriptor.view_transform = view_transform;
        descriptor.edge_length = kLutEdgeLength;
        descriptor.width = kLutEdgeLength * kLutEdgeLength;
        descriptor.height = kLutEdgeLength;
        descriptor.pixel_format = CYCLES_BRIDGE_PIXEL_FORMAT_RGBA32_FLOAT;
        descriptor.flags = CYCLES_BRIDGE_COLOR_LUT_SCENE_LINEAR_INPUT
            | CYCLES_BRIDGE_COLOR_LUT_DISPLAY_REFERRED_OUTPUT
            | CYCLES_BRIDGE_COLOR_LUT_FLATTENED_RED_MAJOR;
        descriptor.pixel_byte_count = static_cast<std::uint64_t>(descriptor.width)
            * descriptor.height * 4U * sizeof(float);
        descriptor.shaper_log2_min = kShaperLog2Min;
        descriptor.shaper_log2_max = kShaperLog2Max;
        descriptor.shaper_epsilon = kShaperEpsilon;
        descriptor.interpolation = CYCLES_BRIDGE_COLOR_LUT_INTERPOLATION_TRILINEAR;
        descriptor.color_look = color_look;
        descriptor.working_space = working_space;
        descriptor.display_device = display_device;

        if (rgba == nullptr) {
            return rgba_capacity == 0U;
        }
        if (rgba_capacity < descriptor.pixel_byte_count) {
            error = "the color LUT output buffer is too small";
            return false;
        }

        std::lock_guard lock(cache_mutex_);
        const std::vector<float>& pixels = cached_lut(key, processor->second);
        std::memcpy(rgba, pixels.data(), static_cast<std::size_t>(descriptor.pixel_byte_count));
        return true;
    }

 private:
    void build_processor(
        std::uint32_t display_device,
        std::uint32_t working_space,
        std::uint32_t view_transform,
        std::uint32_t color_look) {
        const char* display_name_value = display_name(display_device);
        const char* view = view_name(display_device, view_transform);
        const char* source_color_space = working_space_name(working_space);
        if (display_name_value == nullptr || view == nullptr || source_color_space == nullptr) {
            throw std::runtime_error("unknown OCIO color pipeline");
        }
        OCIO::GroupTransformRcPtr transforms = OCIO::GroupTransform::Create();
        bool bypass_display_look = false;
        if (color_look != CYCLES_BRIDGE_COLOR_LOOK_NONE) {
            const char* look = look_name(color_look);
            if (look == nullptr) {
                throw std::runtime_error("unknown OCIO look");
            }
            const char* look_output = OCIO::LookTransform::GetLooksResultColorSpace(
                config_, config_->getCurrentContext(), look);
            if (look_output == nullptr || look_output[0] == '\0') {
                throw std::runtime_error("OCIO look does not declare an output color space");
            }
            OCIO::LookTransformRcPtr look_transform = OCIO::LookTransform::Create();
            look_transform->setSrc(source_color_space);
            look_transform->setDst(look_output);
            look_transform->setLooks(look);
            transforms->appendTransform(look_transform);
            source_color_space = look_output;
            bypass_display_look = true;
        }
        OCIO::DisplayViewTransformRcPtr display = OCIO::DisplayViewTransform::Create();
        display->setSrc(source_color_space);
        display->setDisplay(display_name_value);
        display->setView(view);
        display->setLooksBypass(bypass_display_look);
        transforms->appendTransform(display);
        processors_.emplace(
            pipeline_key(display_device, working_space, view_transform, color_look),
            config_->getProcessor(transforms)->getDefaultCPUProcessor());
    }

    const std::vector<float>& cached_lut(
        std::uint64_t key,
        const OCIO::ConstCPUProcessorRcPtr& processor) const {
        const auto cached = lut_cache_.find(key);
        if (cached != lut_cache_.end()) {
            return cached->second;
        }

        const std::size_t pixel_count = static_cast<std::size_t>(kLutEdgeLength)
            * kLutEdgeLength * kLutEdgeLength;
        std::vector<float> pixels(pixel_count * 4U, 1.0F);
        for (std::uint32_t green = 0; green < kLutEdgeLength; ++green) {
            for (std::uint32_t blue = 0; blue < kLutEdgeLength; ++blue) {
                for (std::uint32_t red = 0; red < kLutEdgeLength; ++red) {
                    const std::size_t pixel = static_cast<std::size_t>(green)
                            * kLutEdgeLength * kLutEdgeLength
                        + static_cast<std::size_t>(blue) * kLutEdgeLength
                        + red;
                    pixels[pixel * 4U] = unshape(red);
                    pixels[pixel * 4U + 1U] = unshape(green);
                    pixels[pixel * 4U + 2U] = unshape(blue);
                }
            }
        }
        OCIO::PackedImageDesc image(
            pixels.data(), static_cast<long>(pixel_count), 1L, 4L);
        processor->apply(image);
        for (const float value : pixels) {
            if (!std::isfinite(value)) {
                throw std::runtime_error("OpenColorIO generated a non-finite LUT value");
            }
        }
        return lut_cache_.emplace(key, std::move(pixels)).first->second;
    }

    std::filesystem::path config_path_;
    OCIO::ConstConfigRcPtr config_;
    std::unordered_map<std::uint64_t, OCIO::ConstCPUProcessorRcPtr> processors_;
    std::uint32_t state_ = CYCLES_BRIDGE_COLOR_CONFIG_UNAVAILABLE;
    std::uint32_t transform_mask_ = 0;
    std::string error_;
    std::mutex activation_mutex_;
    std::atomic<std::uint32_t> active_working_space_{
        CYCLES_BRIDGE_WORKING_SPACE_LINEAR_REC709};
    mutable std::mutex cache_mutex_;
    mutable std::unordered_map<std::uint64_t, std::vector<float>> lut_cache_;
};

ColorManagement::ColorManagement() : impl_(std::make_unique<Impl>()) {}

ColorManagement::~ColorManagement() = default;

std::uint32_t ColorManagement::state() const {
    return impl_->state();
}

std::uint32_t ColorManagement::transform_mask() const {
    return impl_->transform_mask();
}

std::uint32_t ColorManagement::lut_edge_length() const {
    return kLutEdgeLength;
}

std::string ColorManagement::info() const {
    return impl_->info();
}

bool ColorManagement::activate_working_space(
    std::uint32_t working_space,
    std::string& error) {
    return impl_->activate_working_space(working_space, error);
}

bool ColorManagement::query_lut(
    std::uint32_t display_device,
    std::uint32_t view_transform,
    std::uint32_t color_look,
    std::uint32_t working_space,
    CyclesBridgeColorLutDescriptor& descriptor,
    float* rgba,
    std::uint64_t rgba_capacity,
    std::string& error) const {
    return impl_->query_lut(
        display_device, view_transform, color_look, working_space,
        descriptor, rgba, rgba_capacity, error);
}
