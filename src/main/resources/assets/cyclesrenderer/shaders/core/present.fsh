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
    vec4 HdrParams;
};

in vec2 texCoord;
out vec4 fragColor;

vec3 linearToSrgb(vec3 value) {
    bvec3 linearRange = lessThanEqual(value, vec3(0.0031308));
    vec3 low = value * 12.92;
    vec3 high = 1.055 * pow(max(value, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055;
    return mix(high, low, linearRange);
}

vec3 pqToLinear(vec3 value) {
    const float m1 = 0.1593017578125;
    const float m2 = 78.84375;
    const float c1 = 0.8359375;
    const float c2 = 18.8515625;
    const float c3 = 18.6875;
    vec3 encoded = pow(clamp(value, vec3(0.0), vec3(1.0)), vec3(1.0 / m2));
    vec3 numerator = max(encoded - vec3(c1), vec3(0.0));
    vec3 denominator = max(vec3(c2) - vec3(c3) * encoded, vec3(1.0e-6));
    return pow(numerator / denominator, vec3(1.0 / m1));
}

vec3 rec2020ToSrgb(vec3 value) {
    return vec3(
        dot(vec3(1.660491, -0.587641, -0.072850), value),
        dot(vec3(-0.124550, 1.132900, -0.008349), value),
        dot(vec3(-0.018151, -0.100579, 1.118730), value));
}

vec3 linearToSignedSrgb(vec3 value) {
    vec3 magnitude = abs(value);
    bvec3 linearRange = lessThanEqual(magnitude, vec3(0.0031308));
    vec3 low = magnitude * 12.92;
    vec3 high = 1.055 * pow(magnitude, vec3(1.0 / 2.4)) - 0.055;
    return sign(value) * mix(high, low, linearRange);
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
        if (DisplayModes.y != 1 && DisplayModes.z == 0) {
            display = pow(max(display, vec3(0.0)), vec3(DisplayParams.y));
        }
    }

    if (DisplayModes.z == 1) {
        vec3 absoluteRec2020 = pqToLinear(display);
        vec3 relativeSrgb = rec2020ToSrgb(absoluteRec2020) * HdrParams.x;
        display = linearToSignedSrgb(relativeSrgb);
    }

    float alpha = activePass == 0 ? source.a : 1.0;
    fragColor = vec4(display, alpha);
}
