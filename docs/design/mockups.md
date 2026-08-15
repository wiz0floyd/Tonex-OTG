# D2 — Screen Mockups: Rationale

**Story:** D2 (refs [#6](https://github.com/wiz0floyd/Tonex-OTG/issues/6), parent [#1](https://github.com/wiz0floyd/Tonex-OTG/issues/1))
**Depends on:** D1 (`docs/design/ui-design-tokens.md`) — binding, applied not re-decided.
**Artifact:** `docs/design/mockups.html` — open on the Pixel 10 Pro for sign-off.

This document explains the non-obvious calls in the mockups and lists open
questions for the lead. It is a companion to the HTML, not a restatement of
it — read the HTML's own per-screen annotations first; this covers what
didn't fit in a margin note.

---

## 0. Revision history

**Revision 2** — the lead recovered a partial real parameter table (four
banked model-selector groups: Reverb, Modulation, Delay, Cabinet's VIR mic
block) and it invalidated a structural assumption: 64 of the 109 preset
parameters aren't independent, they belong to mutually-exclusive banks
where only one model per selector is ever live. The parameter editor
(screen 2c) was reworked to show a model-selector chip row per bank and
render only the active model's block; the quick tier's Reverb Mix/Delay
Mix cards were reworked to resolve dynamically to whichever model is
live; a human-label-over-abbreviation layer was added to every rendered
row. Master Volume moved from "flagged as possible scope" to "built," as
a persistent bottom dock.

**Revision 3 (this one)** — S6 landed and the *complete* real registry now
exists (`protocol/src/main/kotlin/dev/tonexotg/protocol/params/ParameterRegistry.kt`,
branch `s6-parameter-registry` — read only, per instruction; not imported,
not merged into this branch, nothing outside `docs/design/` touched).
Three changes:

1. **Every rendered parameter row now uses the real abbreviation, real
   min/max/default, and a label derived from the real `enumName`** — not
   the plausible-but-invented names from Revision 2. This changed several
   things I'd gotten wrong under-informed: Reverb's four-letter shape is
   Time/Predelay/**Color**/Mix, not "Character"; Delay's real per-model
   letters are `DG S`/`DG T`/`DT M`/`DT F`/`DT O`/`DT X`, not the "DIG
   T/F/M/C/S" I'd invented; the VIR mic block's SELECT-type rows (mic
   type, cabinet model) have no option-name strings in the registry, so
   the specific mic-brand values I'd shown (e.g. "Dynamic 57") were pure
   invention and are now removed in favor of the raw numeric index.
2. **The `MOD RO S` duplicate-abbreviation callout moved off the rendered
   screens into this document only.** The lead's reasoning: a guitarist
   looking at the app should see "Rotary Sync" and "Rotary Speed" as two
   clearly different controls — surfacing "these two happen to share a
   hardware abbreviation" exposes an implementation inconvenience to
   someone who doesn't have the problem, and diluted `status.error`'s one
   job (connection state) besides. Both rows still show their own
   `pedal-abbr` caption (`MOD RO S`, truthfully, same as every other row)
   with no special color or callout — see §3 for where the actual
   instruction to future implementers now lives.
3. **Categories that don't exist in the real registry were dropped, not
   kept as illustrative:** "Power Amp," "FX Loop / Routing," "Expression /
   Wah," and Cabinet's invented "Low Cut / High Cut / Resonance / Air"
   core rows are gone. The real structure turned out to be *simpler* than
   my Revision-2 guess — 8 preset-scoped groups instead of 11, because I'd
   split things (like a separate power-amp stage) that the pedal doesn't
   actually have as distinct blocks.

Everything the lead accepted as-is in Revision 2 (the destructive-action
color reasoning, the six-item quick-tier ceiling, hiding banks entirely
rather than graying them out, the "resolves to" indirection caption on
quick-tier Reverb/Delay Mix, the contrast self-checks, the 412dp viewport
assumption) is unchanged and not re-litigated here.

---

## 1. The quick tier: what and why

**Promoted (6):** Gain (`MDL GAIN`, idx 20), Bass (`EQ BASS`, idx 11), Mid
(`EQ MID`, idx 13), Treble (`EQ TREBLE`, idx 16), Reverb Mix (resolves to
the active reverb model's mix parameter, e.g. `RVB PL M`), Delay Mix
(resolves to the active delay model's mix parameter, e.g. `DLY DT X`).
All six check out against the real registry unchanged from Revision 2 —
the only correction this pass was Delay Mix's abbreviation, which I'd
guessed as `DLY DIG M` and is actually `DLY DT X` (the real Digital block
uses inconsistent `DG`/`DT` prefixes across its own six parameters — I
preserved that inconsistency rather than "cleaning it up," since it's
what the hardware actually says).

Reasoning (unchanged from Revision 2):

- **Gain, Bass, Mid, Treble** mirror the four knobs on the front panel of
  almost every physical guitar amp a working guitarist has used for
  years. Promoting exactly these four costs zero learning curve on stage.
- **Reverb Mix and Delay Mix** are the two knobs most commonly retouched
  *per song* to match a room or a part, without digging into a full
  effect submenu. They are not single wire parameters — the pedal has one
  mix value per reverb model (`RVB S1 M` … `RVB PL M`, six total, all
  confirmed real) and one per delay model (`DLY DT X` for Digital, `DLY
  TA X` for Tape). The quick-tier card resolves to whichever model is
  currently active and shows that explicitly via a "→ resolves to `RVB PL
  M` · Plate model is active" caption, rather than picking one silently.
- Six was a deliberate ceiling: at `touch.target.min` (64dp) per card,
  six cards run to roughly 664dp — a short scroll on a ~915dp screen, not
  a wall of sliders.
- **Runner-up, still left out:** Noise Gate Threshold (`NG THRESH`, real,
  idx 2, range −100..0dB). One accordion-tap away in "All Parameters →
  Noise Gate" rather than a seventh card.
- **Master Volume is excluded from the quick tier** — it's global
  (`MASTER_VOLUME`, idx 116, confirmed in the real registry), not saved
  per preset, so a per-preset quick tier would misleadingly imply it
  resets with the preset. It has its own persistent dock instead (below).

**Master Volume — the persistent dock:**

- Pinned to the bottom of the preset list and parameter editor screens,
  reachable without navigating anywhere.
- Uses `surface.raised-1` — the exact token D1 assigns to "top app bar,
  bottom bar" (§2.1) — rather than `surface.raised-2` (the per-preset
  param-card token), extending the visual grammar already used elsewhere
  in the mockup to keep global scope legible, rather than inventing a new
  pattern.
- Real registry data: `MVOL`, `MASTER_VOLUME`, range −40..+3 dB, default
  0. The primary numeral shown is the pedal's own 0–10 display scale
  (matches the hardware screen, so it costs a guitarist nothing to read),
  with the dB value as a small secondary caption. The 0↔dB mapping used
  in the mockup (linear, `dB = −40 + value/10 × 43`) is my own assumption
  for display purposes only, **not confirmed by the registry** (which
  stores the dB value directly, not a 0–10 sub-scale) — flagged, not
  presented as fact.

## 2. The 109 parameters: real registry structure

The registry groups cleanly into **8 preset-scoped categories** (down
from Revision 2's 11 guessed ones — the real pedal is simpler than I'd
assumed) plus the 7 confirmed globals:

| Category | Count | Structure |
|---|---|---|
| Noise Gate | 5 | Post, Enable, Threshold, Release, Depth — flat, no banking |
| Compressor | 5 | Post, Enable, Threshold, Makeup Gain, Attack — flat, not expanded on screen |
| EQ | 8 | Post + 3×(band, freq) for Bass/Mid/Treble + Mid Q — flat |
| Tone Model / Amp | 8 | Amp Enable, Switch 1, Gain, Volume, Mix, Cabinet (unknown), Presence, Depth — flat |
| Cabinet | 10 | 1 mode selector (`MDL CAB`) + 9 VIR mic params, live only in VIR mode |
| Modulation | 31 | 3 always-on (Post/Enable/Model select) + 28 banked across 5 models (5–6 live) |
| Delay | 15 | 3 always-on (Post/Enable/Model select) + 12 banked across 2 models (6 live) |
| Reverb | 27 | 3 always-on (Position/Enable/Model select) + 24 banked across 6 models (4 live) |
| **Total preset-scoped** | **109** | |
| Global | 7 | BPM, Input Trim, Cab Sim Bypass, Tempo Source, Tuning Reference, Bypass, Master Volume |

Real live parameter count per preset: 51 (no VIR, smallest modulation
model) to 61 (VIR active, a 6-param modulation model) of 109.

**Grouping decision worth flagging:** the registry's own section comments
put the Cabinet-mode selector (`MDL CAB`, index 24) in the same
contiguous wire block as the Tone-Model/Amp parameters (indices 18–24),
while the VIR mic block is a separate block (25–33). I regrouped `MDL
CAB` together with the VIR block into one "Cabinet" screen category
(count 10) and kept the rest of 18–23 plus the separate 34–35
"Amplifier Extras" block as "Tone Model / Amp" (count 8) — a
presentational reorganization for the screen, not a claim about the wire
layout. Noted here so it's not mistaken for a registry fact.

**Banked-group behavior**, demonstrated on screen for one example each
(Cabinet/VIR, Modulation/Rotary, Delay/Digital, Reverb/Plate): a
model-selector chip row sits above the active model's block; choosing a
different chip swaps the whole block instantly (no motion beyond D1's
150ms ceiling), and the other models' parameters are not rendered at all
— not grayed out, not present in the DOM. This was the structural gap
Revision 1 had (all six reverb models shown flat and simultaneously,
letting a player edit a parameter that does nothing while a different
model is active) and Revision 2 fixed with the four confirmed banks.
Revision 3 didn't change this mechanism, only the data inside it.

**Two rows rendered with a visible `Inferred` tag**, because the registry
doesn't name their function: `MOD RO T` / `DLY DG T` (and their siblings
across every other banked model — every model has one of these) are
SELECT-type, 18 options, positioned right next to each model's Sync
switch. My best guess is "sync/tempo subdivision" and I labeled it "Sync
Division," but that's inference from position and pattern, not a
confirmed registry fact, so it's marked on screen, not just in this
document — per the instruction that an honest caveat has to live where
the reviewer will actually see it.

**Two rows rendered with a visible `S20`-style tag**, because the
registry's own doc comment flags them as unverified against real
hardware:

- `MDL CABU` (`CABINET_UNKNOWN`) — shown as "Cabinet" with a "Known
  unknown" tag. This isn't a case of me failing to find a label: upstream's
  own identifier for this parameter is literally "unknown." I show it
  (it's a real, live, toggleable parameter) but don't invent a function
  for it.
- `VIR M2X` (`VIR_MIC_2_X`) — its registry-recorded range is 0–2, while
  its counterpart `VIR M1X` is 0–10. The registry's own doc comment flags
  this as "likely a typo, unconfirmed." Shown with the recorded range and
  an "S20: range may be wrong" tag.

**SELECT-type rows with no known option labels** (Cabinet Model, Mic 1,
Mic 2, and both `*_TS` selectors) are shown as their raw numeric wire
index, not a descriptive name — the registry stores option counts (e.g.
`VIR_CABINET_MODEL` is 0–10, eleven options) but not option-label
strings. Revision 2 had invented specific values here (e.g. "Dynamic
57," "Condenser 414" as mic types) that were not backed by any real
data; those are gone.

## 3. Human-readable labels over the pedal's raw abbreviations

Every label on screen is derived directly from the row's `enumName` in
the registry (e.g. `NOISE_GATE_THRESHOLD` → "Threshold," trimming the
redundant block-name prefix since the accordion heading already supplies
it), with the raw abbreviation kept visible underneath in a small
monospace caption (`.pedal-abbr`) — the same treatment already used for
"from pedal: PRESET 07" on the preset-list screen, extended rather than
reinvented. Two things this makes possible:

1. **The abbreviation stays discoverable** for a player cross-referencing
   the hardware screen, who will search for `NG THRESH`, not "Threshold."
2. **A one-line instruction for whoever builds S17**, not shown on
   screen: the pedal's own display-name field (`ParameterSpec.name`) is
   *not unique* — indices 88 and 90 are both literally `MOD RO S`
   (`MODULATION_ROTARY_SYNC` and `MODULATION_ROTARY_SPEED`). Both rows in
   the mockup render fine as "Sync" and "Speed" because the human-label
   layer keys off `enumName` (or, at the wire level, index), never off
   the abbreviation string. **This is deliberately not called out on the
   rendered screens** — the lead's instruction was that a guitarist
   should see two unambiguous controls, not a note about a hardware
   naming collision that isn't their problem. If S17 ever builds a
   lookup that groups or deduplicates parameters by `name` instead of
   `enumName`/index, Sync and Speed will silently collide. This paragraph
   is that warning, written down once, here, so it survives independent
   of this file's screens.

The label-derivation principle is demonstrated on every parameter row the
mockup actually renders (full quick tier, and every expanded block in
screen 2c: Noise Gate, EQ, Tone Model/Amp, Cabinet/VIR, Modulation/Rotary,
Delay/Digital, Reverb/Plate, and Global). Collapsed categories
(Compressor) show only their real count, no invented leaf names.

## 4. Snapshot / revert / first-write-warning

Two things resolved by reading `protocol/` (read-only, pre-S6 files —
`ConnectionState.kt`, `TonexError.kt`, `PresetSnapshot.kt` — not touched):

- The `SnapshotStore` contract snapshots **per preset, when that preset
  becomes active** (including via the pedal's own footswitch), not "at
  connect" as the story text simplified it. Copy reflects the precise
  model: "Snapshot taken when this preset became active."
- Revert replays the snapshot as **per-parameter writes**, never a
  whole-state write, and covers only the 109 preset-scoped parameters —
  globals are untouched. The confirm dialog says this explicitly.
- `SnapshotStore.hasWarnedThisSession()` / `markWarned()` already model
  exactly a once-per-session gate; the warning dialog is the direct UI
  for that existing flag.

**"Hard to hit by accident" was solved without color or size:**

1. Physical separation — the revert action lives in Settings, not on a
   screen touched mid-song.
2. The entry point uses `touch.target.secondary` (48dp) and outline
   (unfilled) styling — sized and styled as an available option, not an
   inviting one.
3. The confirmation dialog **inverts the normal button hierarchy**:
   Cancel gets the accent-filled, visually primary treatment; "Revert
   Anyway" is a plain outline button.
4. Copy states the concrete consequence (all 109 parameters, immediate,
   irreversible) instead of a generic "Are you sure?"

**Open question for the lead, unresolved:** D1 scopes `color.status.error`
strictly to connection state (§2.3). I did not reuse it for the revert
flow's destructive action, for the same reason the lead gave for pulling
it off the `MOD RO S` callout this pass (§0) — it's a single-purpose
signal on stage and shouldn't carry a second meaning. If a future screen
needs a clearly-destructive red affordance, D1 will need a new token; I
did not invent one.

## 5. Connection states

D1 pins four states; `protocol/`'s `ConnectionState` sealed interface has
six (`Idle`, `Connecting`, `Hello`, `GetState`, `Ready`, `Error`), mapped
for the UI as: `Idle`→Not Connected, `Connecting`/`Hello`/`GetState`→
Connecting…, `Ready`→Connected, `Error(cause)`→Error with `cause.message`
surfaced. This matches D1 §2.3's own note that `Connecting` covers the
gap between starting an attempt and getting a response, and should read
as one "in progress" state, not three.

The mockup shows both the compact app-bar chip (worn on every screen) and
escalated banners for the two unhappy paths (Disconnected, Error). The
error banner uses a realistic `TransportFailure` message shape matching
`TonexError.kt`'s actual format, not a placeholder string.

## 6. Contrast — what I actually checked

D1 §2.4 verifies a specific set of pairs; several pairs used in this
mockup aren't in that table. Computed from sRGB relative luminance (same
method D1 used), not eyeballed:

| Pair | Ratio | vs. requirement | Result |
|---|---|---|---|
| `on-surface.primary` #FFFFFF on `surface.raised-1` #1D1D1D | 16.86:1 | 4.5:1 | Pass |
| `on-surface.secondary` #E0E0E0 on `surface.raised-1` #1D1D1D | 12.77:1 | 4.5:1 | Pass |
| `on-surface.secondary` #E0E0E0 on `surface.raised-2` #262626 | 11.46:1 | 4.5:1 | Pass |
| `on-surface.tertiary` #9E9E9E on `surface.raised-1` #1D1D1D | 6.29:1 | 4.5:1 | Pass |
| `on-surface.tertiary` #9E9E9E on `surface.raised-2` #262626 | 5.65:1 | 4.5:1 | Pass |
| `on-surface.primary` #FFFFFF on `surface.raised-3` #303030 | 13.20:1 | 4.5:1 | Pass |
| `accent.primary` #52B8FF on `surface.raised-1` #1D1D1D | 7.75:1 | 3:1 | Pass |
| `accent.primary` #52B8FF on `surface.raised-2` #262626 | 6.96:1 | 3:1 | Pass |
| `status.error` #FF5252 on `surface.raised-2` #262626 | 4.74:1 | 4.5:1 (text) | Pass (narrowly) |
| `status.connected` #34D177 on `surface.raised-2` #262626 | 7.59:1 | 3:1 | Pass |
| `status.disconnected` #9AA0A6 on `surface.raised-2` #262626 | 5.73:1 | 3:1 | Pass |
| `on-surface.disabled` #6B6B6B on `surface.raised-1` #1D1D1D | 3.16:1 | exempt (disabled) | N/A by design, same as D1 §2.4's own ruling |

The `status.error`/`raised-2` pair (4.74:1, narrowest pass) isn't used as
text anywhere in this mockup — error-colored text stays on `base` or
`raised-1` (both higher-margin); `raised-2` is only used for the
graphical triangle glyph (3:1 floor). The `on-surface.disabled`/
`raised-1` pair (3.16:1, exempt per D1) is the pairing behind this
revision's new `.illustrative-tag` chips ("Inferred," "Known unknown,"
"S20: …") — deliberately the quietest token available, chosen so these
provenance notes read as secondary metadata, not an alarm; D1 already
exempts disabled-tier content from the contrast requirement for exactly
this reason.

## 7. S20 hardware-probe list

Three items in the real registry are explicitly flagged (by the
registry's own doc comment, or by the lead directly) as needing
verification against a physical pedal before they can be trusted:

1. **`MDL CABU` (`CABINET_UNKNOWN`, index 23)** — upstream's own
   identifier says "unknown." Shown in the mockup with its raw
   abbreviation and a "Known unknown" tag, no invented function. Probe:
   toggle it on real hardware and observe what changes.
2. **`VIR M2X` (`VIR_MIC_2_X`, index 31)** — registry range is 0–2, but
   its counterpart `VIR M1X` (index 28) is 0–10. Registry's own comment:
   "likely a typo, unconfirmed." Probe: sweep the control on hardware and
   confirm the real range.
3. **`MDL CAB` (`CABINET_TYPE`, index 24) option ordering** — the enum
   and one table comment agree on 0=Tone Model, 1=VIR, 2=Disabled (used
   in this mockup's chip order); a separate header comment in the same
   upstream source says 0=Disabled, 1=VIR, 2=Tone Model. Probe: set the
   value on hardware and observe which mode is actually selected at each
   index.

None of these are mockup decisions — they're implementation-verification
work for S20, listed here so they aren't lost between now and then.

## 8. Other assumptions, flagged

- **Viewport width.** Neither D1 nor the issue states the Pixel 10 Pro's
  exact dp width. Assumed ~412dp (typical Pixel Pro-class), built at
  `max-width: 412px` with 1dp = 1px, 1sp = 1px for direct measurement.
  Should be re-checked on-device, which is exactly why the acceptance
  criteria calls for opening it on the real device.
- **20 preset names** are plausible guitarist-style bank names, not
  sourced from anywhere real — the issue only requires "plausible."
- Scope: nothing under `protocol/`, no Gradle file, nothing outside
  `docs/design/` was touched. The S6 registry (`s6-parameter-registry`
  branch) was read via `git show`, never checked out, imported, or
  merged into this branch.

## 9. Summary of what needs a decision

1. Confirm or correct the 6 promoted quick-tier parameters (§1) —
   specifically whether Noise Gate Threshold should swap in for one of
   the six.
2. Confirm the Master Volume dock's 0–10-primary/dB-secondary display
   choice (§1), and flag if the assumed linear dB conversion is
   materially wrong once S7 defines the real curve.
3. Rule on whether D1 needs a `color.status.destructive` token, or
   whether the placement/hierarchy-only approach to the revert flow (§4)
   is sufficient as designed.
4. Confirm the "Sync Division" inferred label (§2) for the `*_TS`
   selectors, or provide the real meaning if known.
5. Work the three S20 items (§7) into that story's hardware-verification
   pass.
6. The Cabinet/Tone-Model-Amp regrouping (§2) is a presentational choice
   for the screen, not a registry fact — flagged in case a different
   split reads better once built.
