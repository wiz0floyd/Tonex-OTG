# Contributing to Tonex-OTG

This is a hobby project — contributions, bug reports, and hardware findings
are welcome, but expect a personal-project pace, not a corporate one.

## Getting started

- `:protocol` is pure Kotlin/JVM and needs no Android SDK:
  `./gradlew :protocol:test`
- `:app` needs the Android SDK (API 36): `./gradlew :app:assembleDebug`,
  `./gradlew :app:testDebugUnitTest`, `./gradlew :app:lintDebug`
- Run the full local check before opening a PR: tests **and** lint. Lint has
  caught real bugs test suites missed on this project — don't skip it.

## Opening a pull request

1. Branch off `main`.
2. Keep the PR scoped to one logical change.
3. Make sure tests and lint are green locally.
4. Open the PR against `main` with a description of what changed and why.
   Link the issue it closes (`Fixes #N`) if there is one.

## Reporting bugs

Open a [GitHub issue](https://github.com/wiz0floyd/tonex-otg/issues/new)
with your Android version, phone model, and what you expected vs. what
happened. Since the USB protocol is reverse-engineered, concrete detail
(a captured log, the exact preset/parameter involved) is especially useful.

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
