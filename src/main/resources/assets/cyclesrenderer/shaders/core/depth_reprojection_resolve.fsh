#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

bool validDepth(float depth) {
    return !isnan(depth) && !isinf(depth) && depth > 0.0;
}

#ifdef COVERAGE_REDUCTION

out vec2 coverageSum;

void main() {
    ivec2 sourceSize = textureSize(InSampler, 0);
    ivec2 sourceBase = ivec2(gl_FragCoord.xy) * 2;
    vec2 sum = vec2(0.0);
    for (int y = 0; y < 2; ++y) {
        for (int x = 0; x < 2; ++x) {
            ivec2 sourcePixel = sourceBase + ivec2(x, y);
            if (all(lessThan(sourcePixel, sourceSize))) {
#ifdef COVERAGE_SOURCE_DEPTH
                float valid = 0.0;
                for (int neighborY = -1; neighborY <= 1; ++neighborY) {
                    for (int neighborX = -1; neighborX <= 1; ++neighborX) {
                        ivec2 neighbor = sourcePixel + ivec2(neighborX, neighborY);
                        if (valid < 0.5
                                && all(greaterThanEqual(neighbor, ivec2(0)))
                                && all(lessThan(neighbor, sourceSize))
                                && validDepth(texelFetch(InSampler, neighbor, 0).r)) {
                            valid = 1.0;
                        }
                    }
                }
                sum += vec2(valid, 1.0);
#else
                sum += texelFetch(InSampler, sourcePixel, 0).rg;
#endif
            }
        }
    }
    coverageSum = sum;
}

#else

uniform sampler2D DepthSampler;
uniform sampler2D ReprojectedSampler;
uniform sampler2D ReprojectedDepthSampler;
uniform sampler2D ReprojectionCoverageSampler;

layout(location = 0) out vec4 resolvedColor;
layout(location = 1) out float resolvedDepth;

const float MIN_VALID_COVERAGE = 0.90;

void useOriginal(ivec2 targetPixel, ivec2 targetSize) {
    vec2 sourceUv = (vec2(targetPixel) + vec2(0.5)) / vec2(targetSize);
    resolvedColor = texture(InSampler, sourceUv);
    resolvedDepth = texture(DepthSampler, sourceUv).r;
}

void main() {
    ivec2 targetSize = textureSize(ReprojectedDepthSampler, 0);
    ivec2 targetPixel = clamp(
        ivec2(texCoord * vec2(targetSize)),
        ivec2(0),
        targetSize - ivec2(1));
    vec2 coverageCounts = texelFetch(ReprojectionCoverageSampler, ivec2(0), 0).rg;
    float coverage = coverageCounts.y > 0.0
        ? coverageCounts.x / coverageCounts.y : 0.0;
    if (coverage < MIN_VALID_COVERAGE) {
        useOriginal(targetPixel, targetSize);
        return;
    }

    float directDepth = texelFetch(ReprojectedDepthSampler, targetPixel, 0).r;
    if (validDepth(directDepth)) {
        resolvedColor = texelFetch(ReprojectedSampler, targetPixel, 0);
        resolvedDepth = directDepth;
        return;
    }

    ivec2 selectedPixel = ivec2(-1);
    float selectedDistance = 100.0;
    float selectedDepth = 0.0;
    for (int y = -1; y <= 1; ++y) {
        for (int x = -1; x <= 1; ++x) {
            ivec2 candidate = targetPixel + ivec2(x, y);
            if (any(lessThan(candidate, ivec2(0)))
                    || any(greaterThanEqual(candidate, targetSize))) {
                continue;
            }
            float candidateDepth = texelFetch(
                ReprojectedDepthSampler, candidate, 0).r;
            if (!validDepth(candidateDepth)) {
                continue;
            }
            float candidateDistance = float(x * x + y * y);
            if (candidateDistance < selectedDistance
                    || (candidateDistance == selectedDistance
                        && candidateDepth < selectedDepth)) {
                selectedPixel = candidate;
                selectedDistance = candidateDistance;
                selectedDepth = candidateDepth;
            }
        }
    }
    if (selectedPixel.x >= 0) {
        resolvedColor = texelFetch(ReprojectedSampler, selectedPixel, 0);
        resolvedDepth = selectedDepth;
        return;
    }
    useOriginal(targetPixel, targetSize);
}

#endif
