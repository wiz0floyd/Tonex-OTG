# Handover — Tonex-OTG

Written at the end of a Claude Code cloud session, for whoever picks this up next.
`main` is at `a0a21ff`. **130 tests passing.**

---

## 1. What this project is

A native Android (Kotlin) app that controls an IK Multimedia **ToneX One** guitar pedal over
USB-OTG, so a guitarist can switch all 20 presets and edit parameters from a phone instead of
carrying a laptop to a gig. Issue #1 is the BRD.

The protocol is **not** being invented. It is ported from `Builty/TonexOneController`
(Apache-2.0, C/ESP32) which builds on `vit3k/tonex_controller` (**MIT** — the BRD wrongly said
Apache-2.0; this is corrected in `CREDITS.md`).

Hardware for validation: **Pixel 10 Pro (USB-C) + ToneX One**.

---

## 2. Read these first

| Path | Why |
|---|---|
| `CREDITS.md` | Attribution rules + the ported-file header convention. Follow it. |
| `docs/design/ui-design-tokens.md` | D1. Binding design tokens. |
| `docs/design/mockups.md` | D2 rationale + open questions. |
| `docs/design/mockups.html` | D2 mockups. **Signed off by the user on a Pixel in a dark room.** |
| `protocol/src/main/kotlin/dev/tonexotg/protocol/*.kt` (8 files) | The frozen API. |

The 8 files directly in `dev/tonexotg/protocol/` are a **frozen contract**. Read them; don't
edit them without a deliberate decision — six stories code against them.

---

## 3. Module layout and why

```
:protocol   pure Kotlin/JVM, ZERO Android imports, Maven Central only   ← builds anywhere
:app        Android + Compose                                            ← NOT YET CREATED
```

`settings.gradle.kts` deliberately does **not** include `:app`. Applying the Android Gradle
Plugin forces resolution against Google's Maven, which was unreachable in the environment where
this was built. Add `:app` in S10.

Packages in `:protocol`, one per story — this separation is what let four agents work in
parallel without conflicts:

```
dev.tonexotg.protocol            frozen contracts (S2)
dev.tonexotg.protocol.framing    HDLC + CRC-16/X-25 (S4)
dev.tonexotg.protocol.codec      varint + message header (S5)
dev.tonexotg.protocol.params     116-parameter registry (S6)
dev.tonexotg.protocol.state      state blob patching (S8) — branch open
dev.tonexotg.protocol.message    message encoders (S7) — branch open
```

---

## 4. Story status

### Merged into `main`

| Story | Issue | What |
|---|---|---|
| S1 | — | Gradle scaffolding, version catalog, JDK 21 |
| S2 | — | Domain contracts / API freeze |
| S3 | — | Licensing, `CREDITS.md`, MIT text |
| D1 | — | Design tokens |
| S4 | #8 | HDLC framing + CRC-16/X-25 |
| S5 | #9 | Varint + message header codec |
| S6 | #10 | Parameter registry (116 rows) |
| D2 | #6 | Screen mockups — **user-approved** |

### In flight — branches pushed, NOT merged, NO PR yet

| Story | Issue | Branch | State |
|---|---|---|---|
| **S8** | #12 | `s8-state-blob-patching` | **Reviewed and approved.** 147 tests. Ready to PR + merge. |
| **S7** | #11 | `worktree-agent-ad4af8e920d9aa507` | **WIP — DO NOT MERGE.** See below. |

**S8 is approved but has no PR.** Open one against `main`. It passed adversarial review
(see §6).

**S7 is incomplete.** Its agent was cut short by a session limit partway through. The work was
committed and pushed so it would survive the container being reclaimed, but it is **not
reviewed and not ready**:

- 9 main source files exist and compile: `HelloMessage`, `RequestStateMessage`,
  `RequestPresetDetailsMessage`, `ParameterWriteMessage`, `MasterVolumeMessage`,
  `PresetNameExtractor`, `SingleParameterPayloadCodec`, `TonexMessage`, `package-info`.
- **Only `HelloMessageTest` exists.** Everything else is untested.
- Nothing has been reviewed. Treat the code as unverified.

