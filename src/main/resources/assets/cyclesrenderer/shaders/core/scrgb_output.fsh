#version 330

uniform sampler2D InSampler;

layout(std140) uniform HdrOutput {
    vec4 OutputParams;
};

in vec2 texCoord;
out vec4 fragColor;

vec3 srgbToLinear(vec3 value) {
    bvec3 linearRange = lessThanEqual(value, vec3(0.04045));
    vec3 low = value / 12.92;
    vec3 high = pow(max((value + 0.055) / 1.055, vec3(0.0)), vec3(2.4));
    return mix(high, low, linearRange);
}

void main() {
    vec4 source = texture(InSampler, texCoord);
    vec3 scRgb = srgbToLinear(max(source.rgb, vec3(0.0))) * OutputParams.x;
    fragColor = vec4(scRgb, source.a);
}
