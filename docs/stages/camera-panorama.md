# Cycles panorama camera contract

## Scope and status

This stage connects Minecraft's live camera to the Cycles panorama camera path. It supports the seven panorama identifiers present in the pinned Cycles 5.2 source, exposes their effective parameters in the F9 editor, and expands section extraction so rays outside the vanilla perspective frustum can still hit loaded block geometry.

The implementation intentionally does not disable or downgrade DLSS when a panorama camera is selected. DLSS RR remains available as an experimental denoiser for every camera type. See [DLSS policy and validation boundary](#dlss-policy-and-validation-boundary) before treating a panorama/DLSS combination as production-qualified.

The implementation was split into these commits:

- `05b5756` — native ABI, validation, Java bridge, Cycles camera configuration and transforms;
- `91177e7` — persistent configuration, F9 dependencies and translations;
- `ba92779` — full-direction section compilation for panorama cameras;
- `6e30201` — preserve the full world height for panorama section uploads;
- `6bbc3f2` — native smoke coverage and camera diagnostics;
- `51ef9a4` — Blender-compatible panorama type order in F9.

## Upstream basis

The contract was derived from the source revisions in this workspace, not from UI labels alone:

- Cycles `v5.2.0`, commit `3b97e190c5ff1a2ed2160d879ad5bf95bea7b8ba`. The checkout reports dirty because the project's experimental DLSS patch is applied.
- Blender `v5.2.0`, commit `fbe6228777e7d9afefcd61a413844e790ae75db7`.

Relevant upstream locations:

- `.deps/cycles/src/kernel/types.h`: stable `ccl::PanoramaType` numeric identifiers;
- `.deps/cycles/src/kernel/camera/projection.h`: forward and inverse ray mappings;
- `.deps/cycles/src/scene/camera.cpp` and `camera.h`: camera sockets, defaults and device update;
- Blender `intern/cycles/blender/camera.cpp`: Blender-to-Cycles parameter forwarding and camera-axis conversion;
- Blender's historical `intern/cycles/blender/addon/camera.py`: the default polynomial fitted to a 50 mm projective camera on a 36 mm sensor.

When upgrading Cycles, re-audit the enum identifiers and all four source areas. The bridge deliberately uses explicit numeric IDs; a changed upstream enum must cause an intentional ABI update rather than silently selecting another projection.

## Type identifiers and active parameters

The F9 order follows Blender's camera panel. The native ID follows `ccl::PanoramaType`; these are separate contracts.

| F9 order | Type | Native ID | Parameters used by Cycles | Usual output shape |
| ---: | --- | ---: | --- | --- |
| 1 | Equirectangular | 0 | latitude min/max, longitude min/max | 2:1 for a full 360° × 180° sphere |
| 2 | Equiangular Cubemap Face | 5 | no subtype parameter | 1:1 single 90° face, not a six-face atlas |
| 3 | Mirror Ball | 3 | no subtype parameter | 1:1 circular image |
| 4 | Fisheye Equidistant | 1 | fisheye FOV | 1:1 recommended |
| 5 | Fisheye Equisolid | 2 | fisheye FOV, lens, sensor width/height | 1:1 recommended |
| 6 | Fisheye Lens Polynomial | 4 | fisheye FOV, sensor width/height, K0–K4 | depends on calibrated sensor/aspect |
| 7 | Central Cylindrical | 6 | longitude min/max, height min/max, radius | depends on angular and height ranges |

`CyclesRenderSettings.PanoramaType` stores an explicit `nativeId`; code must never pass `ordinal()` as the native value. The TOML enum is stored by name, so changing the F9 presentation order does not migrate or reinterpret existing configuration.

## Projection algorithms

All image coordinates below are normalized `u,v` coordinates. The authoritative implementation remains Cycles' `kernel/camera/projection.h`; these formulas summarize the behavior for diagnosis and future ports.

### Equirectangular

Longitude is linear in `u`, latitude/colatitude is linear in `v`, then Cycles converts the spherical angles to a direction. The configured degree ranges are converted to radians before reaching Cycles. Full defaults are longitude `[-180°, 180°]` and latitude `[-90°, 90°]`.

This is the only subtype whose latitude and general longitude settings are active. The bridge validates each endpoint independently and intentionally does not reorder min/max; reversed endpoints can therefore be used to reverse an axis.

### Equiangular cubemap face

Cycles maps one square face with:

```text
x = tan((0.5 - u) * π/2)
y = tan((v - 0.5) * π/2)
direction = normalize(1, x, y)
```

The result is one equiangular 90° cube face. Producing a complete cubemap still requires six camera orientations and external face assembly; the current real-time output is a single face, matching the Cycles subtype.

### Mirror ball

`u,v` first select a point inside the unit disk. Cycles reconstructs the sphere normal and reflects the fixed incident direction around that normal. Pixels outside the disk return a zero direction and do not trace the scene.

Mirror-ball mapping uses a distinct camera-axis matrix from every other panorama subtype, exactly as Blender's Cycles adapter does.

### Fisheye equidistant

Normalized radial distance `r` maps linearly to ray angle:

```text
theta = r * fisheyeFov / 2
```

Pixels outside the unit circle are rejected. The default FOV is `180°`; the accepted configuration range is `10°..1800°`, matching Cycles' ability to represent multi-turn mappings.

### Fisheye equisolid

The lens model is:

```text
r = 2 * lens * sin(theta / 2)
theta = 2 * asin(r / (2 * lens))
```

Here `r`, lens and sensor dimensions share the same millimetre scale. Cycles clips rays beyond half of the configured fisheye FOV. Sensor height is derived as `sensorWidth / renderAspect`.

### Fisheye lens polynomial

For sensor-space radius `r`, Cycles evaluates:

```text
theta = -(K0 + K1*r + K2*r² + K3*r³ + K4*r⁴)
```

It rejects rays when `abs(theta) > fisheyeFov / 2`. The inverse direction-to-image path solves for `r` with up to 20 Newton iterations and terminates when the radial delta is below `1e-6`.

The configured defaults are Blender's historical fit for a 50 mm projective camera on a 36 mm sensor:

```text
K0 = -1.1735143712967577e-05
K1 = -0.019988736953434998
K2 = -3.3525322965709175e-06
K3 =  3.099275275886036e-06
K4 = -2.6064646454854524e-08
```

These coefficients are calibration data, not generic distortion sliders. Changing sensor width, aspect or coefficient units changes the fitted lens.

### Central cylindrical

Cycles constructs an unnormalised direction:

```text
theta = lerp(longitudeMin, longitudeMax, u)
z = lerp(heightMin / radius, heightMax / radius, v)
direction = (cos(theta), sin(theta), z)
```

The height-to-radius division is performed before passing the vertical range to Cycles, matching Blender's adapter. Radius is restricted to a positive value to avoid division by zero. Defaults are longitude `[-180°, 180°]`, height `[-1, 1]`, and radius `1`.

## Units and camera axes

The Java/TOML boundary uses degrees for all angles because that is what the F9 user edits. The native bridge converts panorama angles to radians immediately before calling Cycles. Lens and sensor values remain millimetres, as in Blender. Minecraft block positions, clip distances, focus distance and scene-origin offsets use the existing project convention of one block per scene unit.

Depth-of-field aperture continues to use the existing conversion:

```text
apertureRadius = (focalLengthMm / 1000) / (2 * fStop)
```

Minecraft and Blender cameras both look along local `-Z`, but Cycles panorama mappings have their own environment-camera convention. After applying the Minecraft quaternion and scene-relative translation, the bridge applies the same matrices as Blender:

```text
perspective:        scale( 1,  1, -1)
general panorama:   rows (0,-1,0), (0,0,1), (-1,0,0)
mirror ball:        rows (1, 0,0), (0,0,1), ( 0,1,0)
```

Scale is cleared after multiplication. This keeps the visual heading consistent with Blender and avoids treating mirror-ball orientation as an ordinary environment panorama.

## Native ABI and configuration compatibility

Panorama is an additive but intentionally breaking native ABI extension:

- ABI version: `33 -> 34`;
- `CyclesBridgeRenderSettings`: `288 -> 360` bytes;
- the new settings tail starts at offset `288`, from `camera_type` through `central_cylindrical_radius` at offset `356`;
- `CyclesBridgeDiagnostics`: `504 -> 512` bytes;
- diagnostic `camera_type` and `panorama_type` are at offsets `504` and `508`.

Static assertions pin these sizes and offsets. Java also checks ABI 34 during native initialization, so an old DLL fails the handshake instead of reading the enlarged structure incorrectly.

These are the historical sizes for the panorama stage. The later camera-composition stage advances
the combined bridge to ABI 36; see `camera-composition.md` for the current shift offsets and sizes.

Existing settings remain valid: the camera type defaults to Perspective and the panorama subtype defaults to Equirectangular. New TOML keys are additive; no resource GUID, dependency, file format, or Minecraft world data is changed. The old perspective projection and DOF paths remain active when `camera.type=PERSPECTIVE`.

F9 enables only relevant inputs:

- projection is perspective-only;
- subtype is panorama-only;
- latitude/longitude are equirectangular-only;
- fisheye FOV is enabled for the three fisheye models;
- fisheye lens is equisolid-only;
- K0–K4 are polynomial-only;
- cylindrical ranges and radius are central-cylindrical-only;
- sensor width is used by physical perspective, equisolid and polynomial cameras;
- focal length remains available when DOF needs it, even if the current projection does not.

The debug overlays show both configured and native-effective camera/subtype names. A mismatch such as `PANORAMA/PERSPECTIVE` is therefore visible without attaching a debugger.

## Full-direction scene capture

Changing only the Cycles ray generator is insufficient. Vanilla `LevelRenderer.visibleSections()` is a perspective-frustum list, so rays behind the player or toward the poles would otherwise miss geometry that is loaded but never compiled and uploaded to Cycles.

`LevelExtractorMixin` redirects only the first `visibleSections()` call in `LevelExtractor.extract`, which is the dirty-section compilation candidate loop. In panorama mode it enumerates the current `ViewArea` grid around the camera for every X/Z section and every world-height section. The list is cached by `ViewArea` identity and camera section.

The other visible-section consumers remain unchanged. In particular, this stage does not broaden vanilla entity, block-entity or vanilla raster draw lists.

`SectionSceneManager` applies two range policies:

- horizontal X/Z membership still uses `ChunkTrackingView.isInViewDistance`, so panorama does not load chunks beyond Minecraft's configured render distance;
- perspective keeps the previous vertical view-distance test, while panorama accepts the complete level section height so top and bottom rays are not truncated.

Switching Perspective ↔ Panorama resets the Cycles scene and calls `levelExtractor.allChanged()` so the correct set is compiled. Switching between panorama subtypes restarts the camera render but does not rebuild identical scene coverage. Uploads remain governed by the existing limit of 24 sections and 4 ms per frame, so the first panorama switch may converge over multiple frames rather than blocking one frame indefinitely.

## DLSS policy and validation boundary

DLSS RR is deliberately allowed for panorama cameras. There is no camera-type gate, forced fallback to OptiX/OIDN, or automatic setting rewrite. The existing experimental DLSS history-reset behavior continues to apply when settings, camera state or scene topology changes.

This is a policy decision, not a claim that NVIDIA has qualified every Cycles panorama projection. Fisheye, mirror-ball, EAC-face and cylindrical mappings have discontinuities or invalid pixels that differ from perspective reprojection. Static and moving-image quality must therefore be checked per subtype. If artifacts are found later, prefer fixing motion/history validity for that subtype; do not silently change the user's configured denoiser.

## Validation record

Validation performed on Windows with the DLSS-enabled native build:

- `gradlew.bat compileJava` — passed after the section-range and F9-order changes;
- `cmake --build build/native-dlss --config RelWithDebInfo --target cyclesrenderer_smoke` — passed;
- `cyclesrenderer_scene_update_test.exe` — passed its accumulator and public ABI integration checks;
- `python -m json.tool` on both language files — passed;
- source and staged-diff audits — confirmed panorama commits did not include the parallel interop/section/performance work still present in the shared working tree.

The native smoke test now applies all seven panorama IDs, waits for a new frame, checks the effective diagnostics, and restores Perspective. Its latest execution did not reach that new loop: the pre-existing `initial section` stage timed out while waiting for its expected frame sequence even though OptiX reported a ready frame. That failure is in the concurrently changing section/interop path and must not be recorded as seven passing runtime renders. The test code compiled successfully and remains ready to rerun after that preceding failure is resolved.

## Manual regression checklist

1. Open a world with distinct north/east/south/west markers plus visible geometry above and below the camera.
2. Enable Cycles world replacement and use F9 to switch Camera Type to Panorama.
3. Select each subtype in Blender order and confirm the debug overlay's configured/native names match.
4. Use a square output for mirror ball, fisheye and EAC-face; use 2:1 for a full equirectangular check.
5. Verify rear, top and bottom geometry appears after the asynchronous section queue settles, without increasing loaded chunk distance.
6. For EAC-face, rotate the camera through six cardinal face orientations; do not expect one frame to contain a six-face atlas.
7. Change each subtype-specific parameter and confirm only the relevant F9 controls are enabled.
8. Switch back to Perspective and verify Minecraft-FOV and physical-lens modes retain their former framing and DOF behavior.
9. Repeat static and moving-camera checks with DLSS RR enabled for every subtype. Record seams, invalid-pixel halos and temporal trails rather than expecting an automatic denoiser fallback.

## Known limitations and follow-up risks

- In-game visual validation of all seven types is still required; compile success is not image correctness.
- The mixin redirect is compile-checked but still needs a real client launch to validate injection against the runtime `LevelExtractor.extract` bytecode.
- The panorama path captures block-section geometry already available through `ViewArea`; it does not expand server/client chunk loading, add distant terrain, or broaden entity and block-entity extraction.
- The first Perspective-to-Panorama switch can create a large compilation/upload burst proportional to view distance and world height. Existing asynchronous budgets limit per-frame work but do not eliminate total CPU/GPU/memory cost.
- EAC-face is a single face. A six-face cubemap layout and export orchestration are outside this stage.
- Stereo panorama, pole merge, border crop and render-region controls are not exposed by this bridge.
- Reversed min/max angular endpoints are accepted and forwarded. This is useful for axis reversal but should be considered when adding stricter UI validation later.
- The OptiX cache warning observed during tests (`optix7cache.db` unavailable) did not prevent the scene-update test, but it may increase initialization cost and is not changed by this camera stage.
