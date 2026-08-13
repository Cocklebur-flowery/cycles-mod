# F9 Cycles settings editor

## Purpose

F9 is the primary in-game editor for the existing NeoForge client configuration. It exposes the same persisted keys as `cyclesrenderer-client.toml`; it does not introduce a second configuration format or a second set of renderer settings.

The screen follows Blender's broad organization rather than the former NeoForge tree:

- Device
- Output
- Sampling
- Light Paths
- Filter
- Denoising
- Camera
- Atmosphere
- Materials / PBR
- Color Management
- Passes / Debug

Search operates across all categories and matches both the translated label and the stable configuration ID.

## Edit and apply model

Controls write into a typed in-memory draft. Numeric options provide both a drag slider and direct text input; large non-negative ranges use a logarithmic slider while the text field remains exact. Boolean and enum controls support forward cycling, Shift-click reverse cycling, and mouse-wheel cycling.

The draft is committed only when the user selects **Apply** or leaves with **Done/Escape**. All changed keys are saved under one configuration revision, so dragging an expensive scene or color option does not repeatedly reset Cycles. **Discard** reloads the persisted values.

## Context rules

The editor disables controls that cannot affect the current configuration:

- adaptive minimum samples and noise threshold require adaptive sampling;
- interactive resolution percentage requires dynamic resolution;
- physical lens dimensions require the physical-lens projection;
- aperture controls require depth of field;
- denoiser inputs require an enabled denoiser, and DLSS quality requires DLSS RR;
- PBR tuning is disabled when PBR is off;
- temperature and tint require white balance.

View transforms are filtered against the selected display, looks are filtered against the selected view transform, and available native denoisers and passes are filtered when the native bridge is ready.

## Color input correction

Minecraft base-color images contain sRGB/Rec.709 encoded data. The native image nodes now tag those images as `sRGB` instead of the active scene-linear working space. OCIO therefore decodes the transfer function and transforms Rec.709 primaries into the selected working space (for example ACEScg) exactly once. Normal/specular companion maps remain non-color data and bypass this conversion.

## Compatibility boundary

The editor consumes `CyclesClientConfig.options()` metadata and preserves the existing TOML keys, enum IDs, Native ABI, and renderer settings record. Camera projection and panorama contracts are owned by their dedicated implementation stage; the generic editor merely renders options registered by that stage.
