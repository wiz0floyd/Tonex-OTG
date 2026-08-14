# Tonex-OTG
Control app for original Tonex pedal via USB OTG.

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

This project is not affiliated with or endorsed by IK Multimedia. TONEX is a
registered trademark of IK Multimedia Production Srl.
