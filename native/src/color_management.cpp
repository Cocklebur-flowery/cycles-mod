#include "color_management.h"

#include <Windows.h>

#include <OpenColorIO/OpenColorIO.h>

#include <array>
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

constexpr std::uint32_t kLutEdgeLength = 65;
constexpr float kShaperLog2Min = -10.0F;
constexpr float kShaperLog2Max = 16.0F;
constexpr float kShaperEpsilon = 1.0F / 1024.0F;

struct ViewDefinition final {
    std::uint32_t id;
    const char* name;
};

constexpr std::array<ViewDefinition, 3> kOcioViews = {{
    {CYCLES_BRIDGE_VIEW_TRANSFORM_AGX, "AgX"},
    {CYCLES_BRIDGE_VIEW_TRANSFORM_KHRONOS_PBR_NEUTRAL, "Khronos PBR Neutral"},
    {CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_2, "ACES 2.0"},
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

const char* view_name(std::uint32_t view_transform) {
    for (const ViewDefinition& view : kOcioViews) {
        if (view.id == view_transform) {
            return view.name;
        }
    }
    return nullptr;
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
            for (const ViewDefinition& view : kOcioViews) {
                processors_.emplace(
                    view.id,
                    config_->getProcessor(
                        OCIO::ROLE_SCENE_LINEAR,
                        "sRGB",
                        view.name,
                        OCIO::TRANSFORM_DIR_FORWARD)->getDefaultCPUProcessor());
                transform_mask_ |= 1U << view.id;
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
               << ";display=sRGB;edge=" << kLutEdgeLength
               << ";mask=0x" << std::hex << transform_mask_;
        if (!error_.empty()) {
            output << ";error=" << error_;
        }
        return output.str();
    }

    bool query_lut(
        std::uint32_t view_transform,
        CyclesBridgeColorLutDescriptor& descriptor,
        float* rgba,
        std::uint64_t rgba_capacity,
        std::string& error) const {
        const auto processor = processors_.find(view_transform);
        if (processor == processors_.end()) {
            error = view_name(view_transform) == nullptr
                ? "the requested view transform does not use an OCIO LUT"
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

        if (rgba == nullptr) {
            return rgba_capacity == 0U;
        }
        if (rgba_capacity < descriptor.pixel_byte_count) {
            error = "the color LUT output buffer is too small";
            return false;
        }

        std::lock_guard lock(cache_mutex_);
        const std::vector<float>& pixels = cached_lut(view_transform, processor->second);
        std::memcpy(rgba, pixels.data(), static_cast<std::size_t>(descriptor.pixel_byte_count));
        return true;
    }

 private:
    const std::vector<float>& cached_lut(
        std::uint32_t view_transform,
        const OCIO::ConstCPUProcessorRcPtr& processor) const {
        const auto cached = lut_cache_.find(view_transform);
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
        return lut_cache_.emplace(view_transform, std::move(pixels)).first->second;
    }

    std::filesystem::path config_path_;
    OCIO::ConstConfigRcPtr config_;
    std::unordered_map<std::uint32_t, OCIO::ConstCPUProcessorRcPtr> processors_;
    std::uint32_t state_ = CYCLES_BRIDGE_COLOR_CONFIG_UNAVAILABLE;
    std::uint32_t transform_mask_ = 0;
    std::string error_;
    mutable std::mutex cache_mutex_;
    mutable std::unordered_map<std::uint32_t, std::vector<float>> lut_cache_;
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

bool ColorManagement::query_lut(
    std::uint32_t view_transform,
    CyclesBridgeColorLutDescriptor& descriptor,
    float* rgba,
    std::uint64_t rgba_capacity,
    std::string& error) const {
    return impl_->query_lut(view_transform, descriptor, rgba, rgba_capacity, error);
}
