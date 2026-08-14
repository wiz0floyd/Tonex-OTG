# Contributing to Tonex-OTG

## Porting code from upstream projects

This project ports USB protocol logic from two upstream open-source
projects:

- [`Builty/TonexOneController`](https://github.com/Builty/TonexOneController) — Apache-2.0, Copyright 2025 Greg Smith
- [`vit3k/tonex_controller`](https://github.com/vit3k/tonex_controller) — MIT, Copyright (c) 2024 vit3k

**Before porting or adapting any logic from either project, read
[`CREDITS.md`](CREDITS.md).** It defines:

- which parts of this codebase already derive from which upstream project
  (see the "What was ported from where" table — add a row when you port
  something new), and
- the required source-header comment block every ported Kotlin file must
  carry, naming the upstream project, licence, and specific upstream file
  the logic came from (see "Source-header convention for ported files").

Applying that header is mandatory, not optional, for any file containing
ported/adapted logic — it is how this project satisfies the attribution
terms of both the Apache-2.0 and MIT licences. Copy the template from
`CREDITS.md` verbatim and fill in the upstream file path; do not invent a
different header format.

## Licence files

- [`LICENSE`](LICENSE) — Apache License 2.0, covering Tonex-OTG's own code
  and code derived from `Builty/TonexOneController`.
- [`LICENSES/MIT-vit3k.txt`](LICENSES/MIT-vit3k.txt) — MIT License, covering
  code derived from `vit3k/tonex_controller`.

Both must remain present, unmodified, in any distribution of this project.
