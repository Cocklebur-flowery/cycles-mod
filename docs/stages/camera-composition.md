# Camera composition: shift and safe areas

This stage completes the composition controls that remain after the panorama camera work. It adds
Blender-compatible camera shift to the native Cycles camera and Blender-style safe-area guides to
the Minecraft HUD. The two features deliberately have different ownership:

- Shift changes the ray projection and is part of the native render-settings ABI.
- Safe areas are construction guides. They are persisted in the client config and drawn only in the
  Java GUI layer; they never crop or alter rendered pixels.

## Upstream reference

The reference checkout used for this investigation is Blender v5.2.0 commit
`fbe6228777e7d9afefcd61a413844e790ae75db7`.

`intern/cycles/blender/camera.cpp` computes a sensor-fit aspect ratio and offsets a perspective
viewplane by:

```text
offset = 2 * aspectRatio * (shiftX, shiftY)
```

The unshifted perspective bounds are `(aspectRatio, 1)` for a landscape frame and
`(1, aspectRatio)` for a portrait frame. The bridge has no Blender viewport crop/zoom state, so a
full panoramic render starts with the Cycles unit viewplane `[0, 1] x [0, 1]` and applies the shift
directly to both bounds. This is the upstream full-render behavior; Blender's optional
`pano_aspectratio` correction only exists when a panorama is mapped into a camera viewport subset.

Blender stores shift at zero by default. The bridge accepts finite X/Y values in `[-10, 10]`, which
matches the exposed F9 control range and rejects invalid ABI input before it reaches Cycles.

## ABI contract

The device-phase diagnostics work consumed ABI 35 concurrently. This camera stage therefore uses
ABI 36 and only appends fields:

```text
CyclesBridgeRenderSettings
  camera_shift_x @ 360
  camera_shift_y @ 364
  size = 368

CyclesBridgeDiagnostics
  camera_shift_x @ 616
  camera_shift_y @ 620
  size = 624
```

Native and Java layout assertions must move together. A live viewplane-only update was verified to
stall the existing Cycles 5.2 session at sample zero, while the same shifted projection rendered
normally during initial session construction. Shift-only changes therefore request a Session
rebuild instead of an accumulation-only reset. The selected device and denoiser configuration are
preserved: DLSS remains allowed, is not forced to another denoiser, and its temporal history starts
fresh with the rebuilt projection.

## Safe-area contract

`DNA_scene_types.h` defines each safe-area value as a normalized X/Y margin multiplier. Blender's
defaults are retained exactly:

| Guide | X | Y |
| --- | ---: | ---: |
| Title safe | 0.100 | 0.050 |
| Action safe | 0.035 | 0.035 |
| Center title safe | 0.175 | 0.050 |
| Center action safe | 0.150 | 0.050 |

The upstream comment explicitly describes the center values as a different safe-area kind for an
alternate aspect ratio. They are not a request to crop the image. Each guide rectangle is therefore
computed from the active GUI viewport as:

```text
left/right inset = round(viewportWidth  * marginX)
top/bottom inset = round(viewportHeight * marginY)
```

Margins are clamped to `[0, 0.5]` at draw time as an additional guard. Ordinary title/action guides
and center-cut title/action guides use separate colors so overlapping defaults remain legible.

Guides are extracted only when all of the following are true:

1. The experimental Cycles renderer is enabled.
2. A Cycles frame is actually replacing the vanilla world.
3. `camera.safeAreas` is enabled.

`camera.centerCutSafeAreas` additionally controls the alternate pair. Disabling Cycles, waiting for
the first Cycles frame, or disabling safe areas leaves the vanilla HUD unchanged.

## Validation

- Java compilation covers the record/config/UI/overlay call chain.
- Default and DLSS native smoke targets compile against ABI 36.
- The native smoke test applies a non-zero shift, requires the diagnostic values to round-trip, and
  requires the rendered frame checksum to differ from its unshifted projection before restoring
  zero shift.
- Both localization JSON files are parsed as UTF-8.

Full in-game visual verification remains manual: check positive/negative X and Y shift in both
Perspective and Panorama, then compare all four safe-area rectangles at multiple GUI scales and
window aspect ratios.
