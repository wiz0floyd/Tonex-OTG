# Tonex-OTG

A native Android app that controls an [IK Multimedia ToneX One](https://www.ikmultimedia.com/products/tonexone/)
guitar pedal over a wired USB-OTG connection — full preset and parameter
control from your phone, no laptop needed for field/live use.

[![CI](https://github.com/wiz0floyd/tonex-otg/actions/workflows/ci.yml/badge.svg)](https://github.com/wiz0floyd/tonex-otg/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/wiz0floyd/tonex-otg)](https://github.com/wiz0floyd/tonex-otg/releases/latest)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

## What it does

The ToneX One has no built-in MIDI or wireless control, and IK Multimedia's
official control app only supports the newer ToneX One+. This app fills that
gap for the original ToneX One:

- Browse and switch between all 20 stored presets (not limited to the
  pedal's own 2-3 footswitch slots)
- Read and edit preset parameters — gain, EQ, gate, compressor, reverb, and
  the rest of the parameter set exposed by the pedal
- Toggle individual effects blocks on/off
- Live connection status and reconnect handling for the USB link

## Status and scope

This is a hobby project (see the project's [BRD](https://github.com/wiz0floyd/tonex-otg/issues/1)
for the full background), not a commercial product. Current scope, by
design:

- **Wired USB-OTG only** — no Bluetooth, no WiFi. If you want wireless,
  [Pirate MIDI's Polar](https://piratemidi.com/) hardware already solves
  that.
- **ToneX One only** — not the ToneX One+, the full-size ToneX Pedal, or
  ToneX Cab/Amp software.
- Functional UI, not a skinned amp-modeling interface.

This project is not affiliated with or endorsed by IK Multimedia. TONEX is a
registered trademark of IK Multimedia Production Srl.

## Requirements

- An Android phone or tablet with **USB On-The-Go (OTG) host mode** support
  (most phones since ~2015 have this; check your device if unsure)
- Android 8.0 (API 26) or newer
- A USB-OTG cable/adapter matching your phone's port (USB-C or micro-USB) to
  the ToneX One's USB-C port
- A ToneX One pedal

## Download and install

Grab the latest signed APK from the
[Releases page](https://github.com/wiz0floyd/tonex-otg/releases/latest).
Nightly builds (tracking `main`) are also published as a
[pre-release](https://github.com/wiz0floyd/tonex-otg/releases/tag/nightly)
if you want the bleeding edge.

F-Droid listing is planned but not yet published — this README will be
updated with a badge/link once the submission lands.

1. Download the APK from the release you want.
2. Install it (you may need to allow "install unknown apps" for your
   browser/file manager the first time).
3. Connect your phone to the ToneX One with a USB-OTG cable, power on the
   pedal, and open the app — grant the USB permission prompt when Android
   asks.

## Building from source

```
git clone https://github.com/wiz0floyd/tonex-otg.git
cd tonex-otg
./gradlew :app:assembleDebug
```

The `:protocol` module is pure Kotlin/JVM (no Android SDK required) and can
be built and tested on its own:

```
./gradlew :protocol:test
```

Building `:app` requires the Android SDK (API 36) — see
[CLAUDE.md](CLAUDE.md) if you're working with an AI coding agent in this
repo, it documents the expected SDK setup.

## Reporting bugs / hardware issues

Please [open an issue](https://github.com/wiz0floyd/tonex-otg/issues/new).
Include:

- What you expected vs. what happened
- Your Android version and phone model
- Whether the pedal was connected/powered when the issue occurred

Since the USB protocol was reverse-engineered (see Attribution below),
reports that include specifics — a captured log, the exact preset/parameter
involved — are especially useful for tracking down protocol edge cases.

## Contributing

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md),
especially before touching any USB protocol code: this project ports logic
from two upstream reverse-engineering projects and has a mandatory
source-header convention for attribution compliance.

## Attribution

Tonex-OTG's USB protocol layer ports logic from two upstream open-source
projects:

- **[Builty/TonexOneController](https://github.com/Builty/TonexOneController)**
  — Apache License 2.0, Copyright 2025 Greg Smith. Source of the Tonex
  parameter table, preset-details response handling, and state-blob field
  offsets, plus part of the HDLC framing/CRC logic.
- **[vit3k/tonex_controller](https://github.com/vit3k/tonex_controller)**
  — MIT License, Copyright (c) 2024 vit3k. Also a source of the HDLC
  framing/CRC logic (the same checksum algorithm appears independently in
  both upstream projects).

Full licence texts are included in this repository: the Apache License 2.0
in [`LICENSE`](LICENSE) and the MIT License in
[`LICENSES/MIT-vit3k.txt`](LICENSES/MIT-vit3k.txt). See
[`CREDITS.md`](CREDITS.md) for the complete breakdown of which parts of this
codebase derive from which upstream project, and
[`CONTRIBUTING.md`](CONTRIBUTING.md) for the source-header convention
applied to ported files.

## License

Tonex-OTG's own code is licensed under [Apache License 2.0](LICENSE). See
[CREDITS.md](CREDITS.md) for the additional attribution obligations that
come with the ported upstream code.
