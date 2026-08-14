#version 330

uniform sampler2D InSampler;

in vec2 texCoord;
out vec4 fragColor;

vec3 srgbToLinear(vec3 value) {
    vec3 magnitude = abs(value);
    bvec3 linearRange = lessThanEqual(magnitude, vec3(0.04045));
    vec3 low = magnitude / 12.92;
    vec3 high = pow((magnitude + 0.055) / 1.055, vec3(2.4));
    return sign(value) * mix(high, low, linearRange);
}

vec3 linearToSrgb(vec3 value) {
    bvec3 linearRange = lessThanEqual(value, vec3(0.0031308));
    vec3 low = value * 12.92;
    vec3 high = 1.055 * pow(max(value, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055;
    return mix(high, low, linearRange);
}

vec3 compressHdrPeak(vec3 value) {
    vec3 positive = max(value, vec3(0.0));
    float peak = max(positive.r, max(positive.g, positive.b));
    if (peak <= 1.0) {
        return positive;
    }
    float mappedPeak = peak / (peak + 0.05);
    return positive * (mappedPeak / peak);
}

void main() {
    vec4 source = texture(InSampler, texCoord);
    vec3 linearSdr = compressHdrPeak(srgbToLinear(source.rgb));
    fragColor = vec4(linearToSrgb(linearSdr), clamp(source.a, 0.0, 1.0));
}
