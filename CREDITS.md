# Credits and Third-Party Attribution

Tonex-OTG ports USB protocol logic from two upstream open-source projects into
Kotlin for Android. This file records where that logic came from, under what
licence, and exactly which parts of this codebase derive from which upstream.

It is written to be reused verbatim (plain text, no GitHub-specific markdown
features) by an in-app "Credits" / "Open Source Licences" screen.

Tonex-OTG's own original code is licensed under Apache-2.0 — see [`LICENSE`](LICENSE)
at the repository root. This file covers the *additional* obligations that
come from the two upstream projects our USB protocol logic was ported from.

---

## Upstream projects

### 1. TonexOneController

- **Repository:** https://github.com/Builty/TonexOneController
- **Licence:** Apache License 2.0 (SPDX: `Apache-2.0`)
- **Copyright:** Copyright 2025 Greg Smith
- **Full licence text:** [`LICENSE`](LICENSE) (root of this repository — same licence
  text, reproduced there for our own code) — see also
  https://github.com/Builty/TonexOneController/blob/main/LICENSE
- **Verified:** per-file licence headers in the upstream source (e.g.
  `source/main/tonex_params.c`, `source/main/usb_tonex_one.h`) read
  `Copyright (C) 2025 Greg Smith`, licensed under Apache-2.0. (Note: the
  top-level `LICENSE` file in that repository still carries the unedited
  Apache boilerplate placeholder `Copyright [2024] [Greg Smith]` — the
  per-file headers are the more specific and more recent statement of
  copyright and were used as the source of truth here.)
- **NOTICE file:** none present upstream (checked
  `https://github.com/Builty/TonexOneController/blob/main/NOTICE` — 404).
  No NOTICE-propagation obligation applies; attribution here is handled via
  this file and per-file source headers instead.

### 2. tonex_controller

- **Repository:** https://github.com/vit3k/tonex_controller
- **Licence:** MIT License (SPDX: `MIT`)
- **Copyright:** Copyright (c) 2024 vit3k
- **Full licence text:** [`LICENSES/MIT-vit3k.txt`](LICENSES/MIT-vit3k.txt) in this
  repository (verbatim copy) — see also
  https://github.com/vit3k/tonex_controller/blob/main/LICENSE
- **Verified:** root `LICENSE` file and per-file headers in the upstream
  source (e.g. `main/hdlc.cpp`, `main/hdlc.h`) both read the standard MIT
  text with `Copyright (c) 2024 vit3k`.
- **NOTICE file:** none present upstream (checked
  `https://github.com/vit3k/tonex_controller/blob/main/NOTICE` — 404).
- **MIT obligation:** the MIT licence requires that "the above copyright
  notice and this permission notice shall be included in all copies or
  substantial portions of the Software." We satisfy this by (a) shipping
  the full licence text unmodified in `LICENSES/MIT-vit3k.txt`, and (b)
  naming the copyright holder and licence in the header of every ported
  file (see "Source-header convention" below) and in this document.

---

## What was ported from where

