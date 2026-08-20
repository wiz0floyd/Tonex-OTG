# S23 — UI wiring contract (issue #74)

**Status:** Architecture contract. Binding for the S23 implementer.
**Branch:** `s23-ui-integration`.
**Inputs, already binding and not re-litigated here:** `docs/design/interaction-spec.md` (D3,
issue #7) and `docs/design/mockups.md` (D2, issue #6).

S16–S19 built the screens and their state holders; S11–S13 built the USB session. Nothing
connects them: `MainActivity` still renders `Text("Tonex-OTG")`. This document specifies exactly
how those two halves are joined, so the implementer makes no design calls of their own.

---

## 0. The one thing to get right

Everything in §2 is low-stakes plumbing. §3 is not: it is the code path that decides *when a
live `TonexUsbTransport` is handed to something that can write to the user's only pedal*. Read
§3.4 in particular before writing any of it.

---

## 1. Non-goals / deliberately deferred

- **No DI framework.** No Hilt, no Koin, no `Application` subclass. The codebase already
  established the pattern for app-scoped singletons in
  `UsbConnectionManager.getInstance(context)` (see its own KDoc, which explains this choice);
  S23 follows it rather than introducing a second, competing mechanism.
- **No `androidx.lifecycle.ViewModel` migration.** All three state holders
  (`PresetListViewModel`, `ParameterEditorViewModel`, `ConnectionStatusViewModel`) are plain
  classes taking a `CoroutineScope`, by explicit design documented in each one's KDoc. They stay
  that way. Do **not** add `lifecycle-viewmodel-compose` in this story.
- **No Settings screen.** D3 §5.1 puts the revert flow in Settings; S18 never built one, and
  `ParameterEditorViewModel`'s KDoc already records that revert lives at the bottom of the
  editor for now. S23 does not move it and does not add a fourth route for it.
- **No `POST_NOTIFICATIONS` runtime-permission flow.** Known gap, already filed on issue #18.
  Out of scope here.
- **No firmware capability probe.** See §5 — this is the one item escalated to the product owner.
- **No new tests for the screens themselves.** They are already covered. New test surface is
  limited to what §4 names.

---

## 2. Navigation

### 2.1 Library

`androidx.navigation:navigation-compose` `2.9.3`, added in this branch
(`gradle/libs.versions.toml` → `navigationCompose`, wired into `app/build.gradle.kts`). It is
**not** part of the Compose BOM — `androidx.navigation` ships its own release train — hence its
own explicit `version.ref`. Verified to resolve and compile on this branch.

### 2.2 Route graph

Three destinations, defined in the already-committed
`app/src/main/kotlin/dev/tonexotg/app/ui/navigation/TonexRoute.kt`:

| Route | Constant | Screen | Reached from |
|---|---|---|---|
| `presets` | `TonexRoute.PRESET_LIST` (start) | `PresetListScreen` | start destination; editor back arrow |
| `editor` | `TonexRoute.PARAMETER_EDITOR` | `ParameterEditorScreen` | tapping any preset row |
| `about` | `TonexRoute.ABOUT` | `AboutScreen` | preset-list app-bar action |

**No route takes an argument.** The editor edits whatever `TonexController.activePreset`
currently reports — never an index captured at navigation time. This is forced by D3 §6.2:
`ParameterWriteMessage` carries no preset index on the wire, so a write always lands on whatever
preset is active *now*. An `editor/{index}` route would make the stale-screen / misdirected-write
hazard representable in the nav graph. `TonexRoute`'s KDoc restates this; keep it.

**Transitions:**

- Preset row tap → `viewModel.selectPreset(index)` **and** `navController.navigate("editor")`, on
  the same tap, per D3 §4.1's combined select+navigate ruling. Fire both unconditionally; do not
  await the write result before navigating (D1 §5's zero-motion-gating rule). A failed select
  surfaces through `PresetListUiState.selectPresetError`, which the list already renders when the
  user backs out.
- Editor back arrow → `navController.popBackStack()`. Scroll position on the list is preserved
  for free by `NavHost`'s own saved state; do not hand-roll it.
- About → `navigate("about")`, popped with the system back button / a back arrow.

### 2.3 The persistent connection status bar — layout, not a Scaffold

`ConnectionStatusBar` must be visible on every route (S18's own requirement, D1's
"legible peripherally"). `ParameterEditorScreen` already owns a `Scaffold` with a `TopAppBar`,
a `bottomBar` (the Master Volume dock) and a `SnackbarHost`. Nesting that inside a second,
app-level `Scaffold` fights over insets and the bottom bar.

**So the app shell is a plain `Column`, not a `Scaffold`:**

```
Column(Modifier.fillMaxSize()) {
    ConnectionStatusBar(...)                    // fixed chrome, all routes
    NavHost(..., modifier = Modifier.weight(1f)) { ... }
}
```

Each route composable keeps whatever `Scaffold` it already has, unchanged, inside the `NavHost`.
This is why S23 touches the existing screens as little as it does.

`PresetListScreen` has no app bar of its own today (it is a bare `Column`). The preset-list
*route* composable — not `PresetListScreen` — wraps it in a `Scaffold` with a `TopAppBar` whose
only action navigates to About. Keep that wrapper in the nav file so `PresetListScreen` stays
directly previewable and testable as-is.

---

## 3. The controller lifecycle — `TonexSessionHolder`

### 3.1 Decision: one long-lived controller, reused across connections

There is exactly **one** `DefaultTonexController` instance for the life of the process. It is
constructed eagerly when the holder is first created and is **never** recreated on
attach/detach/error. Reconnecting calls `connect(transport)` on that same instance; disconnecting
calls `disconnect()` on it.

**Why, and why not the obvious alternative.** The alternative — build a fresh
`DefaultTonexController` per USB attachment and expose it as `StateFlow<TonexController?>` — was
considered and rejected:

- Every screen and every state holder in S16–S19 takes a **non-null** `TonexController` and
  already models "not connected" correctly through `controller.connectionState`
  (`PresetListUiState.isLive`, `ConnectionUiState.NotConnected`,
  `ParameterEditorUiState.Empty`). A nullable controller would add a second, redundant
  "not connected" concept the screens don't have and would force null branches into code that is
  already tested without them.
- `rememberPresetListViewModel` / `rememberConnectionStatusViewModel` key their `remember` on
  **controller identity**. A per-attachment controller silently re-creates all three state
  holders on every replug, discarding accordion expansion, an open numeric-entry sheet, and the
  list's scroll state.
- Reuse is a supported mode of `DefaultTonexController`, not an assumption: `teardown()`
  (`DefaultTonexController.kt`, ~line 492) resets `session`, `transport`, `readerJob`,
  `selfInitiatedPreset`, `footswitchSnapshot`, calls `snapshotStore.clear()`, empties `_presets` /
  `_activePreset` / `_parameterValues`, and publishes the final state last; `onTransportEnded`'s
  KDoc explicitly reasons about "a fresh `connect()` [running] to completion, resetting
  `teardownDone`."
- `snapshotStore.clear()` inside `teardown()` is also what makes D3 §5.2's first-write-warning
  rule ("reappears exactly once per physical USB connection") come out right with a reused
  controller: the warning gate is `SnapshotStore`'s lifetime, and that is cleared on every
  disconnect regardless of whether the controller object survives.

### 3.2 New class: `TonexSessionHolder`

`app/src/main/kotlin/dev/tonexotg/app/session/TonexSessionHolder.kt`

App-scoped singleton, same shape as `UsbConnectionManager.getInstance(context)`: a
`@Volatile` instance behind `getInstance(context.applicationContext)`, backed by its own
`CoroutineScope(SupervisorJob() + Dispatchers.Default)`. That scope is what it passes as
`DefaultTonexController`'s `scope` argument — required by that class's KDoc ("`:app` passes a
service/view-model scope"), and it must outlive any Activity.

It owns and exposes:

| Member | Type | Purpose |
|---|---|---|
| `controller` | `TonexController` | the single instance from §3.1; non-null, always |
| `aliasStore` | `PresetAliasStore` | one `DataStorePresetAliasStore` for the process (S14) |
| `blockedReason` | `StateFlow<String?>` | §3.4's fail-loud gate message; `null` when nothing is blocked |
| `requestReconnect(context)` | `fun` | §3.5 |

`aliasStore` lives here rather than in `MainActivity` for one concrete reason: DataStore throws
if two instances are created over the same file, and an Activity recreation on rotation would do
exactly that.

### 3.3 What the holder observes, and what it does

A single collector on the holder's scope, combining two sources:

```
combine(UsbConnectionManager.getInstance(ctx).state, UsbConnectionService.foregroundActive) { usb, fgs -> usb to fgs }
```

and reacting, serialized behind the holder's own `Mutex` (a connect/disconnect pair must never
interleave):

| `UsbConnectionState` | `foregroundActive` | Action |
|---|---|---|
| `Connected(_, _, transport)` | `true` | if controller is `Idle`/`Error`: `controller.connect(transport)`. `blockedReason = null` |
| `Connected(...)` | `false` | **do not connect.** `blockedReason = "Pedal attached, but the background service isn't running — reconnect to continue."` |
| `Connecting` | any | no action; `blockedReason = null` |
| `Disconnected` | any | `controller.disconnect()`; `blockedReason = null` |
| `Failed(reason)` | any | `controller.disconnect()`; `blockedReason = reason` |

Notes the implementer needs:

- **Do not close the transport here.** `UsbConnectionManager` owns it and closes it on detach;
  `DefaultTonexController.teardown()` also closes it (documented idempotent). Both paths
  converging is expected. Adding a third closer is not.
- On detach, three things race by design and all converge: the manager flips state to
  `Disconnected` *before* the slow close (see its KDoc); the controller's reader sees
  `incoming()` end and tears itself down to `Error`; this collector calls `disconnect()`, which
  is documented safe from any state and always lands on `Idle`. No extra sequencing is needed.
- `controller.connect()` suspends until `Ready` or failure. Call it from inside the collector's
  coroutine; do not `launch` a second unsupervised one.
- Guard against re-entrant connects: only call `connect` when
  `controller.connectionState.value` is `Idle` or `Error`.

### 3.4 The foreground-service gate — the load-bearing part

Issue #18 made it a deliberate product decision not to run a pedal session without foreground-
service protection. Today nothing enforces that: `UsbConnectionManager.start()`'s own
`ACTION_USB_DEVICE_ATTACHED` receiver calls `onDeviceAttached` directly, so
`UsbConnectionState.Connected` can be reached with no foreground service promoted at all. Wiring
the controller straight to `UsbConnectionManager.state` would therefore quietly bypass the gate.

**Required change to `UsbConnectionService` (S13 code — change it exactly this much, no more):**

- Add a companion-scoped `private val _foregroundActive = MutableStateFlow(false)` and a public
  `val foregroundActive: StateFlow<Boolean>`.
- Set it `true` immediately after `startForegroundSafely(...)` returns `true` in
  `onStartCommand`.
- Set it `false` in `onDestroy()`, and in `degradeAfterFailedStartForeground()`.
- Do **not** touch `startForegroundSafely`'s exception handling, the wakelock logic, the
  `pingPermissionPrerequisiteIfAlreadyGranted` gate, or `onDestroy`'s "never call
  `manager.stop()`/`disconnect()`" rule. Those are all reviewed, load-bearing, and explained at
  length in that class's KDoc.

**Two consequences, both intended:**

1. A background reattach with no service running produces `Connected` + `foregroundActive ==
   false` → the app shows `blockedReason` and does **not** open a protocol session. Fail loud,
   per the house philosophy: no write path exists until protection is in place.
2. `MainActivity` is what normally clears that state — see §3.5.

### 3.5 Who starts the service, and what "reconnect" means

`MainActivity.onStart()` calls
`ContextCompat.startForegroundService(this, Intent(this, UsbConnectionService::class.java))`.
An Activity in `onStart` is a foreground context, which is what makes this legal under API 31+'s
background-start restriction (`UsbConnectionService` KDoc, point 2). The service's own
`onStartCommand` then calls `connectToAttachedDeviceIfPresent()` for the
already-plugged-in case. `START_STICKY` plus an idempotent `startForeground` makes repeat calls
across rotations harmless.

**`MainActivity` must never call `UsbConnectionManager.connectToAttachedDeviceIfPresent()` or
`onDeviceAttached()` itself.** That is precisely the bypass §3.4 exists to close. Every path to a
connection goes through the service.

`TonexSessionHolder.requestReconnect(context)` is therefore just: start the foreground service
again. It does **not** call `controller.connect()`.

### 3.6 Required change to `ConnectionStatusViewModel`

`ConnectionStatusViewModel`'s current `reconnect()` calls `controller.disconnect()` then
`controller.connect(transportFactory())` — i.e. it opens a protocol session from the UI layer,
bypassing §3.4's gate entirely, and its `transportFactory: () -> TonexTransport` signature cannot
express "no transport is available" without throwing inside a `launch` (an app crash).

**Change:** replace the `transportFactory: () -> TonexTransport` constructor parameter with
`onReconnectRequested: () -> Unit`, and make `reconnect()` simply invoke it. `uiState` is
unchanged. `rememberConnectionStatusViewModel` takes the callback instead of the factory. The app
passes `{ sessionHolder.requestReconnect(context) }`.

`ConnectionStatusViewModelTest` needs updating to assert the callback fires rather than asserting
`connect`/`disconnect` were called. That is the intended test change, not collateral damage —
the old assertion was pinning the unsafe behaviour.

### 3.7 The three duplicate `FakeTonexController`s

There is one per screens subpackage (`presets`, `parameters`, `connection`). **Leave them
alone.** Consolidating them is a refactor with its own review surface and no bearing on this
story's acceptance criteria. Noted only so the implementer doesn't take it on unasked.

---

## 4. Files: touched vs. new

**Already changed on this branch (scaffolding):**

- `gradle/libs.versions.toml` — `navigationCompose = "2.9.3"` + `androidx-navigation-compose`.
- `app/build.gradle.kts` — `implementation(libs.androidx.navigation.compose)`.
- `app/src/main/kotlin/dev/tonexotg/app/ui/navigation/TonexRoute.kt` — new; the three routes.

**New files the implementer writes:**

- `app/src/main/kotlin/dev/tonexotg/app/session/TonexSessionHolder.kt` — §3.2/§3.3/§3.5.
- `app/src/main/kotlin/dev/tonexotg/app/ui/navigation/TonexApp.kt` — the `Column` shell of §2.3,
  the `NavHost`, and the three route composables (including the preset list's `Scaffold` +
  `TopAppBar` wrapper).
- `app/src/test/kotlin/dev/tonexotg/app/session/TonexSessionHolderTest.kt` — pure-JVM, driving
  fake `StateFlow`s for USB state and `foregroundActive` against a fake controller. Must cover:
  connects only when `Connected && foregroundActive`; sets `blockedReason` on
  `Connected && !foregroundActive` **without** connecting; disconnects on `Disconnected`; does
  not re-enter `connect` while already `Ready`.

**Existing files modified:**

- `MainActivity.kt` — becomes the real entry point: `TonexTheme { TonexApp(...) }`, plus the
  `startForegroundService` call of §3.5. This is a rewrite of the placeholder, including its
  now-false KDoc.
- `usb/connection/UsbConnectionService.kt` — the `foregroundActive` flag of §3.4, and nothing else.
- `ui/screens/connection/ConnectionStatusViewModel.kt` — the constructor change of §3.6.
- `app/src/test/.../connection/ConnectionStatusViewModelTest.kt` — follows §3.6.
- `ui/screens/presets/PresetListScreen.kt` — add an `onPresetOpened: (PresetIndex) -> Unit`
  parameter, invoked alongside the existing `viewModel::selectPreset` on a row tap. The stateless
  `PresetListContent` already takes `onPresetClick`; thread the new callback through the
  stateful wrapper only.
- `ui/screens/parameters/ParameterEditorScreen.kt` — add an `onBack: () -> Unit` parameter and a
  `navigationIcon` back arrow on its existing `TopAppBar`.
- `app/src/main/AndroidManifest.xml` — replace `MainActivity`'s stale "placeholder scaffold only"
  comment. No attribute changes; the `taskAffinity` comment below it is still accurate and stays.

Anything not on this list should not need to change. If something does, that is a signal the
contract is wrong — say so rather than improvising.

---

## 5. Escalated to the product owner — `FirmwareCapabilities`

`DefaultTonexController` takes `capabilities: FirmwareCapabilities` with **no default**, by
design (see that type's KDoc: "there is deliberately no 'assume supported' constructor or default
value"). Nothing in `:protocol` probes it; the caller supplies it. S23 is the first code that has
to supply it for the real app, and the two options are not equivalent:

- `FirmwareCapabilities.NONE_CONFIRMED` (`supportsSingleParameterWrite = false`) — every
  `setParameter` call fails with `TonexError.UnsupportedByFirmware`. The parameter editor becomes
  read-only, i.e. the app cannot do the thing it exists to do.
- `FirmwareCapabilities(supportsSingleParameterWrite = true)` — what `ProbeSession`'s own write
  tests already use (`ProbeSession.kt` lines ~198, ~289, ~413). If the connected firmware is in
  fact too old, the failure mode is a write the pedal ignores — **not** a corrupting write: the
  single-parameter path is the *safe* path; the dangerous whole-state path is unaffected either
  way.

**Recommendation:** ship `supportsSingleParameterWrite = true`, as a single named constant in
`TonexSessionHolder` with a comment pointing at this section and at issue #26. Reasoning: `false`
makes the app non-functional, and `true`'s downside is a silently-ignored write on old firmware
rather than any risk to pedal state.

**But this is a product-facing call, not an engineering one, and the implementer must not decide
it silently.** Whether S20's hardware probe (#25/#26) actually *observed* a successful
single-parameter write on the owner's pedal is not something this document claims to know — that
log is the evidence that would settle it outright, and it should be checked before the PR merges.
Flag it in the PR description either way.

---

## 6. Verification the implementer owes

Per repo CLAUDE.md's PR workflow, before opening the PR:

- `./gradlew :protocol:test :app:testDebugUnitTest` — green.
- `./gradlew :app:lintDebug` — green. Not optional and not implied by the tests: lint was red on
  `main` across all of S11–S13 precisely because every implementer ran tests and skipped it.
- `TonexSessionHolderTest`'s four cases in §4 exist and pass. The FGS-gate case is the one that
  matters; a PR without it is not done.
- Adding a real-hardware smoke check is welcome but is not a merge gate — no pedal exists in this
  environment.

---

## 7. What this document's own verification covers

Every class, file, method, and line reference above was read on this branch at `c1876ea`. The
navigation dependency and `TonexRoute.kt` were compiled (`:app:compileDebugKotlin`). Nothing here
has been run against real hardware, and nothing about the S13 foreground-service prerequisite
(issue #18's open real-device question) is resolved by this document.
