# Launcher icon

Slate mini-stomp pedal with a USB logo on a flat `#FF6A1A` orange background.

| File | What it is |
|---|---|
| `icon_v04_source_1024.png` | Flattened source image (1024²) |
| `ic_launcher_foreground_1080.png` | Transparent foreground layer on a 108dp canvas (10 px/dp), pedal fitted inside the 66dp safe circle |
| `preview_circle_mask.png` | How it looks under a circular launcher mask at 192/96/48 px |

Shipped as an adaptive icon: `app/src/main/res/mipmap-anydpi/ic_launcher.xml`,
foreground PNGs in `mipmap-{m,h,xh,xxh,xxxh}dpi/`, background colour in
`values/ic_launcher_background.xml`. The F-Droid listing icon is
`metadata/dev.tonexotg.app/en-US/images/icon.png` (512²).

## Provenance

- Pedal body generated locally with FLUX.1-schnell (Apache-2.0) via
  stable-diffusion.cpp, then recoloured and scaled by script.
- The USB logo is drawn as vector primitives (PIL), not by the model.
- No third-party artwork; the icon is licensed with the rest of this repo.

To replace it, regenerate the 1080² foreground (transparent, art inside the
centre 640 px circle) and re-export the five density PNGs.
