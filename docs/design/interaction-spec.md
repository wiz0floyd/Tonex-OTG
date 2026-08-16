# D3 — Interaction & Information-Architecture Spec

**Story:** D3 (refs [#7](https://github.com/wiz0floyd/Tonex-OTG/issues/7), parent [#1](https://github.com/wiz0floyd/Tonex-OTG/issues/1))
**Depends on:** D1 (`docs/design/ui-design-tokens.md`) and D2 (`docs/design/mockups.html` / `mockups.md`) — both signed off and merged; applied here, not re-litigated, except where explicitly noted.
**Status:** Spec — no implementation, no application code touched. This document, not the D2 mockups, is the binding interaction contract for S17.
**Blocks:** S17 (implementation).

## 0. Scope, and how this relates to D2

D2 produced reviewable screens and, in `mockups.md`, already made two decisions with
real rationale attached: the six-parameter quick tier, and "hide the inactive
model's parameters entirely, don't gray them out." Both are treated here as
**settled, not re-decided** — restated with their full rationale so this document is
self-contained, but not reopened.

What D2 did *not* fully specify, because screen mockups aren't the right artifact for
it, is the actual **mechanics**: exactly which condition suppresses the first-write
warning after its first showing, exactly what widget a SELECT parameter with no
option labels gets, exactly what happens when the pedal changes preset out from under
an open editor screen. That's this document's job, and it's written so S17 can build
against it without having to make any of those calls itself.

Every real parameter name, abbreviation, and index below is read directly from
`protocol/src/main/kotlin/dev/tonexotg/protocol/params/ParameterRegistry.kt` and
`protocol/src/main/kotlin/dev/tonexotg/protocol/ParameterId.kt` (S6, merged into
`main`) — not invented, not the Revision-1/2 guesses D2's own revision history
documents having to walk back.

---

## 1. Quick tier vs. full tier — the v1 exposure decision

**This section is the direct answer to BRD [issue #1](https://github.com/wiz0floyd/Tonex-OTG/issues/1) §9 Open Question 4**
("minimum viable parameter set for v1 — full parity or reduced?").

**The answer has two halves, and they are deliberately decoupled, per issue #7's own framing:**

- **Protocol parity: full.** S6 already ports all 116 parameters (109 preset-scoped +
  7 global) regardless of what any screen shows. Nothing in this document removes a
  parameter from what the app *can* read or write.
- **UI exposure: tiered.** A **6-parameter quick tier**, reachable in one tap from the
  moment the parameter editor opens, plus a **persistent Master Volume dock**, plus a
  **full tier** exposing all 116 parameters (109 + 7) via a scrolling, categorized
  accordion. Nothing is permanently hidden — "full tier" is a deeper scroll, not a
  locked door — but only 6 parameters plus Master Volume are one tap away.

This decoupling is what issue #7 asked for explicitly: because the protocol layer
already carries everything, a future decision to promote or demote a quick-tier
parameter is a UI-only change with no protocol rework behind it.

### 1.1 The quick tier (6 parameters)

| # | Label | Enum name | Abbr · index | Range |
|---|---|---|---|---|
| 1 | Gain | `MODEL_GAIN` | `MDL GAIN` · 20 | 0–10 |
| 2 | Bass | `EQ_BASS` | `EQ BASS` · 11 | 0–10 |
| 3 | Mid | `EQ_MID` | `EQ MID` · 13 | 0–10 |
| 4 | Treble | `EQ_TREBLE` | `EQ TREBLE` · 16 | 0–10 |
| 5 | Reverb Mix | *resolves to the active reverb model's `*_MIX`* | e.g. `RVB PL M` · 62 | 0–100% |
| 6 | Delay Mix | *resolves to the active delay model's `*_MIX`* | e.g. `DLY DT X` · 102 | 0–100% |

**Rationale, per parameter:**

- **Gain, Bass, Mid, Treble** mirror the four knobs on the front panel of nearly every
  physical guitar amp a working guitarist has already used. Promoting exactly these
  four costs zero learning curve mid-set — a player doesn't have to learn what the app
  calls something they already know how to hear.
- **Reverb Mix and Delay Mix** are the two controls most commonly retouched *per song*
  to match a room or a part — not a setup-time decision like which reverb algorithm to
  use, but a live one. Both are **not single wire parameters**: the pedal stores one
  mix value per reverb model (`REVERB_SPRING1_MIX` … `REVERB_PLATE_MIX`, indices
  42/46/50/54/58/62, six total) and one per delay model (`DELAY_DIGITAL_MIX` idx 102,
  `DELAY_TAPE_MIX` idx 108). The quick-tier card always shows and edits whichever one
  is currently live, and makes that indirection visible with a `→ resolves to RVB PL M
  · Plate model is active` caption rather than picking one silently or forcing a
  seventh/eighth card per model.
- **Six is a hard ceiling**, not a rounding-down. At `touch.target.min` (64dp per D1
  §4.2) per card, six cards run to roughly 664dp — a short scroll on a ~915dp screen,
  not a wall of sliders a player has to hunt through mid-song. This ceiling is the
  actual reason Noise Gate and Compressor did not make the cut (below), not a
  statement that gate/comp are unimportant.

**Master Volume is deliberately *not* in the quick tier**, despite being the single
most-touched control on stage (`MASTER_VOLUME` / `MVOL`, idx 116, global,
−40..+3dB). It is `ParameterScope.GLOBAL`, not saved per preset — putting it in a
per-preset quick tier would misleadingly imply it resets when the preset changes,
which it does not. Instead it gets its own **persistent bottom dock**, present on both
the Preset List and Parameter Editor screens, using `surface.raised-1` (the same
token D1 assigns to "top app bar, bottom bar," §2.1) rather than `surface.raised-2`
(the per-preset param-card token) — so its global scope reads visually as chrome, not
as "part of whichever preset is open." The dock shows the pedal's own 0–10 display
scale as the primary numeral (matches the hardware screen, costing a guitarist
nothing new to read) with the engineering dB value as a small secondary caption.

**Explicitly considered and left out of the quick tier, one accordion-tap deeper in
the full tier:**

- **Noise Gate Threshold** (`NOISE_GATE_THRESHOLD` / `NG THRESH`, idx 2, −100..0dB).
  Genuinely useful, genuinely close to promotion — but gate threshold is typically set
  once per guitar/pickup setup, not retouched per song the way EQ or a wet/dry mix is.
  One tap into "All Parameters → Noise Gate" rather than costing a seventh quick-tier
  card.
- **Compressor** (Threshold `COMP_THRESHOLD` idx 7, Makeup Gain `COMP_MAKE_UP` idx 8,
  Attack `COMP_ATTACK` idx 9). Same reasoning as Noise Gate: a compressor is dialed in
  once per tone, not per song, so it does not clear the "retouched live" bar that
  earned EQ and the two mix knobs their spots. Reachable via the "Compressor"
  accordion in the full tier (48dp rows, `touch.target.secondary`, since browsing
  categories is a between-songs action).

**On the difference from issue #7's own illustrative list** ("realistically gain,
volume, EQ, reverb mix, gate, comp"): that list was scoping shorthand in the issue
text, not a ruling. Against the real registry, Volume is global (excluded for the
reason above, given its own dock instead) and Gate/Comp lose out to the six-card
ceiling in favor of Delay Mix, which the issue's illustrative list didn't anticipate
because the banked-mix structure (one mix value per model, not a single wire
parameter) wasn't yet known when the issue was scoped. The six-parameter set above is
the actual, registry-grounded answer.

### 1.2 The full tier: real registry structure

The 109 preset-scoped parameters group into **8 categories** (matching the registry's
own contiguous wire blocks, with one presentational regrouping noted below), plus the
7 globals:

| Category | Count | Notes |
|---|---|---|
| Noise Gate | 5 | Post, Enable, Threshold, Release, Depth — flat |
| Compressor | 5 | Post, Enable, Threshold, Makeup Gain, Attack — flat |
| EQ | 8 | Post + 3×(band, freq) for Bass/Mid/Treble + Mid Q — flat |
| Tone Model / Amp | 8 | Amp Enable, Switch 1, Gain*, Volume, Mix, Cabinet (`CABINET_UNKNOWN`), Presence, Depth |
| Cabinet | 10 | Mode selector (`CABINET_TYPE`) + 9 VIR mic params, live only in VIR mode |
| Modulation | 31 | 3 always-on (Post/Enable/Model) + 28 banked across 5 models |
| Delay | 15 | 3 always-on (Post/Enable/Model) + 12 banked across 2 models |
| Reverb | 27 | 3 always-on (Position/Enable/Model) + 24 banked across 6 models |
| **Total preset-scoped** | **109** | |
| Global | 7 | BPM, Input Trim, Cab Sim Bypass, Tempo Source, Tuning Reference, Bypass, Master Volume |

*Gain (`MODEL_GAIN`) is promoted to the quick tier; it also appears in its home
category below the quick-tier cut line, per §3's "promoted" marker convention, so a
player browsing the full tier doesn't wonder where it went.

`CABINET_TYPE` (idx 24) is grouped into "Cabinet" here, alongside the 9 VIR mic
parameters (idx 25–33), rather than with the rest of the Tone Model/Amp block it sits
adjacent to on the wire (idx 18–24) — a presentational choice for the screen, carried
over unchanged from D2, not a claim about wire layout.

**Live parameter count per preset is 51–61 of 109**, depending on which
modulation/reverb/delay model and cabinet mode the preset uses — see §2 for why the
other 48–58 are never rendered at all rather than shown disabled.

### 1.3 Navigating from quick tier to full tier

The Parameter Editor is **one continuous scrolling screen**, not two separate routes:
the quick tier's 6 cards sit at the top, immediately followed by an "All Parameters"
section header and the 8-category + Global accordion, all under the same app bar and
snapshot indicator. There is no button, tab, or navigation action between them — a
player scrolls down.

This is an explicit IA decision this document is making (D2's screens split "Quick
Tier" and "All Parameters" into separate mockup frames purely for reviewer
readability — the "All Parameters" frame carries no app bar of its own, which is the
tell that it was always meant to read as a continuation of the same screen, not a
second one). Making it one screen rather than two removes a navigation decision a
player would otherwise have to make correctly under stage pressure ("where did the
rest of the parameters go?"), at the cost of a longer scroll for anyone who dives deep
into a banked block. Given the full-tier accordion starts collapsed (§2), the added
scroll length before reaching a specific category is small.

---

## 2. Model-switched disclosure rules

Four parameters select among mutually-exclusive parameter blocks that all exist on
the wire simultaneously:

| Selector | Enum name · abbr · index | Models | Params/model | Live at once |
|---|---|---|---|---|
| Reverb model | `REVERB_MODEL` · `RVB MODEL` · 38 | Spring1–4, Room, Plate (6) | 4 each (Time/Predelay/Color/Mix) | 4 |
| Modulation model | `MODULATION_MODEL` · `MOD MODEL` · 65 | Chorus, Tremolo, Phaser, Flanger, Rotary (5) | 5–6 each | 5–6 |
| Delay model | `DELAY_MODEL` · `DLY MODEL` · 96 | Digital, Tape (2) | 6 each | 6 |
| Cabinet mode | `CABINET_TYPE` · `MDL CAB` · 24 | Tone Model, VIR, Disabled (3) | VIR only: 9 mic params | 0 or 9 |

### 2.1 The mechanism: hidden entirely, not grayed, not collapsed

**A model-selector chip row sits above each block. Choosing a chip swaps which block
renders beneath it; every other model's rows are not rendered at all** — not
`display:none`, not present-but-disabled, not a collapsed-but-expandable accordion
section. Switching is instant, within D1's 150ms motion ceiling (effectively no
transition — direct replace), because D1 §5 forbids any animation or transition from
sitting between a tap and its effect.

**Two alternatives were considered and rejected:**

- **Grayed-out / disabled rows for inactive models.** Rejected because it is the exact
  failure mode issue #7 names explicitly: "rendering all six reverb sets at once
  would be actively misleading." A grayed Spring1 Time/Predelay/Color/Mix block next
  to a live Plate block still visually claims "these four rows exist and matter right
  now," when touching them would do nothing audible. It also blows past the "short
  scroll, not a wall of sliders" restraint principle §1 relies on — the full tier
  would have to render all 24 reverb rows on every preset instead of 4.
- **Collapsed-but-present accordion sections per model.** Rejected for a subtler
  reason: a collapsed section still implies "there are 6 independently browsable
  states here, expand any of them" — true at the *storage* level (each model's values
  are real, independently held) but false at the *audible* level (only one is ever
  live). Keeping the collapsed rows out of the DOM/composition entirely removes the
  temptation to "peek" at a state that currently does nothing, which is the same
  reasoning the "hidden entirely" choice already applies to graying.

Both alternatives were also already rejected in D2 (`mockups.md` §0, "the ... six-item
quick-tier ceiling, hiding banks entirely rather than graying them out ... is
unchanged and not re-litigated here") — restated here with the reasoning spelled out
because that reasoning is what S17 needs, not just the outcome.

### 2.2 What happens to the values of a model you switch away from

**They are not reset, zeroed, or discarded.** Each model's parameters are real,
independent wire indices (e.g. `REVERB_SPRING1_MIX` idx 42 and `REVERB_PLATE_MIX` idx
62 are two different parameters, both always present in the preset's 109-value
state). Switching the model selector changes what's *audible* and what's *rendered*;
it does not touch any parameter belonging to a model that isn't currently selected.
Switching back to a previously-set model later must re-render that model's block with
its actual current values (read from the same preset-state cache the rest of the
editor already holds), not with `ParameterSpec.default` placeholders — `default` is
explicitly documented as a load-time seed value, not authoritative pedal state (see
`ParameterRegistry.kt`'s own KDoc), so it must never be used to paint a screen once
real values have been read.

### 2.3 Model selection is itself a parameter write

Tapping a model chip is not purely a local UI-state change — `REVERB_MODEL` /
`MODULATION_MODEL` / `DELAY_MODEL` / `CABINET_TYPE` are themselves ordinary
preset-scoped SELECT parameters, and choosing a chip issues the same kind of
single-parameter write as any slider. Two consequences worth being explicit about for
S17:

- It counts toward the first-destructive-write condition in §5 — the first time a
  player switches a reverb model this session is exactly as "destructive" as the first
  time they move a slider, and should trip the same one-time warning if nothing has
  before it.
- Because model selection is a real write, not a preview, there is no "pending" model
  choice to reconcile if a player is mid-drag on the currently-visible block's slider
  when they tap a different model chip — the slider's last-sent value is already
  committed (drags write continuously; see §3 and §6), so tapping the next chip is
  simply the next ordinary action, with nothing left hanging from the one before it.

---

## 3. Slider + numeric entry (FR9)

FR9 requires exact numeric entry as an alternative to a relative slider drag. The
risk it names — a drag gesture fighting a simultaneous numeric field — is resolved
**architecturally**, not with a tap/long-press timing heuristic:

**The slider and the numeric-entry affordance are two separate, non-overlapping tap
targets on the same card, so no touch ever has to be disambiguated between "drag" and
"open the keypad."**

- The **slider band** spans the full card width, with a `touch.target.min` (64dp)
  invisible hit/drag band centered on the 8dp visual track (per D1 §4.2 — the visual
  track stays slim, but the tappable/draggable region around it is full-size). Any
  touch-and-move here is a drag.
- The **numeric value chip**, top-right of the card, is a distinct `48dp`
  (`touch.target.secondary`) tappable element. A tap here — anywhere on the chip, no
  drag — opens a bottom-sheet numeric keypad for that parameter. It never
  participates in the slider's drag gesture because it isn't inside the slider's hit
  region.

Because there is no shared touch surface, there is nothing to arbitrate — a drag on
the track is always a drag, a tap on the chip is always "open exact entry." This is
the concrete pattern S17 implements: **do not** build this as an editable text field
overlaid on or adjacent to the slider thumb where a drag could be misread as text
selection; keep the two controls in physically separate regions of the card, as D2's
mockup already lays them out.

### 3.1 The numeric-entry sheet (RANGE-type parameters)

A bottom sheet (per D2 screen 2b): grabber, parameter name, a large numeric field
with unit suffix, a 3-column keypad (`1`–`9`, `.`, `0`, `⌫`), and two `touch.target.min`
(64dp) actions — `Cancel` (outline) and `Set` (accent-filled). Every key is sized to
64dp rather than the 48dp chrome floor, because dialing in an exact value fast
between songs is a mid-performance-adjacent action, not settings chrome.

- **Entry is never blocked for being out of range.** A player can type `99` for a
  0–10 parameter; nothing prevents the keystroke. Validation happens at `Set`:
  the entered value is clamped to the parameter's registered `min`/`max` (visibly,
  before the write goes out) rather than rejected. This deliberately mirrors the
  clamp-don't-reject policy `ParameterWriteMessage.kt` already documents at the wire
  level ("an out-of-range value has one unambiguous, safe interpretation — the
  nearest in-range value") — the UI should not invent a stricter or different policy
  than the write path it's calling into.
- **Cancel** discards the sheet with no write. **Set** commits immediately (same
  single-parameter write as a slider release — no additional confirmation step; this
  is an ordinary edit, not a destructive one beyond what §5 already covers), the
  sheet closes, and the card's slider updates to match.
- Precision and unit suffix follow the parameter's own `ParameterSpec.unit` (dB, ms,
  Hz, RPM, BPM, %, or none for the dimensionless 0–10 knobs) — already demonstrated
  per-parameter in the D2 mockup's rendered rows. Exact decimal-place defaults (e.g.
  one decimal for 0–10 float knobs, whole numbers for ms/Hz/RPM/BPM) are an S17
  implementation detail within this pattern, not a product decision this document
  needs to pin down further.

### 3.2 Non-RANGE parameters get a different interaction, not a keypad

A numeric keypad only makes sense for a continuous `RANGE` value. The registry has
two other parameter shapes, and each needs its own affordance — a gap D2's mockup
left open (it only demonstrated the keypad for `MODEL_GAIN`, a RANGE parameter), so
this is where D3 closes it:

- **`SWITCH`** (e.g. `NOISE_GATE_ENABLE`, `EQ_POST`, `DELAY_DIGITAL_MODE`): a one-tap
  toggle, no sheet at all — matches the mockup's existing `.mini-row.switch`
  treatment.
- **`SELECT` with known option labels** (the four model selectors in §2, and
  `CABINET_TYPE`'s Tone Model/VIR/Disabled modes): the horizontal chip row from §2 —
  tapping a chip *is* the entire interaction. No separate numeric affordance is
  needed or shown.
- **`SELECT` with *no* option labels** — `VIR_CABINET_MODEL` (0–10), `VIR_MIC_1` and
  `VIR_MIC_2` (0–2 each), and the six `*_TS` "Sync Division" selectors banked under
  every reverb/mod/delay model (0–17 each) — get a **stepper**: the raw numeric index
  shown large in the middle, flanked by `−`/`+` buttons (48dp each,
  `touch.target.secondary`), incrementing or decrementing by exactly 1 and clamping
  at 0/max. This is a deliberate, non-obvious call: a numeric keypad here would let a
  player type `7` for an 18-option selector with no way to know what option 7 *is*,
  and would wrongly imply a continuous range rather than discrete steps; a chip row is
  unusable at 11–18 unlabeled options. A stepper is the only one of the three patterns
  that's honest about "discrete, unlabeled, pick by number" without inventing option
  names the registry doesn't provide (matching D2's own refusal to invent mic-brand
  names for these same rows).

---

## 4. Navigation and active-preset visibility

### 4.1 The two screens

**Preset List** and **Parameter Editor** (which, per §1.3, contains both the quick
tier and the full tier in one scroll). Settings (containing the revert flow, §5) and
About are reached from the Preset List's app bar and are not stage-time screens.

**A single tap on a preset row both selects it on the pedal and navigates into its
Parameter Editor.** There is no separate "select" vs. "open editor" step. This
matches D1 §5's zero-motion-gating rule — the pedal write and the screen transition
both happen immediately and optimistically on the same tap, with no dialog or
intermediate confirmation — and matches the most common stage need ("switch preset,
then maybe touch one knob") rather than the less common one ("flip through several
presets by ear without opening any of them"). The editor's `←` back arrow returns to
the Preset List with scroll position preserved.

**Flagged as a genuine toss-up:** combined select+navigate optimizes for the
"select-then-tweak" flow but costs an extra back-tap for a player who wants to A/B
two or three presets by ear in quick succession without editing either — under the
current design each preset check requires opening its editor and backing out again.
An alternative (tap selects only, staying on the list; a second, distinct action —
e.g. tapping the "Now on the pedal" card, or a per-row chevron — opens the editor)
would serve that flow better at the cost of an extra tap for the more common
select-then-tweak case. This has real, opposite-direction stage-time consequences
depending on how a given player actually works, and is called out here rather than
decided silently.

### 4.2 Keeping the active preset legible

- **On the Preset List:** the "Now on the pedal" card at the top uses D1's 57sp
  `type.display.preset` — the one non-negotiable, arm's-length type tier — and is
  **not** pinned/sticky; it scrolls away with the list, matching D2's actual layout
  (only the Master Volume dock is `position: sticky` in D2's markup; the now-playing
  card is not, and D2 gave no explicit reason either way). The active row itself
  additionally gets `surface.raised-3` fill, an `accent.primary` border, and a
  checkmark — this is the scroll-persistent fallback: once the giant banner scrolls
  off, the highlighted row is still findable by shape and color while scanning the
  list.
- **On the Parameter Editor:** the preset name lives in the app bar at `type.title`
  (22sp), not the 57sp tier, but the app bar never scrolls away — so this is a
  deliberate trade of size for permanence. The list screen's job is "announce what's
  live" (hence the giant type); the editor screen's job is "let you work" on a preset
  you already knowingly navigated into, so a smaller-but-always-visible confirmation
  is the right trade there, not a redundant giant repeat of the same name.

**Flagged as a genuine toss-up:** should the "Now on the pedal" card be pinned (sticky
to the top of the Preset List's scroll), the way the Master Volume dock is pinned to
the bottom? Arguments for: it keeps the single most peripherally-legible text on
screen at all times, which is exactly the arm's-length requirement D1 opens with, and
D2 never actually argued *against* pinning it — it simply didn't do it. Arguments
against: it permanently costs vertical space on a 20-row list, and the active row's
own highlight already gives scroll-persistent legibility once you're past the banner,
so the marginal benefit of pinning may be small. This document keeps D2's non-sticky
behavior as the v1 default — a signed-off screen isn't overturned without a stated
reason for reversing it, and none is being asserted here — but flags it explicitly
since it's an arguable, stage-safety-relevant trade-off either way.

---

## 5. Revert flow and first-write warning

### 5.1 Revert

The entry point lives in **Settings**, not on any screen touched mid-song — physical
separation is the first safety layer. It's a `touch.target.secondary` (48dp),
`btn-outline` "Revert…" button: sized and styled as an available option, not an
inviting one.

Tapping it opens a confirmation dialog that **inverts the normal button hierarchy**:
`Cancel` gets the accent-filled, visually primary treatment; `Revert Anyway` is a
plain outline button. The safe path looks like the default path — this inversion,
not color-coding or size, is the actual "hard to hit by accident" mechanism. Copy
states the concrete consequence rather than a generic "Are you sure?": *"This
immediately rewrites all 109 parameters of [preset] back to their values from when it
became active. The pedal auto-saves — this can't be undone either."*

**Mechanically**, per `PresetSnapshot.kt` (S9b, merged): revert replays the snapshot
as **109 individual per-parameter writes**, never a single whole-state write — the
same reasoning `PedalState.kt` documents for why whole-state writes are the dangerous
path this project exists to avoid (a stale whole-device echo silently reverts globals
a snapshot never touched). Only the 109 preset-scoped values are affected; the 7
globals (Master Volume, BPM, tuning reference, etc.) are untouched, and the confirm
copy says so.

**Inherited, not re-opened here:** D2 flagged that D1 has no dedicated
"destructive-action" color token, and deliberately did not repurpose `status.error`
(scoped to connection state) for the "Revert Anyway" button — safety is carried by
placement and button-hierarchy inversion instead. That's a D1 (visual-token) question,
not a D3 (interaction) one, so it isn't re-decided here; noted only so it stays
tracked if D1 is revisited.

### 5.2 First-destructive-write warning

**Trigger — precisely:** the dialog fires the moment the **first preset-scoped
(`ParameterScope.PRESET`) write actually goes out on the wire this session** — i.e.
the moment that would flip `SnapshotStore.hasWarnedThisSession()` from `false` to
`true` for the first time. Concretely, this can be:

- the first movement of the first slider drag a player makes (drags write
  continuously and in near-real-time per NFR2 — see §6 — so the *first* write may be
  the first millimeter of movement, not only a final released value or a keypad
  `Set`),
- the first switch toggle,
- the first SELECT chip tap, **including a model-selector chip** (§2.3 — model
  selection is itself a preset-scoped write),
- or the first stepper tap.

**Global writes never trigger it.** Master Volume, BPM, Input Trim, and the other
5 globals sit outside `PresetSnapshot`'s 109-parameter scope (`PresetSnapshot.kt`
§"Scope and immutability"), so a revert can't touch them either way — warning about
an "irreversible write" that revert wouldn't have protected against anyway would
mislead in the opposite direction.

**Presentation does not gate the write.** Per D1 §5's zero-motion-gating rule, the
write fires immediately; the dialog appears as a heads-up describing what just
happened, not a confirmation the player must clear first. It has a single `Got it`
button, no `Cancel` — dismissing the dialog *is* proceeding, because proceeding
already happened. Copy: *"Tonex-OTG writes every change straight to the pedal as you
make it. There's no undo on the pedal itself. A snapshot of [preset] was taken the
moment it became active. You can revert to it any time from Settings — until then,
experiment freely."*

**Suppression condition — the exact rule S17 implements:**

> Check `SnapshotStore.hasWarnedThisSession()` immediately before showing the dialog.
> If `true`, never show it, for the rest of this connection, regardless of which
> preset or which parameter triggers the next destructive write. The first time it
> *is* shown, call `SnapshotStore.markWarned()` (idempotent, per that method's own
> contract).

No separate app-level flag is needed — `SnapshotStore` already models exactly this
gate; the dialog is the direct UI for that existing flag, not a new piece of state.

**Why this doesn't become nagware, stated precisely:** "session" here is
`SnapshotStore`'s own lifetime, which is cleared on disconnect (`SnapshotStore.clear()`,
documented as running "e.g. on disconnect") — the same event boundary that ends the
current `SessionId` every `PresetSnapshot` is stamped with. So the warning reappears
exactly once per physical USB connection, never twice within one, and a reconnect
(potentially a different day, a different gig) is treated as a legitimate reason to
re-orient the player once, not as an excuse to nag on every subsequent slider touch
within the same plug-in.

---

## 6. External preset change (FR6)

### 6.1 Why this needs a deliberate answer, not just "update the UI"

Per `PedalState.kt`'s documented contract, a footswitch-driven preset change arrives
at the app as the pedal pushing a fresh, unsolicited state blob — the app reacts to a
push, it does not poll. `PresetSnapshot.kt` already requires re-snapshotting
"whenever the active preset changes, including externally at the footswitch (FR6)," so
the protocol-level bookkeeping is already specified. What's missing is the UI
reaction, and it is not a purely cosmetic question: **`ParameterWriteMessage`'s
single-parameter write path addresses whatever preset is currently active on the
pedal — the wire payload carries only a parameter index and a value, no preset
index.** If the Parameter Editor kept showing Preset A's values after the pedal
silently moved to Preset B, the next slider touch would write to Preset B while the
screen still displays and labels itself as Preset A — a silent misdirected-edit
hazard, precisely the class of failure NFR1 exists to prevent (the BRD's own
cautionary precedent is an earlier version of the reference project that "would
overwrite the pedal global settings").

### 6.2 The behavior, by screen state

- **On the Preset List:** the "Now on the pedal" card and the active-row highlight
  update immediately and silently — no dialog, no toast. This is FR6's "reflects"
  requirement in its simplest form, and needs no special handling since nothing the
  player is doing is invalidated by it.
- **On the Parameter Editor, viewing a *different* preset than the one that just
  became active:** the editor **must immediately swap** to the new preset — app-bar
  title, all six quick-tier cards, and any expanded full-tier accordion rows all
  update to the new preset's live values, and a fresh snapshot is taken per
  `PresetSnapshot.kt`. This is forced by the protocol reality above, not a stylistic
  preference: continuing to show stale values that no longer match what a write would
  actually hit is a worse failure mode than the disruption of the screen changing
  under the player's eyes.
- **A transient indicator accompanies the swap**, since it can happen while a
  player's thumb is still near the screen and needs a reason, not just a changed
  screen: a low-emphasis, auto-dismissing snackbar — *"Pedal switched to [Preset
  Name] (footswitch)"* — for roughly 2–3 seconds, non-interactive (no
  `touch.target` sizing needed, since it isn't tapped), built from existing D1 tokens
  only (`surface.raised-2` background, `on-surface.secondary` text — no new
  color/status token is introduced for this).

### 6.3 In-flight interactions when the swap happens

If a player is **mid-drag on a slider or has a numeric-entry sheet open** for the
preset that just got swapped out from under them, the in-flight interaction is
**cancelled without committing**: an open keypad sheet closes with no write; an
active slider drag stops updating and does not send a final write for the stale
touch.

**Flagged as a genuine toss-up:** the alternative — let the in-flight gesture
complete and simply write to whatever preset is now active, since the wire protocol
doesn't distinguish anyway — was considered and rejected here, on the reasoning that
a player whose thumb is still moving when the screen changes almost certainly meant
to affect the preset they were looking at when they started the gesture, not
whichever one the footswitch just made current a moment later; letting a stale touch
land a real, unintended write is judged the worse failure mode versus an occasionally
dropped gesture. This is a real behavioral choice with opposite-direction
consequences (a dropped slider touch is itself a surprise, just a safer one), so it's
named explicitly rather than assumed obvious.

---

## 7. Summary — flagged for product-owner review before merge

Everything else in this document is a decision with stated rationale, not a request
for input. These four are genuine toss-ups with real, opposite-direction
product-facing consequences, carried here rather than resolved silently:

1. **§4.1 — Combined select+navigate on a preset-list tap**, vs. separate
   select/open-editor actions. Current default: combined (optimizes for
   select-then-tweak; costs an extra back-tap for rapid A/B browsing).
2. **§4.2 — Whether the "Now on the pedal" card should be pinned/sticky** at the top
   of the Preset List's scroll, the way the Master Volume dock is pinned at the
   bottom. Current default: not pinned, matching D2's shipped (if unargued) choice.
3. **§6.3 — Drop vs. complete an in-flight slider/keypad gesture** when the pedal's
   active preset changes externally mid-touch. Current default: drop, on the
   reasoning that a misdirected write is worse than a dropped gesture.
4. *(Inherited from D2, not new here)* — whether D1 needs a dedicated
   `color.status.destructive` token for the revert flow, vs. the placement/hierarchy-only
   approach this document keeps in §5.1. This is a D1 question; flagged so it isn't
   lost, not re-argued here.
