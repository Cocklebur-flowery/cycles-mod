#version 330

uniform sampler2D InSampler;

layout(std140) uniform CyclesDisplay {
    vec4 DisplayParams;
    ivec4 DisplayModes;
};

in vec2 texCoord;
out vec4 fragColor;

vec3 linearToSrgb(vec3 value) {
    bvec3 linearRange = lessThanEqual(value, vec3(0.0031308));
    vec3 low = value * 12.92;
    vec3 high = 1.055 * pow(max(value, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055;
    return mix(high, low, linearRange);
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
        display *= DisplayParams.x;
        if (DisplayModes.y != 1) {
            display = linearToSrgb(display);
        }
        display = pow(max(display, vec3(0.0)), vec3(DisplayParams.y));
    }

    float alpha = activePass == 0 ? source.a : 1.0;
    fragColor = vec4(display, alpha);
}