| Area of our code | Derived from | Upstream file |
|---|---|---|
| HDLC-style byte-stuffing / frame delimiting, and the CRC-16 checksum (reversed polynomial `0x8408`) used to validate USB packets — the same algorithm appears independently in both upstream projects | TonexOneController | `source/main/usb_tonex_common.c` / `.h` (`tonex_common_calculate_CRC`, byte-stuffing helpers) |
| HDLC-style byte-stuffing / frame delimiting, and the CRC-16 checksum (reversed polynomial `0x8408`) used to validate USB packets — the same algorithm appears independently in both upstream projects | tonex_controller | `main/hdlc.cpp` / `.h` (`calculateCRC`, `addByteWithStuffing`) |
| Tonex parameter table (parameter IDs, scaling, min/max) | TonexOneController only | `source/main/tonex_params.c` / `.h` |
| Preset-details response handling (`TYPE_STATE_PRESET_DETAILS`, `TYPE_STATE_PRESET_DETAILS_FULL`, preset name parsing) | TonexOneController only | `source/main/usb_tonex_one.c` / `.h` |
| State-blob field offsets (e.g. input trim, stomp mode, cab bypass, tuning mode/reference, BPM, tempo source, direct monitor, current slot, bypass mode, slot presets) | TonexOneController only | `source/main/usb_tonex_one.h` (`TONEX_STATE_OFFSET_*` definitions) |
| Variable-length integer encoding (`0x80`/`0x81`/`0x82`/`0x88` lead-byte forms) and the `B9 03 <type> <size> <opaque>` message-header envelope it is built on | tonex_controller only | `protocol.md` (a documentation file, not C++ source — see [S5's issue #9](https://github.com/wiz0floyd/tonex-otg/issues/9) for the "single-sourced, not confirmed in code" caveat on the parts beyond the core `0x80`/`0x81`/`0x82` int forms and the `0x88` float form) |
| Message-type wire IDs (`TYPE_HELLO` / hello response `0x02`, `TYPE_STATE_PRESET_DETAILS_FULL` `0x0303`, `TYPE_STATE_PRESET_DETAILS` `0x0304`, `TYPE_STATE_UPDATE` `0x0306`, param-changed `0x0309`) | TonexOneController only | `source/main/usb_tonex_one.h` (same `TYPE_*` constants the preset-details row above already attributes to this file) |

The first two rows describe the same logic, once per upstream source file —
that logic derives from both projects independently, it is not a single
shared upstream file. When new files are ported, add a row here (or extend
an existing one) so this table stays the single source of truth for
provenance.

---

## Source-header convention for ported files

Every Kotlin file that contains logic ported or adapted from either upstream
project **must** carry a header comment identifying the upstream project,
its licence, and the specific upstream file the logic came from. This is
required for both projects: Apache-2.0 requires stating that a file was
modified, and MIT requires the copyright/permission notice to travel with
copies of the code. Naming the exact upstream file also keeps provenance
traceable as the port evolves.

Place the header at the very top of the file, directly above the `package`
declaration.

### Template — logic from a single upstream project

```kotlin
/*
 * Ported/adapted from: <Upstream Project Name>
 * Upstream repository: <https://github.com/OWNER/REPO>
 * Upstream file:        <path/to/file.c in the upstream repository>
 * Upstream licence:     <SPDX-Identifier> — <exact copyright line>
 * Full licence text:    <path in this repo, e.g. LICENSE or LICENSES/MIT-vit3k.txt>
 *
 * This file is a Kotlin port/adaptation of logic originally written for the
 * upstream project named above. See /CREDITS.md for full attribution.
 */
```

### Template — logic that appears in both upstream projects

Use this for anything matching the "Both" row in the table above (currently:
HDLC framing / CRC):

```kotlin
/*
 * Ported/adapted from: TonexOneController and tonex_controller
 * (equivalent logic appears independently in both upstream projects)
 *
 * - TonexOneController
 *   Repository:      https://github.com/Builty/TonexOneController
 *   Upstream file:    source/main/usb_tonex_common.c
 *   Licence:          Apache-2.0 — Copyright 2025 Greg Smith
 *   Full licence text: LICENSE
 *
 * - tonex_controller
 *   Repository:       https://github.com/vit3k/tonex_controller
 *   Upstream file:    main/hdlc.cpp
 *   Licence:          MIT — Copyright (c) 2024 vit3k
 *   Full licence text: LICENSES/MIT-vit3k.txt
 *
 * This file is a Kotlin port/adaptation of logic originally written for the
 * upstream projects named above. See /CREDITS.md for full attribution.
 */
```

### Worked example

A hypothetical `Hdlc.kt` implementing byte-stuffing and CRC-16 validation
would use the dual-provenance template above verbatim, with `Hdlc.kt` as the
filename. A hypothetical `TonexParameters.kt` implementing the parameter
table would use the single-project template, filled in as:

```kotlin
/*
 * Ported/adapted from: TonexOneController
 * Upstream repository: https://github.com/Builty/TonexOneController
 * Upstream file:        source/main/tonex_params.c
 * Upstream licence:     Apache-2.0 — Copyright 2025 Greg Smith
 * Full licence text:    LICENSE
 *
 * This file is a Kotlin port/adaptation of logic originally written for the
 * upstream project named above. See /CREDITS.md for full attribution.
 */
```

### Rules of thumb

- Always use the exact upstream file path as it exists in the upstream repo
  at the time of porting (not a guess, not the whole repo).
- Always name the specific licence and copy the copyright line exactly as it
  appears upstream (`Copyright 2025 Greg Smith` / `Copyright (c) 2024 vit3k`)
  — do not paraphrase or omit it.
- If a file combines logic from both upstreams, use the dual-provenance
  template and list both.
- If in doubt which upstream a piece of logic came from, add a row to the
  "What was ported from where" table above first, then write the header to
  match it.
- This header is in addition to, not a replacement for, any Kotlin file-level
  KDoc describing what the file does.

---

## Full licence texts

- Apache License 2.0 (Tonex-OTG's own code, and the licence covering
  TonexOneController-derived portions): [`LICENSE`](LICENSE)
- MIT License (covering tonex_controller-derived portions),
  Copyright (c) 2024 vit3k: [`LICENSES/MIT-vit3k.txt`](LICENSES/MIT-vit3k.txt)
