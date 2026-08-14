#version 330

uniform sampler2D InSampler;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    const vec2 quarterCell = vec2(1.0 / 256.0, 1.0 / 144.0);
    vec3 color = max(texture(InSampler, texCoord + vec2(-quarterCell.x, -quarterCell.y)).rgb, vec3(0.0));
    color += max(texture(InSampler, texCoord + vec2(quarterCell.x, -quarterCell.y)).rgb, vec3(0.0));
    color += max(texture(InSampler, texCoord + vec2(-quarterCell.x, quarterCell.y)).rgb, vec3(0.0));
    color += max(texture(InSampler, texCoord + vec2(quarterCell.x, quarterCell.y)).rgb, vec3(0.0));
    fragColor = vec4(color * 0.25, 1.0);
}
