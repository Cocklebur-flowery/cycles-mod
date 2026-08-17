#version 330

uniform sampler2D InSampler;

flat in ivec2 sourcePixel;
flat in float targetAxialDepth;
flat in float validProjection;

layout(location = 0) out vec4 reprojectedColor;
layout(location = 1) out float reprojectedDepth;

void main() {
    if (validProjection < 0.5) {
        discard;
    }
    reprojectedColor = texelFetch(InSampler, sourcePixel, 0);
    reprojectedDepth = targetAxialDepth;
}
