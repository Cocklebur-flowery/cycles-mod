#version 330

uniform sampler2D DepthSampler;

layout(std140) uniform DepthReprojection {
    vec4 SourcePositionDelta;
    vec4 SourceRotation;
    vec4 SourceProjection;
    vec4 SourceClipImage;
    vec4 TargetRotation;
    vec4 TargetProjection;
    vec4 TargetClipImage;
};

flat out ivec2 sourcePixel;
flat out float targetAxialDepth;
flat out float validProjection;

vec3 rotateQuaternion(vec4 quaternion, vec3 value) {
    vec3 twiceCross = 2.0 * cross(quaternion.xyz, value);
    return value + quaternion.w * twiceCross + cross(quaternion.xyz, twiceCross);
}

vec3 inverseRotateQuaternion(vec4 quaternion, vec3 value) {
    return rotateQuaternion(vec4(-quaternion.xyz, quaternion.w), value);
}

void rejectProjection() {
    gl_Position = vec4(2.0, 2.0, 0.0, 1.0);
    gl_PointSize = 1.0;
    targetAxialDepth = 0.0;
    validProjection = 0.0;
}

void main() {
    int sourceWidth = int(SourceClipImage.z + 0.5);
    int sourceHeight = int(SourceClipImage.w + 0.5);
    sourcePixel = ivec2(gl_VertexID % sourceWidth, gl_VertexID / sourceWidth);
    float sourceDepth = texelFetch(DepthSampler, sourcePixel, 0).r;
    if (isnan(sourceDepth) || isinf(sourceDepth)
            || sourceDepth < SourceClipImage.x || sourceDepth > SourceClipImage.y) {
        rejectProjection();
        return;
    }

    vec2 sourceUv = (vec2(sourcePixel) + vec2(0.5)) / SourceClipImage.zw;
    float sourceAspect = SourceProjection.y;
    float sourceFitAspect = max(sourceAspect, 1.0 / sourceAspect);
    float sourceRadiusX = sourceAspect >= 1.0 ? sourceAspect : 1.0;
    float sourceRadiusY = sourceAspect >= 1.0 ? 1.0 : 1.0 / sourceAspect;
    vec2 sourcePlane = vec2(
        (2.0 * sourceUv.x - 1.0) * sourceRadiusX
            + 2.0 * sourceFitAspect * SourceProjection.z,
        (2.0 * sourceUv.y - 1.0) * sourceRadiusY
            + 2.0 * sourceFitAspect * SourceProjection.w);
    float sourceTangent = tan(SourceProjection.x * 0.5);
    vec3 sourceLocal = vec3(
        sourcePlane.x * sourceTangent / sourceRadiusY * sourceDepth,
        sourcePlane.y * sourceTangent / sourceRadiusY * sourceDepth,
        -sourceDepth);

    vec3 targetLocal = inverseRotateQuaternion(
        TargetRotation,
        SourcePositionDelta.xyz + rotateQuaternion(SourceRotation, sourceLocal));
    float targetDepth = -targetLocal.z;
    if (isnan(targetDepth) || isinf(targetDepth)
            || targetDepth < TargetClipImage.x || targetDepth > TargetClipImage.y) {
        rejectProjection();
        return;
    }

    float targetAspect = TargetProjection.y;
    float targetFitAspect = max(targetAspect, 1.0 / targetAspect);
    float targetRadiusX = targetAspect >= 1.0 ? targetAspect : 1.0;
    float targetRadiusY = targetAspect >= 1.0 ? 1.0 : 1.0 / targetAspect;
    float targetTangent = tan(TargetProjection.x * 0.5);
    vec2 targetPlane = targetLocal.xy * targetRadiusY
        / (targetDepth * targetTangent);
    vec2 targetUv = vec2(
        ((targetPlane.x - 2.0 * targetFitAspect * TargetProjection.z)
            / targetRadiusX + 1.0) * 0.5,
        ((targetPlane.y - 2.0 * targetFitAspect * TargetProjection.w)
            / targetRadiusY + 1.0) * 0.5);
    if (any(isnan(targetUv)) || any(isinf(targetUv))
            || any(lessThan(targetUv, vec2(0.0)))
            || any(greaterThanEqual(targetUv, vec2(1.0)))) {
        rejectProjection();
        return;
    }

    float reversedDepth = clamp(
        (TargetClipImage.y - targetDepth)
            / (TargetClipImage.y - TargetClipImage.x),
        0.0,
        1.0);
    gl_Position = vec4(targetUv * 2.0 - vec2(1.0), reversedDepth, 1.0);
    gl_PointSize = 1.0;
    targetAxialDepth = targetDepth;
    validProjection = 1.0;
}
