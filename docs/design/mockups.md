# D2 — Screen Mockups: Rationale

**Story:** D2 (refs [#6](https://github.com/wiz0floyd/Tonex-OTG/issues/6), parent [#1](https://github.com/wiz0floyd/Tonex-OTG/issues/1))
**Depends on:** D1 (`docs/design/ui-design-tokens.md`) — binding, applied not re-decided.
**Artifact:** `docs/design/mockups.html` — open on the Pixel 10 Pro for sign-off.

This document explains the non-obvious calls in the mockups, especially the
109-parameter tiering, and lists open questions for the lead. It is a
companion to the HTML, not a restatement of it — read the HTML's own
per-screen annotations first; this covers what didn't fit in a margin note.

---

## 0. Revision 2 — the real parameter table changed the structure

The lead recovered the real parameter table after the first pass and it
invalidated a structural assumption: **64 of the 109 preset parameters are
not independent — they belong to four mutually-exclusive model banks**
(Reverb: 6 models × 4 params = 24; Modulation: 5 models, 28 params;
Delay: 2 models × 6 params = 12; Cabinet's VIR mic block: 9 params, live
only in VIR mode), each gated by its own selector. Only one model's block
per selector is ever live. Real live parameter count is roughly 52–61 of
109, not 109.

This forced three concrete changes, covered in detail in §2 below:

1. **The "All Parameters" screen (2c) now shows a model-selector chip row
   at the top of Reverb, Modulation, Delay, and Cabinet**, rendering only
   the active model's block and *hiding* — not graying out — every other
   model's parameters. The previous revision showed all six reverb
   models' parameters flat and simultaneously, which would have let a
   player edit a "Spring 2" knob that does nothing while Plate is active.
   That was wrong, not just incomplete.
2. **The quick tier's "Reverb Mix" and "Delay Mix" cards now resolve
   dynamically** to whichever model's mix parameter is currently live
   (`RVB PL M` when Plate is selected, `DLY DIG M` when Digital is
   selected, etc.), shown explicitly via a "→ resolves to `RVB PL M` ·
   Plate model is active" caption, rather than pretending Reverb Mix is
   one fixed wire parameter.
3. **A human-readable-label layer over the pedal's raw abbreviations is
   now shown explicitly** wherever a parameter is displayed (a small
   monospace caption under every label, e.g. "Threshold / `NG THRESH`"),
   including a worked example of the one confirmed case where the raw
   abbreviation is not unique (`MOD RO S` = both Rotary Sync, index 88,
   and Rotary Speed, index 90).

Master Volume also moved from "flagged as possible new scope" to "built":
it now has a persistent bottom dock present on the preset list and
parameter editor screens (§1).

Everything accepted as-is by the lead (the destructive-action color
reasoning, the six-item quick-tier ceiling, the contrast self-checks, the
412dp viewport assumption) is unchanged from Revision 1 and not repeated
here beyond what's needed for context.

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
  those get set once during rehearsal, not mid-set. **These are not single
  wire parameters** — the pedal has one mix value per reverb model
  (`RVB S1 M` … `RVB PL M`, six total) and one per delay model (`DLY DIG
  M`, `DLY TAPE M`), and only one of each is ever live. The quick-tier
  card resolves to whichever model is currently active on this preset and
  shows that explicitly (a "→ resolves to `RVB PL M` · Plate model is
  active" caption) rather than silently picking one or, worse, showing
  six sliders. Switching the underlying model happens in the full editor
  (screen 2c), not from the quick tier — the quick tier is not a place to
  discover you've accidentally changed which reverb algorithm is running.
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
  which it doesn't and mustn't.

  **Revision 2: built, not just flagged.** Master Volume now has a
  persistent dock pinned to the bottom of the preset list and parameter
  editor screens (shown in both `mockups.html` screens 1 and 2), always
  reachable without navigating into Settings or the full parameter list.
  It reuses `surface.raised-1` — the exact token D1 assigns to "top app
  bar, bottom bar" (§2.1) — rather than `surface.raised-2` (the
  per-preset param-card token), extending the visual grammar this mockup
  already used to keep global scope legible, rather than inventing a new
  pattern for it.

  **Display scale — dB vs. 0–10, and why:** the parameter's real
  engineering range is −40..+3 dB (confirmed in `ParameterSpec`'s own doc
  comment: "master volume's *engineering* range is -40..3 ... even though
  the ToneX One's own display shows 0..10 — the wire/display conversion
  is S7's concern"). I chose to show the **0–10 scale as the primary,
  large numeral**, with the dB value in a small caption underneath
  (e.g. "7.5" primary, "≈ −6.8 dB" secondary) — the same reasoning as
  Gain/Bass/Mid/Treble in the quick tier: 0–10 is what's printed on the
  physical pedal's own screen, so it's the number a guitarist's hand
  already trusts, and it costs nothing to keep the dB value visible for
  anyone cross-referencing documentation. The 0↔dB mapping used in the
  mockup (`dB = −40 + value/10 × 43`, i.e. linear) is my own assumption
  for display purposes only — **not verified against the pedal's actual
  curve**, which `ParameterSpec`'s own comment already assigns to S7, not
  to this story. Flagged, not invented as fact.

## 2. The 109 parameters: banked structure, not a flat list

**This is the section that changed most.** The lead's recovered table
established four confirmed facts that Revision 1 didn't know:

| Selector | Options | Params total | Live at once |
|---|---|---|---|
| `RVB MODEL` | Spring 1–4, Room, Plate (6) | 1 selector + 24 (6 × T/P/C/M) | 4 |
| `MOD MODEL` | Chorus, Tremolo, Phaser, Flanger, Rotary (5) | 1 selector + 28 | 5–6 |
| `DLY MODEL` | Digital, Tape (2) | 1 selector + 12 (6 each) | 6 |
| `MDL CAB` | Tone Model / VIR / Disabled (3) | 1 selector + 9 (VIR mic block) | 0 or 9 |

64 of 109 parameters belong to these four banks; only one model per
selector is ever live, so real live count is roughly 52–61 of 109
depending on which models and cabinet mode a preset uses — not 109.

**Information-architecture consequence:** a flat, always-visible list
would have been actively wrong here, not just cluttered — it would show
(for example) four "Spring 2" parameters that do nothing while Plate is
selected, and a player could edit them believing they're editing the live
sound. The parameter editor (screen 2c) now renders a **model-selector
chip row** at the top of each banked group's accordion body; choosing a
chip swaps which block of parameters renders beneath it, and the other
models' parameters are not rendered at all — no grayed-out rows. Cabinet
gets the same treatment for its VIR mic sub-block: 4 always-on core
params (Low Cut, High Cut, Resonance, Air) plus the 9 VIR-specific mic
params, shown only when Cabinet mode = VIR.

**Reconsideration prompted by the smaller live count:** in Revision 1 I
reasoned the quick tier's 6-item ceiling was necessary specifically to
avoid a "wall of 109 sliders." With the real live count closer to
52–61 — and, more importantly, with most of that total now organized into
4–9-parameter *blocks* behind a single selector rather than 109
independent rows — a well-organized full editor is more viable than
either the story brief or I assumed going in. I did **not** loosen the
quick-tier ceiling because of this: the six promoted items (§1) were
chosen for "what a guitarist's hand already knows how to reach for," a
criterion that has nothing to do with how many parameters technically
exist. But it does mean I'm less worried than I was that the "buried"
tier is a bad experience — hiding inactive banks turns a 109-parameter
space into effectively ~11 category taps, each showing at most ~14 rows
(Cabinet, the largest, sized because of its VIR block). That's a much
smaller information-architecture problem than "109 flat sliders," and
worth the lead knowing I updated my own mental model on, not just the
mockup.

**Category totals reconcile exactly to 109** across 11 preset-scoped
groups: Noise Gate 3, Compressor 4, Tone Model 7, Cabinet 14 (1 selector
+ 4 core + 9 VIR), EQ 3, Power Amp 5, Modulation 29 (1 + 28), Delay 13
(1 + 12), Reverb 25 (1 + 24), FX Loop 3, Expression/Wah 3 — plus the 7
confirmed globals = 116 total, matching the issue's own number.

**What's confirmed vs. still illustrative**, to be precise about
provenance:

- **Confirmed, used verbatim:** the four selectors' option lists and
  param-per-model counts (table above); `MDL GAIN` (idx 20), `EQ BASS`
  (idx 11), `EQ MID` (idx 13), `EQ TREBLE` (idx 16); `NG THRESH`; the
  `MOD RO S` collision at indices 88/90 (Rotary Sync and Rotary Speed);
  `VIR M1Z`; `MDL CABU`; and the 7 global names.
- **Still illustrative:** which specific leaf parameters make up Noise
  Gate's other 2, Compressor's 4, Tone Model's other 6, Power Amp's 5, FX
  Loop's 3, Expression/Wah's 3, Cabinet's 4 non-VIR core params, and the
  specific per-model parameter names within Reverb/Modulation/Delay
  beyond what the lead confirmed (e.g. I invented "Character" for
  reverb's `C` slot, matching the given T/P/C/M shape, but did not
  confirm that letter means "Character" specifically). These were sized
  to keep the category math consistent with the confirmed banked totals,
  not sourced from a real recovered table — S6/D3 still owns closing this
  out. I did not use the `:protocol` registry as an import (it isn't
  merged) and worked only from the facts given directly.
- **One deliberate non-guess:** `MDL CABU` is shown in the mockup with
  its raw abbreviation as the primary text and no invented human label —
  I could not confidently expand it from the information given, and
  showing a wrong label would be worse than admitting it isn't understood
  yet. This is also the fallback behavior the label layer should have
  generally (§3): show the raw abbreviation when confidence is low,
  rather than a fluent-sounding guess.

## 3. Human-readable labels over the pedal's raw abbreviations

The pedal's own parameter names are terse hardware-screen abbreviations —
`NG THRESH`, `MDL CABU`, `VIR M1Z`, `MOD RO S` — and at least one pair is
not even unique: indices 88 and 90 are both literally `MOD RO S` (Rotary
Sync and Rotary Speed). That's upstream truth, confirmed by the lead, not
a bug to route around.

Every parameter row in the mockup now carries two lines: a human-readable
label as the primary text, and the raw pedal abbreviation underneath in a
small monospace caption (`.pedal-abbr` in the CSS) — the same visual
treatment already used for "from pedal: PRESET 07" on the preset-list
screen, extended rather than reinvented. This does two things at once:

1. **Makes the pedal's abbreviation discoverable**, per the lead's
   instruction — a player cross-referencing the hardware screen will
   search for `MOD RO S`, not "Rotary Speed," so the raw string has to
   stay visible somewhere, not be replaced.
2. **Disambiguates what the abbreviation alone cannot.** The Modulation
   block (screen 2c) shows the Rotary model expanded specifically to
   demonstrate the `MOD RO S` collision: both rows show the identical
   abbreviation, colored with `status.error` and flagged in a bank-note
   explaining that the label layer must be keyed by **wire index**, not
   by parsing the abbreviation string, or Sync and Speed will silently
   get conflated. (This is the one deliberate non-connection use of
   `status.error` in the mockup — flagging a genuine data-integrity
   hazard the implementation must not get wrong, which I judged distinct
   enough from both "connection error" and "destructive action" to be a
   defensible third use of the same token; flagged for the lead to
   confirm rather than assumed.)

The principle is demonstrated on every parameter actually shown in this
mockup — the full quick tier, and every expanded block in screen 2c
(Noise Gate, Tone Model, Cabinet/VIR, EQ, Modulation/Rotary, Delay/
Digital, Reverb/Plate) — which per the lead's instruction is the scope
asked for, not all 109. Extending it to the remaining collapsed
categories is S17's job once the real registry lands; the pattern here is
meant to be copy-pasteable, not a one-off.

## 4. Snapshot / revert / first-write-warning

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

## 5. Connection states

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

## 6. Contrast — what I actually checked

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

## 7. Other assumptions, flagged

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

## 8. Summary of what needs a decision

1. Confirm or correct the 6 promoted quick-tier parameters (§1) —
   specifically whether Noise Gate Threshold should swap in for one of
   the six.
2. ~~Decide whether Master Volume needs a persistent, always-reachable
   control~~ — resolved this pass: built as a persistent dock (§1).
   Confirm the 0–10-primary/dB-secondary display choice and flag if the
   assumed linear dB conversion is materially wrong once S7 defines the
   real curve.
3. Rule on whether D1 needs a `color.status.destructive` token, or
   whether the placement/hierarchy-only approach to the revert flow (§4)
   is sufficient as designed. Also confirm the one non-connection use of
   `status.error` introduced this pass — flagging the `MOD RO S`
   duplicate-abbreviation collision (§3) — is an acceptable third use of
   that token, or should be styled differently.
4. Nothing here should be treated as the final 109-parameter taxonomy
   (§2) — that's D3's/S6's job. The four banked-model groups (Reverb,
   Modulation, Delay, Cabinet-VIR) and the confirmed leaf names (§2,
   §3) are load-bearing; everything else is still illustrative,
   sized only to keep the category math consistent with 109.
5. `MDL CABU` (§2, §3) could not be confidently expanded to a human label
   from the facts given — shown with its raw abbreviation and no guessed
   name. If the lead knows what it means, that's a one-line fix; otherwise
   it's a question for S6/firmware docs.
