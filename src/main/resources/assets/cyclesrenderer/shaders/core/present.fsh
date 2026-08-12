#version 330

uniform sampler2D InSampler;
uniform sampler2D ColorLutSampler;

layout(std140) uniform CyclesDisplay {
    vec4 DisplayParams;
    ivec4 DisplayModes;
    vec4 ColorLutParams;
    vec4 WhiteBalanceRow0;
    vec4 WhiteBalanceRow1;
    vec4 WhiteBalanceRow2;
};

in vec2 texCoord;
out vec4 fragColor;

vec3 linearToSrgb(vec3 value) {
    bvec3 linearRange = lessThanEqual(value, vec3(0.0031308));
    vec3 low = value * 12.92;
    vec3 high = 1.055 * pow(max(value, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055;
    return mix(high, low, linearRange);
}

vec3 fetchColorLut(ivec3 index, int edge) {
    ivec2 packed = ivec2(index.x + index.z * edge, index.y);
    return texelFetch(ColorLutSampler, packed, 0).rgb;
}

vec3 applyColorLut(vec3 value) {
    int edge = int(ColorLutParams.x + 0.5);
    vec3 shaped = clamp(
        (log2(max(value, vec3(0.0)) + vec3(ColorLutParams.w))
            - vec3(ColorLutParams.y)) * ColorLutParams.z,
        vec3(0.0),
        vec3(1.0));
    vec3 coordinate = shaped * float(edge - 1);
    ivec3 lower = ivec3(floor(coordinate));
    ivec3 upper = min(lower + ivec3(1), ivec3(edge - 1));
    vec3 blend = fract(coordinate);

    vec3 c000 = fetchColorLut(ivec3(lower.x, lower.y, lower.z), edge);
    vec3 c100 = fetchColorLut(ivec3(upper.x, lower.y, lower.z), edge);
    vec3 c010 = fetchColorLut(ivec3(lower.x, upper.y, lower.z), edge);
    vec3 c110 = fetchColorLut(ivec3(upper.x, upper.y, lower.z), edge);
    vec3 c001 = fetchColorLut(ivec3(lower.x, lower.y, upper.z), edge);
    vec3 c101 = fetchColorLut(ivec3(upper.x, lower.y, upper.z), edge);
    vec3 c011 = fetchColorLut(ivec3(lower.x, upper.y, upper.z), edge);
    vec3 c111 = fetchColorLut(ivec3(upper.x, upper.y, upper.z), edge);
    vec3 lowBlue = mix(mix(c000, c100, blend.x), mix(c010, c110, blend.x), blend.y);
    vec3 highBlue = mix(mix(c001, c101, blend.x), mix(c011, c111, blend.x), blend.y);
    return mix(lowBlue, highBlue, blend.z);
}

void main() {
    vec4 source = texture(InSampler, texCoord);
    vec3 display = source.rgb;
    int activePass = DisplayModes.x;

    if (activePass == 1) {
        display = vec3(1.0) - exp(-max(display, vec3(0.0)) * (8.0 / DisplayParams.z));
    } else if (activePass == 2) {
        display = display * 0.5 + 0.5;
    } else if (activePass == 6) {
        display /= DisplayParams.w;
    } else if (activePass != 5) {
        display = vec3(
            dot(WhiteBalanceRow0.xyz, display),
            dot(WhiteBalanceRow1.xyz, display),
            dot(WhiteBalanceRow2.xyz, display));
        display *= DisplayParams.x;
        if (DisplayModes.y != 1) {
            display = applyColorLut(display);
        }
        if (DisplayModes.y != 1) {
            display = pow(max(display, vec3(0.0)), vec3(DisplayParams.y));
        }
    }

    float alpha = activePass == 0 ? source.a : 1.0;
    fragColor = vec4(display, alpha);
}
