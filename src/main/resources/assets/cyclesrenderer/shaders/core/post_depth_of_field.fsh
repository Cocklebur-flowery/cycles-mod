#version 330

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;

layout(std140) uniform PostDepthOfField {
    vec4 LensParams;
    vec4 ImageParams;
    vec4 ApertureParams;
};

in vec2 texCoord;
out vec4 fragColor;

const vec2 POISSON[16] = vec2[](
    vec2(-0.94201624, -0.39906216),
    vec2(0.94558609, -0.76890725),
    vec2(-0.09418410, -0.92938870),
    vec2(0.34495938, 0.29387760),
    vec2(-0.91588581, 0.45771432),
    vec2(-0.81544232, -0.87912464),
    vec2(-0.38277543, 0.27676845),
    vec2(0.97484398, 0.75648379),
    vec2(0.44323325, -0.97511554),
    vec2(0.53742981, -0.47373420),
    vec2(-0.26496911, -0.41893023),
    vec2(0.79197514, 0.19090188),
    vec2(-0.24188840, 0.99706507),
    vec2(-0.81409955, 0.91437590),
    vec2(0.19984126, 0.78641367),
    vec2(0.14383161, -0.14100790)
);

float sanitizedDepth(vec2 uv) {
    float value = texture(DepthSampler, uv).r;
    if (isnan(value) || isinf(value) || value <= 0.0) {
        return ImageParams.w;
    }
    return min(value, ImageParams.w);
}

float signedCocRadius(float depth) {
    float focus = LensParams.x;
    float focal = min(LensParams.y, focus * 0.95);
    float denominator = max(LensParams.z * depth * (focus - focal) * LensParams.w, 1.0e-7);
    float diameterPixels = ImageParams.x * focal * focal * (depth - focus) / denominator;
    return clamp(diameterPixels * 0.5, -ImageParams.z, ImageParams.z);
}

vec2 apertureOffset(vec2 point) {
    float rotation = ApertureParams.y;
    mat2 transform = mat2(cos(rotation), -sin(rotation), sin(rotation), cos(rotation));
    vec2 result = transform * point;
    float blades = ApertureParams.x;
    if (blades >= 3.0) {
        float angle = atan(result.y, result.x);
        float sector = 6.28318530718 / blades;
        float local = mod(angle + 0.5 * sector, sector) - 0.5 * sector;
        float polygon = cos(3.14159265359 / blades) / max(cos(local), 1.0e-3);
        result *= polygon;
    }
    result.x *= ApertureParams.z;
    return result;
}

void main() {
    float centerDepth = sanitizedDepth(texCoord);
    float centerCoc = signedCocRadius(centerDepth);
    float radius = abs(centerCoc);
    vec4 center = texture(InSampler, texCoord);
    if (radius < 0.5) {
        fragColor = center;
        return;
    }

    vec2 texel = vec2(1.0 / ImageParams.x, 1.0 / ImageParams.y);
    vec4 accumulated = center;
    float totalWeight = 1.0;
    for (int index = 0; index < 16; ++index) {
        vec2 offset = apertureOffset(POISSON[index]) * radius * texel;
        vec2 sampleUv = clamp(texCoord + offset, vec2(0.0), vec2(1.0));
        float sampleDepth = sanitizedDepth(sampleUv);
        float sampleCoc = signedCocRadius(sampleDepth);
        float coverage = smoothstep(0.0, 1.0, abs(sampleCoc) / max(radius, 0.5));
        float foregroundCenter = centerCoc < 0.0 ? 1.0 : 0.0;
        float crossesFocus = sampleCoc * centerCoc < 0.0 ? 1.0 : 0.0;
        float rejection = foregroundCenter * crossesFocus * 0.9;
        float weight = max(0.05, coverage * (1.0 - rejection));
        accumulated += texture(InSampler, sampleUv) * weight;
        totalWeight += weight;
    }
    fragColor = accumulated / totalWeight;
}
