# UI Design Direction & Design Tokens

**Story:** D1 (refs [#5](https://github.com/wiz0floyd/Tonex-OTG/issues/5), parent [#1](https://github.com/wiz0floyd/Tonex-OTG/issues/1))
**Status:** Spec — no implementation. Consumed by D2 (screen layout) and S15 (Material 3 wiring).

This document is the single source of truth for color, type, spacing, touch-target, and
motion values in Tonex-OTG. It is a **specification**, not code: no Compose, no
`Color.kt`, no theme object. S15 is responsible for turning these tokens into an actual
M3 `ColorScheme`/`Typography`/`Shapes` object; this doc defines what those values must
be and why.

## 1. Design principles (why every later decision exists)

Tonex-OTG is a **stage instrument controller**, not a general-purpose app. Every token
below is a direct answer to one of these operating conditions:

| Condition | Consequence |
|---|---|
| Dim/dark venues | Dark is the only theme in v1 — not a dark *option* sitting next to a light one. |
| Glancing down mid-song | The active preset name must be legible **peripherally**, at arm's length, without the player focusing on the phone. |
| Guitar in hand | Interaction is one-thumb, imprecise. Touch targets are oversized versus a typical app. |
| "Am I still connected?" is the highest-stakes question on stage | Connection state must be readable without reading — and without color vision. Shape and text carry the meaning; color reinforces it. |

Restraint is a feature. This is explicitly **not** a skinned amp/pedal graphic (per the
BRD, issue #1 §3). No amp-cab illustrations, no faux-hardware textures, no chrome knobs.

---

## 2. Color roles and tokens

### 2.1 Surfaces & content (dark theme — the only theme in v1)

Dark M3 surfaces are built from **tonal elevation** (a flat, lighter fill per elevation
step), not drop shadows — shadows are close to invisible on a black background in a dim
room, so elevation must read as a value shift, not a shadow.

| Token | Value | M3 mapping | Usage |
|---|---|---|---|
| `color.surface.base` | `#121212` | `surface` | App background, the resting state of every screen. |
| `color.surface.raised-1` | `#1D1D1D` | `surfaceContainer` (elevation 1) | Preset list rows, top app bar, bottom bar. |
| `color.surface.raised-2` | `#262626` | `surfaceContainerHigh` (elevation 3) | Dialogs, bottom sheets, parameter-edit panels. |
| `color.surface.raised-3` | `#303030` | `surfaceContainerHighest` (elevation 5) | Pressed/active fill on a raised-1 element; highest overlay. |
| `color.on-surface.primary` | `#FFFFFF` | `onSurface` | Primary text/icons: active preset name, key numerals. |
| `color.on-surface.secondary` | `#E0E0E0` | `onSurfaceVariant` (strong) | Body text, control labels. |
| `color.on-surface.tertiary` | `#9E9E9E` | `onSurfaceVariant` (muted) | Captions, secondary metadata (e.g. preset index, timestamps). |
| `color.on-surface.disabled` | `#6B6B6B` | `onSurface` @ 38% equivalent | Disabled control text/icons only. |

### 2.2 Accent (interactive / selection)

| Token | Value | M3 mapping | Usage |
|---|---|---|---|
| `color.accent.primary` | `#52B8FF` | `primary` | Selection ring on the active preset tile, slider fill/thumb, primary button fill, focus indicator. |
| `color.on-accent` | `#121212` | `onPrimary` | Text/icon glyphs drawn on top of an accent-primary fill. |

Accent is a **cool blue**, deliberately not adjacent to the green/red status hues (§2.3)
so "this is selected/interactive" never gets confused with "this is a status reading."

### 2.3 Connection status — color + shape + icon + text, never color alone

The brief requires connection state to be readable without color vision. Each state is
therefore defined as a **triple**: a fill color, a distinct silhouette, and a text
label. Silhouette and label are load-bearing, not decorative — a grayscale or
protanopic/deuteranopic render must still disambiguate all three states from shape and
text alone.

| State | Token | Value | Shape (silhouette) | Text label | Contrast on `surface.base` |
|---|---|---|---|---|---|
| Connected | `color.status.connected` | `#34D177` | Filled circle (●) | "Connected" | 9.40:1 |
| Connecting *(transient — see note)* | `color.status.connecting` | `#52B8FF` (= accent-primary) | Hollow ring, same silhouette as Disconnected | "Connecting…" | 8.62:1 |
| Disconnected | `color.status.disconnected` | `#9AA0A6` | Hollow/outline ring (○) | "Not Connected" | 7.09:1 |
| Error | `color.status.error` | `#FF5252` | Filled triangle with glyph (▲!) | "Error" | 5.87:1 |
| Text/icon drawn on any status fill | `color.on-status` | `#121212` | — | — | ≥5.87:1 against every status fill above (see §2.4) |

The three permanent states each get their own silhouette family: a **filled circle**
means "good, live"; a **hollow ring** means "nothing there"; a **filled triangle** means
"something is wrong." No two states share a silhouette, and the fill color is
consistent within a state everywhere it appears (status dot in the app bar, banner,
any future notification).

*Connecting note:* FR2 (issue #1) requires graceful handling of the USB
permission/handshake window. It is not one of the three states named in the acceptance
criteria, so it is included here as a minimal, inferred addition rather than new scope:
same hollow-ring silhouette as Disconnected (it *is* still "not yet connected"), colored
accent-primary instead of neutral gray, so it reads as "in progress" rather than "off."
No spinner, no animated fill — see §5 on why nothing may animate over a connection or
preset action.

### 2.4 Verified contrast ratios (WCAG 2.1, computed via relative luminance — not eyeballed)

All ratios below were computed from sRGB relative luminance, not estimated.

| Pair | Ratio | WCAG AA target | Result |
|---|---|---|---|
| `on-surface.primary` #FFFFFF on `surface.base` #121212 | 18.73:1 | 4.5:1 (normal text) | Pass (exceeds AAA 7:1) |
| `on-surface.secondary` #E0E0E0 on `surface.base` #121212 | 14.19:1 | 4.5:1 | Pass |
| `on-surface.tertiary` #9E9E9E on `surface.base` #121212 | 6.99:1 | 4.5:1 | Pass |
| `on-surface.disabled` #6B6B6B on `surface.base` #121212 | 3.52:1 | *(exempt — disabled content is excluded from SC 1.4.3)* | N/A by design |
| `on-surface.primary` #FFFFFF on `surface.raised-2` #262626 | 15.13:1 | 4.5:1 | Pass |
| `status.connected` #34D177 on `surface.base` #121212 | 9.40:1 | 3:1 (graphical object/large text) | Pass |
| `status.connected` #34D177 on `surface.raised-1` #1D1D1D | 8.45:1 | 3:1 | Pass |
| `status.disconnected` #9AA0A6 on `surface.base` #121212 | 7.09:1 | 3:1 | Pass |
| `status.disconnected` #9AA0A6 on `surface.raised-1` #1D1D1D | 6.38:1 | 3:1 | Pass |
| `status.error` #FF5252 on `surface.base` #121212 | 5.87:1 | 3:1 (4.5:1 for text) | Pass, incl. as text |
| `status.error` #FF5252 on `surface.raised-1` #1D1D1D | 5.28:1 | 4.5:1 | Pass |
| `accent.primary` #52B8FF on `surface.base` #121212 | 8.62:1 | 3:1 | Pass |
| `on-accent` #121212 on `accent.primary` #52B8FF (filled button label) | 8.62:1 | 4.5:1 | Pass |
| `on-status` #121212 on `status.connected` #34D177 | 9.40:1 | 4.5:1 | Pass |
| `on-status` #121212 on `status.error` #FF5252 | 5.87:1 | 4.5:1 | Pass |
| `on-status` #121212 on `status.disconnected` #9AA0A6 | 7.09:1 | 4.5:1 | Pass |

Two pairs were tested and **rejected** for this reason:

- White text/icon on `accent.primary` fill: **2.17:1** — fails. Use `on-accent`
  (`#121212`) on any accent-filled control instead.
- White text/icon on `status.disconnected` fill: **2.64:1** — fails. Use `on-status`
  (`#121212`) on any status-filled badge/banner, for all three states, for consistency.

This is why `color.on-status` and `color.on-accent` are both fixed to dark
(`#121212`), not white — white only clears AA against the *base/raised surfaces*, not
against the light-toned accent/status fills.

**Bonus (not a requirement, a design check):** relative luminance of the three status
hues is intentionally spread (connected 0.477, accent 0.433, disconnected 0.348, error
0.279) so a fully desaturated/grayscale render still shows three distinguishable gray
levels, on top of the shape+text mitigation that actually satisfies the requirement.

---

## 3. Type scale

Sizes are in `sp` (scale with system font size, per Android accessibility norms).
Several tiers are set **above** stock Material 3 defaults — noted per row — because the
default M3 scale assumes normal reading distance, and this app's primary read (the
active preset) happens at arm's length, in motion, under stage lighting.

| Token | Size / line-height | Weight | M3 mapping | Usage |
|---|---|---|---|---|
| `type.display.preset` | 57sp / 64sp | 700 (Bold) | `displayLarge` | **The arm's-length tier.** Active preset name/number only. Nothing else in the app uses this size. |
| `type.display.index` | 45sp / 52sp | 700 (Bold) | `displayMedium` | Optional companion numeral badge next to the preset name (e.g. slot "12"). |
| `type.headline` | 24sp / 32sp | 600 (SemiBold) | `headlineSmall` | Screen/section titles. |
| `type.title` | 22sp / 28sp | 500 (Medium) | `titleLarge` | Preset list row primary text, dialog titles. |
| `type.body-large` | 18sp / 26sp *(M3 default: 16sp)* | 400 (Regular) | `bodyLarge` | Parameter names, primary body copy. Bumped +2sp for glanceability. |
| `type.body` | 16sp / 22sp *(M3 default: 14sp)* | 400 (Regular) | `bodyMedium` | Secondary body copy, helper text. Bumped +2sp. |
| `type.label` | 16sp / 20sp *(M3 default: 14sp)* | 600 (SemiBold) | `labelLarge` | Button labels, chip text, tab labels. Bumped +2sp — labels sit inside the oversized touch targets in §4 and should not look small inside them. |
| `type.caption` | 13sp / 18sp *(M3 default: 11sp)* | 400 (Regular) | `labelSmall` | Lowest-priority metadata only (e.g. firmware version string in an About screen). Never used for anything status-related or interactive. |

**Arm's-length rationale for `type.display.preset` (57sp):** a phone glanced at from
roughly 40–70cm (propped on an amp/mic stand, or held at hip/chest height without
raising to eye level) needs numerals well above normal reading size to resolve
peripherally, under variable stage lighting, without the player breaking eye contact
with the audience/fretboard. 57sp is M3's own `displayLarge` step — the largest
standard M3 tier — set to Bold (M3 default is Regular) for maximum stroke weight at
a glance. This is the one tier this app treats as non-negotiable; do not shrink it to
fit a layout — shrink or truncate the container instead.

---

## 4. Spacing scale & touch targets

### 4.1 Spacing scale

8dp base grid with one half-step for tight/inline spacing. All values in `dp`.

| Token | Value | Usage |
|---|---|---|
| `space.0-5` | 4dp | Icon-to-label gap, tightest inline spacing. |
| `space.1` | 8dp | Base unit; padding inside small chips. |
| `space.2` | 12dp | Padding inside compact controls. |
| `space.3` | 16dp | Standard content padding, gap between related controls. |
| `space.4` | 24dp | Gap between unrelated control groups. |
| `space.5` | 32dp | Section separation. |
| `space.6` | 48dp | Major layout margins, top-level screen padding. |
| `space.7` | 64dp | Reserved — matches the touch-target minimum (§4.2), used only where a token needs to visually match a target's footprint. |

### 4.2 Minimum touch target size

**`touch.target.min = 64dp`** for every control a player operates *during* a
performance: preset tiles, the preset list itself, effect on/off toggles, and any
transport-style action.

Material's baseline minimum is 48dp. This app sets 64dp for performance-critical
controls because the physical context — one thumb, guitar in the other hand,
glancing rather than aiming — needs a materially larger error margin than seated,
two-handed, looked-at phone use; 48dp remains acceptable only for low-frequency,
non-performance chrome (overflow menu, settings gear, About screen link) that is never
touched mid-song.

| Token | Value | Usage |
|---|---|---|
| `touch.target.min` | 64dp | Preset tiles, preset list rows, effect toggles, any control used mid-performance. |
| `touch.target.secondary` | 48dp | Settings/overflow/navigation chrome not used mid-performance. Matches Material's own floor — no further justification needed since it's never a stage-time control. |
| `touch.target.spacing` | 8dp minimum gap | Minimum gap between two adjacent 64dp targets, to keep a mis-tap from landing on the neighboring preset. |

---

## 5. Motion

Motion budget is deliberately close to zero. **A preset switch is a performance
action — nothing may sit between the tap and the pedal receiving the command.**

| Token | Value | Usage |
|---|---|---|
| `motion.duration.instant` | 0ms | Preset selection acknowledgement. The UI reflects "selected" the instant the tap is registered, optimistically, before waiting on any USB round-trip. |
| `motion.duration.fast` | 100ms | Micro-feedback only: pressed-state opacity/scale change, ripple. |
| `motion.duration.standard` | 150ms | Ceiling. The single largest duration allowed anywhere in the app (e.g. a bottom sheet opening for parameter edit). |
| `motion.easing.standard` | `cubic-bezier(0.2, 0, 0, 1)` | M3 standard easing, used only where `motion.duration.standard` applies. |

Hard rules, not guidelines:

- **No animation, spinner, skeleton loader, or transition may gate a preset switch.**
  The UI updates to show the new preset as selected immediately on tap; if the pedal
  write later fails, that surfaces as an error state (per §2.3) applied after the fact,
  not as a delay before the fact.
- 150ms is a ceiling for the whole app, not just preset selection — there is no
  screen transition, dialog, or reveal animation anywhere that should feel "designed,"
  because a felt animation is a perceived delay on stage.
- The `connecting` status silhouette (§2.3) does not pulse, spin, or animate. It is a
  static hollow ring in accent color. Motion is not how this app communicates
  "in progress."

---

## 6. Explicitly out of scope (for D2 / S15 — do not gold-plate)

- **Light theme.** v1 is dark-only. No light `ColorScheme`, no theme toggle, no
  "follow system" branch. If a light theme is ever wanted, it is a new story.
- **Skinned/graphical amp or pedal chrome.** No amp-cab art, no knob textures, no
  faux-hardware skeuomorphism. Confirmed out of scope by the BRD (issue #1 §3).
  Restraint is the design, not a placeholder for something more decorated later.
- **Corner radius / shape system.** Not defined here. S15 may take M3's default shape
  scale as-is; no custom radius tokens are specified by this doc.
- **Icon set design.** Use Material Symbols (outlined for empty/off states, filled for
  active/positive states) as a default. This doc only mandates the three status
  *silhouettes* in §2.3; it does not design a bespoke icon set.
  Beyond those three specific silhouettes, icon choices are open to S15/D2.
- **Elevation via shadow.** Explicitly rejected in favor of tonal fills (§2.1); do not
  add `Modifier.shadow`/elevation-shadow styling on top of these surface tokens.
  On the target device (Pixel 10 Pro, per project context) the display class does not
  change this decision.
- **Haptics.** Not specified. May be a good fit for a stage app (tap feedback without
  looking down) but is a separate story if wanted.
- **Tablet/foldable layout scale.** Tokens assume a single phone form factor. No
  breakpoint system is defined.
- **Localization/RTL.** Not addressed. Type scale values assume a Latin, LTR script.
- **Accent color theming / dynamic color (Material You).** Accent is the single fixed
  value in §2.2. No per-user theming, no wallpaper-derived dynamic color.
- **Light-mode contrast validation.** Only the dark palette in §2 has been contrast
  checked, because only the dark palette exists in v1.

---

## 7. Handoff notes for S15 (Material 3 wiring)

- Every token in §2 has an explicit M3 role mapping in its table. `status.connected`
  and `status.disconnected` have no stock M3 role (M3 ships `error`/`onError` but no
  "success" or neutral-status pair) — wire them as a custom extension on the
  `ColorScheme` (M3's documented pattern for app-specific semantic colors), not by
  repurposing `tertiary` or `secondary`.
- `type.display.preset` must map to `displayLarge` with its `fontWeight` overridden to
  `FontWeight.Bold` — M3's stock `displayLarge` is Regular weight by default.
- The three bumped body/label sizes in §3 are intentional deltas from stock M3, not
  typos — do not "fix" them back down to the M3 defaults in the code comments/diff.
