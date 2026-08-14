# D2 — Screen Mockups: Rationale

**Story:** D2 (refs [#6](https://github.com/wiz0floyd/Tonex-OTG/issues/6), parent [#1](https://github.com/wiz0floyd/Tonex-OTG/issues/1))
**Depends on:** D1 (`docs/design/ui-design-tokens.md`) — binding, applied not re-decided.
**Artifact:** `docs/design/mockups.html` — open on the Pixel 10 Pro for sign-off.

This document explains the non-obvious calls in the mockups, especially the
109-parameter tiering, and lists open questions for the lead. It is a
companion to the HTML, not a restatement of it — read the HTML's own
per-screen annotations first; this covers what didn't fit in a margin note.

---

## 1. The quick tier: what and why

**Promoted (6):** Gain, Bass, Mid, Treble, Reverb Mix, Delay Mix.

Reasoning:

- **Gain, Bass, Mid, Treble** mirror the four knobs on the front panel of
  almost every physical guitar amp a working guitarist has used for years.
  Promoting exactly these four costs zero learning curve on stage — the
  player already has muscle memory for "turn this one down when the room's
  boomy." That's the single strongest selection criterion I used: not
  "what's technically most impactful" but "what does a guitarist's hand
  already know how to reach for without reading a label."
- **Reverb Mix and Delay Mix** are the two knobs most commonly retouched
  *per song* to match a room or a part (drier for a tight rhythm part,
  wetter for an ambient intro) without wanting to dig into a full effect
  submenu (rate, feedback, decay, tone). Their *Mix* control is promoted;
  their other parameters (Time, Feedback, Decay, Type, etc.) are not —
  those get set once during rehearsal, not mid-set.
- Six was a deliberate ceiling, not a rounding. At `touch.target.min` (64dp)
  per card plus 12dp inner padding and 8dp gaps, six cards run to roughly
  6×(64+~40)+5×8 ≈ 664dp of vertical space — enough to need a short scroll
  on a ~915dp-tall screen but still comfortably "the first thing you see,"
  not a second wall of sliders. Going to eight or ten would have
  reintroduced exactly the "wall of sliders" the story explicitly warns
  against (see the issue's link to D3).
- **Runner-up, deliberately left out:** Noise Gate Threshold. Genuinely
  useful mid-set on noisy stages, but I judged it fights for the same
  "make an opinionated cut" budget as the six above and lost narrowly — it
  is one accordion-tap away in "All Parameters → Noise Gate" rather than
  a seventh quick-tier card. Worth the lead's sign-off specifically: if
  hum/noise complaints are a bigger real-world pain point than I'm
  modeling, swap it in for one of the six.
- **Master Volume is explicitly excluded from the quick tier**, even
  though "always want it handy" is a strong argument for including it.
  It's a *global* parameter — device-wide, not saved per preset (confirmed
  by `protocol/.../ParameterId.kt`'s `ParameterScope.GLOBAL` doc comment,
  which enumerates it alongside BPM, Input Trim, Cab Sim Bypass, Tempo
  Source, Tuning Reference, and Bypass). Putting it inside a per-preset
  quick tier would visually imply it resets or varies with the preset,
  which it doesn't and mustn't. The mockup instead shows it living in the
  "Global — not saved to any preset" section of the full parameter list
  (screen 2c). **Open question:** should Master Volume additionally get
  its own always-visible affordance (e.g. a persistent control outside any
  preset screen, closer to how a real amp's volume knob is never "inside"
  a channel), rather than requiring a trip into the full parameter list?
  I did not design that in this pass — it felt like new scope (a
  persistent global control surface) rather than a D2 layout call, and I'd
  rather flag it than invent it silently.

## 2. The 103 buried parameters: category structure

The full parameter list is grouped by signal-chain block (Noise Gate →
Compressor → Amp/Preamp → Power Amp → Tone Model → Cabinet → EQ →
Modulation → Delay → Reverb → FX Loop → Expression/Wah), collapsed by
default, at `touch.target.secondary` (48dp) rows — correctly the smaller
tier per D1 §4.2, since menu-diving through 109 parameters is a
between-songs action, not a mid-song one.

**This category breakdown is illustrative, not authoritative.** I checked
the repo for a recovered 109-parameter name table before inventing
anything, and found none: `ParameterSpec`/`ParameterId.kt` defines the
*shape* a parameter takes (id, scope, name, type, min/max/default, unit)
but the actual 109-entry registry is explicitly `TODO`'d out to S6
(`PresetSnapshot.valueOf`/`toMap` are literal `TODO()`s pointing at "S6:
look up id's position"). So there is nothing yet in this repo to be
faithful *to* for the buried 103. Rather than block on that or invent
names with no grounding, I built category names and counts (summing to
109, matching FR9's number) that follow the known ToneX-style
signal-chain architecture — gate, comp, amp, cab, EQ, mod, delay, reverb —
so the mockup demonstrates the *tiering pattern* honestly rather than
reading as lorem ipsum, without asserting these are the real upstream
names. The **7 global parameters are the one part of this I can call
authoritative**: they're copied verbatim from `ParameterId.kt`'s own doc
comment enumerating `ParameterScope.GLOBAL` (BPM, Input Trim, Cab Sim
Bypass, Tempo Source, Tuning Reference, Bypass, Master Volume).

**Flag for the lead:** when S6's real registry lands, someone (S6, D3, or
a follow-up to D2) needs to reconcile the illustrative categories/counts
here against the real 109 names. I do not consider this mockup's category
list a spec — it's a demonstration that *a* sensible grouping exists and
fits the space, not a claim about which specific parameter lands in which
bucket.

## 3. Snapshot / revert / first-write-warning

Two things I resolved by reading `protocol/` (read-only — I did not touch
it) that shifted the design slightly from the story's own framing:

- The story text says "a snapshot taken when it connects." The actual
  `SnapshotStore` contract snapshots **per preset, when that preset
  becomes active** (including when the pedal itself changes preset via
  its footswitch), and requires re-snapshotting on every preset change —
  a snapshot from a previous preset is treated as *worse than none*. I
  designed the copy around this more precise model ("Snapshot taken when
  this preset became active") rather than "at connect," since the latter
  would be describing a stale/wrong mental model to the player.
- Revert replays the snapshot as **per-parameter writes**, never a
  whole-state write (the doc comment on `PresetSnapshot` is explicit that
  whole-state writes are "the dangerous path" this project exists to
  avoid), and covers only the 109 preset-scoped parameters — globals are
  untouched by a revert. The confirm dialog's copy says this explicitly
  ("global settings... are not affected") so a player isn't surprised
  their volume or tuning reference moved.
- `SnapshotStore.hasWarnedThisSession()` / `markWarned()` already model
  exactly a once-per-session gate — I designed the warning dialog as the
  direct UI for that existing flag rather than inventing new state.

**"Hard to hit by accident" was solved without color or size**, on
purpose:

1. Physical separation — the revert action lives in Settings, not on the
   screen a player is actively touching mid-song.
2. The entry point uses `touch.target.secondary` (48dp) and outline
   (unfilled) button styling — sized and styled as an available option,
   not an inviting one.
3. The confirmation dialog **inverts the normal button hierarchy**:
   Cancel gets the accent-filled, visually primary treatment; "Revert
   Anyway" is a plain outline button. The safe path looks like the
   default path.
4. Copy states the concrete consequence (all 109 parameters, immediate,
   irreversible) instead of a generic "Are you sure?"

**Open question for the lead:** D1 defines `color.status.error` strictly
within §2.3, titled "Connection status" — it is not offered as a general
"destructive action" token. I deliberately avoided reusing status-error
red on the "Revert Anyway" button, reasoning that doing so risks the
exact confusion D1 warns against for accent color ("this is
selected/interactive" vs "this is a status reading") — a red revert
button next to a red connection-error banner could misread as *itself*
being an error state. I solved "this is dangerous" entirely through
placement, sizing, and button-hierarchy inversion instead. If a future
screen wants a clearly-destructive red affordance, D1 will need a new
token (e.g. `color.status.destructive`, or an explicit ruling that
`status.error` may double for it) — I did not invent one, per the
brief's instruction to stop and flag rather than invent.

## 4. Connection states

D1 pins four states; `protocol/`'s actual `ConnectionState` sealed
interface has six (`Idle`, `Connecting`, `Hello`, `GetState`, `Ready`,
`Error`). I mapped them for the UI as:

| Protocol state | UI state shown |
|---|---|
| `Idle` | Not Connected |
| `Connecting`, `Hello`, `GetState` | Connecting… |
| `Ready` | Connected |
| `Error(cause)` | Error, with `cause.message` surfaced |

This matches D1 §2.3's own note that `Connecting` is "the gap between
starting a connection attempt and getting a response" and should read as
"in progress," not as three separately-explained sub-states — collapsing
the three-stage handshake into one UI state is squarely what D1 already
called for.

The mockup shows both the compact app-bar chip (worn on every screen,
answering "am I still connected?" peripherally) and escalated banners for
the two unhappy paths (Disconnected, Error), per the issue's explicit ask
to "design the unhappy paths properly." The error banner uses a realistic
`TransportFailure` message shape (`"Transport failure: <cause>"`) matching
`TonexError.kt`'s actual message format, not a placeholder string.

## 5. Contrast — what I actually checked

D1 §2.4 verifies a specific set of pairs. Several pairs used in this
mockup aren't in that table (e.g. secondary/tertiary text on
`raised-1`/`raised-2`, accent on `raised-1`/`raised-2`, status colors on
`raised-2`). I computed these myself from sRGB relative luminance (same
method D1 states it used, not eyeballed) rather than assume they pass
because the darker-surface pairs do:

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

The narrowest pass (`status.error` on `raised-2`, 4.74:1 against a 4.5:1
text requirement) isn't actually used as *text* anywhere in this
mockup — I kept error-colored text on `surface.base` or `raised-1` only
(both comfortably higher-margin per D1's own table) and used `raised-2`
only for the error glyph, which is graphical (3:1 floor). Flagging the
number anyway since it's the tightest margin I found.

## 6. Other assumptions, flagged

- **Viewport width.** Neither D1 nor the issue states the Pixel 10 Pro's
  exact dp width. I assumed ~412dp (typical of recent Pixel Pro-class
  phones, since Android normalizes dp width across pixel densities) and
  built the mockup at `max-width: 412px` with 1dp = 1px, 1sp = 1px, so
  spacing and touch targets can be measured directly in the browser. If
  the device's actual dp width differs meaningfully, the proportions
  (especially the 64dp targets relative to screen width) should be
  re-checked on-device rather than trusted from this number alone — this
  is exactly why the acceptance criteria calls for opening it on the real
  device rather than signing off from a description.
- **20 preset names** are plausible guitarist-style bank names
  (`CLEAN AMBIENT`, `METAL DROP`, etc.), not sourced from anywhere real —
  the issue only requires "plausible," not literal.
- I did not touch anything under `protocol/`, any Gradle file, or
  anything outside `docs/design/`, per this story's scope boundary. Every
  fact I cited from `protocol/` (global parameter names, snapshot
  semantics, connection-state shape, error message format) was read, not
  written.

## 7. Summary of what needs a decision

1. Confirm or correct the 6 promoted quick-tier parameters (§1) —
   specifically whether Noise Gate Threshold should swap in for one of
   the six.
2. Decide whether Master Volume needs a persistent, always-reachable
   control of its own beyond "inside the global settings list" (§1).
3. Rule on whether D1 needs a `color.status.destructive` token, or
   whether the placement/hierarchy-only approach to the revert flow (§3)
   is sufficient as designed.
4. Nothing here should be treated as the final 109-parameter taxonomy
   (§2) — that's D3's job; this mockup only proves the tiering pattern
   fits and reads well.