To resume: write the remaining tests per issue #11's acceptance criteria (§5 here has the
verified byte literals), run `./gradlew :protocol:test`, then review before opening a PR.
Re-read §5's warning that issue #11 may repeat the wrong three-varint header description.

### Not started

- **S9** (#13) connection state machine — needs S7 + S8
- **S9b** (#14) snapshot + revert — needs S7 + S9
- **S10–S14** (#15–#19) Android: AGP, USB transport, permissions, foreground service, aliases
- **S15–S19** (#20–#24) Compose UI against the approved D2 design
- **D3** (#7) interaction / IA spec
- **S20–S22** (#25–#27) hardware validation on the Pixel

---

## 5. Protocol facts — verified, do NOT re-research

Everything here was confirmed against the reference implementation. Re-deriving it wastes time
and risks regressions.

**Device**: VID `0x1963`, PID `0x00D1`, **CDC-ACM**, interface 0.

**Framing**: `0x7E` delimiters. `0x7D` escape, escaped byte is `value XOR 0x20`. Only `0x7E`
and `0x7D` are ever escaped. CRC is **CRC-16/X-25** (reflected poly `0x8408`, init `0xFFFF`,
refin/refout true, xorout `0xFFFF`), appended **little-endian**, itself byte-stuffed.
Check values: X-25 `0x906E`, CCITT-FALSE `0x29B1` for `"123456789"`.

> Upstream docs call this "CRC-CCITT". That is wrong and produces a different checksum.
> There is a pinned regression test asserting the two diverge. Don't "fix" it.

**A raw `0x7E` is ALWAYS a delimiter**, regardless of escape state. A correct encoder can never
emit `0x7D 0x7E` (escaped bytes are only ever `0x5E`/`0x5D`), so that sequence is always
corruption and resyncing immediately is correct. This was a real bug found in review.

**Message header**: `B9 03` then **FOUR varints** — `type`, `size`, and two opaque fields
(first is always `11`; second is `1` for hello, `3` otherwise). **`size` is exact**; the payload
slices to it.

> Issue #9 and possibly #11 say *three* varints. **That is wrong.** The merged code in
> `dev.tonexotg.protocol.codec` is correct.

Per-field encoding conventions, observed across five reference literals:

| field | convention |
|---|---|
| `type` | bare literal when small, `0x81` + 2 bytes LE when wide — never `0x82` |
| `size` | always `0x82` + 2 bytes LE |
| unknownA | always `0x80` + 1 byte |
| unknownB | always bare literal |

**Varints**: `0x80` → next 1 byte; `0x81`/`0x82` → next 2 bytes LE; `0x88` → float32; else the
byte is the value.

**Handshake literals** (pre-framing):
- Hello: `b9 03 00 82 04 00 80 0b 01 b9 02 02 0b`
- Request state: `b9 03 00 82 06 00 80 0b 03 b9 02 81 06 03 0b`

**Message types**: `0x02` hello, `0x0303` full preset details, `0x0304` preset details summary,
`0x0306` state update, `0x0309` param changed.

**Parameters**: 109 per-preset (0–108) + 7 global (110–116). **Index 109 is a `LAST` sentinel,
not a parameter.** All values are float32 on the wire including switches and selectors.

Upstream's table columns are `{default, min, max, "Name", TYPE, ...}` — **default first**.
Reading it as min/max/default inverts bounds silently.

**Display names are NOT unique** — indices 88 and 90 both read `"MOD RO S"`. Use `enumName`
for identity. The UI label layer must key off wire index, never the abbreviation.

**Banked parameters — this drives the whole UI**:

| selector | idx | options | banked params | live at once |
|---|---|---|---|---|
| `RVB MODEL` | 38 | 6 | 24 | 4 |
| `MOD MODEL` | 65 | 5 | 28 | 5–6 |
| `DLY MODEL` | 96 | 2 | 12 | 6 |
| `MDL CAB` | 24 | 3 | 9 (VIR block) | 0 or 9 |

**64 of 109 parameters are in mutually-exclusive banks; only ~16 are ever active.** Real live
count is 51–61. "Reverb Mix" is not a parameter — it resolves to the active model's mix.

**State blob offsets**, end-relative, from `usb_tonex_one.c` lines 110–119 @ `7079f157`:
`SLOT_A=18`, `SLOT_B=16`, `SLOT_C=14`, `CURRENT_SLOT=11`, `BYPASS_MODE=12`, `TUNING_REF=9`,
`DIRECT_MONITOR=7`, `TEMPO_SOURCE=6`, `BPM=4`. `MAX_STATE_DATA=512`.

> **These offsets are firmware-version dependent.** They moved between upstream releases
> (`-12/-10/-8/-5` → `-18/-16/-14/-11`). Never patch blind.

---

## 6. The safety story — read before touching writes

**The pedal auto-saves every write. There is no undo and no commit step.**
`USB_COMMAND_SAVE_PRESET` upstream is an empty function with the comment "Tonex One uses auto
save, nothing needed."

**The bug this project must never reproduce**: upstream V1.0.0.2 shipped 20 hardcoded
whole-device-state packets captured via Wireshark from the author's own pedal and replayed them
to switch presets. Because "set preset" is a whole-state write, it **overwrote every user's
globals with the capture rig's** — input trim, cab-sim bypass, tuning, tempo, colour table.

Design rules, enforced in `dev.tonexotg.protocol.state`:

1. The blob is **opaque**, retained **verbatim**, patched only at named offsets.
2. **Never synthesize a blob. Never replay a stored one.**
3. Writes are rejected unless the blob was read **this session** (`SessionId`).
4. A sanity check validates the offset bytes look plausible before patching.
5. **Do not port** upstream's inherited side-effects: `set_preset_in_slot()` unconditionally
   forces `DIRECT_MONITOR = 1` and may force stomp/AB mode. S8 deliberately never names those
   offsets as constants, so no code path can write them.
6. **Never write to learn something.** Upstream once wrote a deliberately wrong preset into
   Slot A at boot to provoke a name response. Do not do this.

`SessionId` is a class with **no contents** and identity equality, with an `internal`
constructor — so it cannot be forged, because there is nothing to match. `PedalState` likewise.
Code outside `:protocol` cannot manufacture a writable blob. **Preserve this.** The test source
set is a friend module and can already construct them; you do not need an escape hatch.

Known residual risk, honestly stated: the sanity check catches an *implausible* byte, but a
layout shift that happened to leave four plausible values would not be caught. Robustly fixing
this needs a firmware fingerprint the pedal doesn't expose. Documented in
`StateBlobOffsets` KDoc.

**Safety net (S9b, not yet built)**: on reaching READY, snapshot the active preset's 109 floats;
expose `revert()` replaying them via per-parameter writes; warn before the first destructive
write of a session; re-snapshot on preset change.

---

## 7. Android environment — the blocker and the fix

Android work was deferred because `dl.google.com` returned **403 CONNECT** and
`maven.google.com` 301-redirects there. Root cause found: the cloud environment's **Network
access** was on the default **Trusted** level, whose allowlist includes `developer.android.com`
and `*.googleapis.com` but **not** `dl.google.com` or `maven.google.com` — docs host allowed,
artifact hosts not.

**This is user-fixable, not an admin action.** Per the docs there is no org-level allowlist
admins push. Set the environment's Network access to **Custom** and add:

```
dl.google.com
maven.google.com
jitpack.io
```

…with **"Also include default list of common package managers" checked**, or use **Full**.
Without that checkbox you lose Maven Central and break the `:protocol` build.

`jitpack.io` matters only if S11 chooses `usb-serial-for-android` over the raw `UsbRequest` API.

> If you are reading this in a **full-access container, this is already solved** — verify with
> `curl -sSI https://dl.google.com/` and then proceed with S10.

Android builds in Anthropic-hosted environments aren't a documented/tested scenario. Expect at
least one more rough edge; SDK licence acceptance under `sdkmanager` is the usual candidate.

---

## 8. Android platform facts — verified, don't re-research

- **minSdk 26.** `UsbRequest.queue(ByteBuffer)` and `requestWait(timeout)` are API 26. Below
  that you inherit Android bug 39522 — no-arg `requestWait()` blocks forever, uninterruptibly.
- **Read loop**: keep N `UsbRequest`s queued on the IN endpoint, demux via `getClientData()`,
  on a **dedicated thread** — not `Dispatchers.IO`, because it blocks in JNI where coroutine
  cancellation can't reach. Budget a watchdog.
- **Permission**: `FLAG_IMMUTABLE` **plus `setPackage()`**. On Android 14 an implicit broadcast
  can't reach a `RECEIVER_NOT_EXPORTED` receiver — presents as "user taps Allow, nothing
  happens". Custom receiver → `RECEIVER_NOT_EXPORTED`; system ATTACHED/DETACHED → **no flag**.
- **Foreground service type `connectedDevice`** + `FOREGROUND_SERVICE_CONNECTED_DEVICE`. Not on
  the Android 15 timeout list. `USB_DEVICE_ATTACHED` can only target an `<activity>`, so a
  no-display trampoline activity starts the service.
- `device_filter.xml` IDs are **decimal**: `0x1963` → `6499`, `0x00D1` → `209`.
- **Testing without hardware**: Robolectric 4.9+ has `ShadowUsbDeviceConnection`
  (`writeIncomingData()` / `getOutgoingDataStream()`) and `ShadowUsbManager.grantPermission()`.

---

## 9. Working conventions

- **One PR per story into `main`**, opened only after review. Merge commits, not squash.
- Sub-agents build; the lead reviews. Agents **push branches but do not open PRs**.
- Commit trailers on every commit:
  ```
  Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
  Claude-Session: <session url>
  ```
- Body includes `Refs #<issue>`.
- Assign each parallel agent its **own package** — this is what prevented conflicts across four
  simultaneous agents.
- Tell agents **not to re-research**; point them at
  `/workspace/builty/tonexonecontroller` (clone it if absent, pin `7079f157`).

**Review lessons that repeatedly mattered:**

1. **Diff the test names, don't trust the count.** One agent reported a "3-test change" that was
   actually 13 removed / 5 added, silently dropping 8 tests.
2. **Probe the branch yourself.** Every significant defect this session was found by writing a
   throwaway test against the agent's branch, not by reading its code or report.
3. **When an agent flags an inconsistency, believe the bytes, not the issue.** The four-varint
   header correction came from an agent honestly reporting "my read and the evidence disagree."
4. Delete throwaway probes and confirm `git status` is clean before opening the PR.

---

## 10. Immediate next steps

1. **Open a PR for S8** (`s8-state-blob-patching` → `main`). Approved, 147 tests.
2. **Check whether the S7 agent finished**, review its branch, then PR it.
3. Once both land, **S9** (connection state machine) unblocks, then **S9b** (snapshot/revert).
4. If network access is now Full/Custom, **S10** (AGP + Android SDK) unblocks the entire
   Android and UI track — S11–S14 and S15–S19.
5. **S20 (firmware probe) is worth doing early on the Pixel.** It determines whether the pedal
   supports the *per-parameter* write path. If its firmware predates Editor support, revert
   falls back to whole-state writes and the risk profile changes materially — which would force
   a revisit of S9b.

---

## 11. Open questions for the user

- **D3 / S17**: which parameters get promoted beyond the six in the quick tier
  (Gain, Bass, Mid, Treble, Reverb Mix, Delay Mix)? Noise Gate Threshold was the runner-up.
- **Master Volume conversion curve** (−40..+3 dB ↔ 0–10) is assumed linear and unverified.
- **`MDL CABU`** (index 23) — upstream's own name is `CABINET_UNKNOWN`. Nobody knows what it
  does. On the S20 probe list.
- **`VIR M2X`** (index 31) max recorded as 2 while `VIR M1X` (28) is 10. Likely a typo. S20.
- **`CABINET_TYPE`** (index 24) enum ordering — upstream header comments contradict each other.
  S20.
