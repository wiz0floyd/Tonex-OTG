# S22 preset-diff drill: silent death after the second Hello write (static analysis)

Diagnosing the 2026-08-19 hardware run where the log ends immediately after
`runPresetChangeSafetyDrill`'s Hello `WRITE` — no `READ`, no timeout error, no
`[ERROR]`, no crash-capture entry, despite PR #70's real-time file sink. This
is a static-analysis pass only; no hardware is available in this environment.
No fix is proposed here — see issue #27 for why a fix without hardware
confirmation is explicitly discouraged on this project.

## 1. Confirmed from static reading

- **Sequencing between drills is safe — no coroutine race.** `runSafetyBackup`
  (`ProbeSession.kt:707-767`) and `runPresetChangeSafetyDrill`
  (`ProbeSession.kt:798-911`) are invoked from two *separate* `scope.launch {}`
  blocks in `ProbeActivity.kt` (backup: line 247, preset-diff: line 460), each
  gated by a `busy` flag that disables the trigger button until the previous
  coroutine's `finally { busy = false }` has run. A human click is required
  between them. So `runSafetyBackup`'s `finally` (healthJob.cancel() →
  `controller.disconnect()` inside `NonCancellable` → `transport.close()`,
  lines 752-767) is guaranteed to have fully returned — including the blocking
  parts described below — before `runPresetChangeSafetyDrill`'s own
  `UsbRequestTonexTransport` constructor (`ProbeSession.kt:807`) ever runs.
  This rules out a same-process-tick race at the `ProbeSession`/`ProbeActivity`
  level.
- **`DefaultTonexController.disconnect()` correctly stops consuming the old
  transport before it's closed.** `disconnect()` → `teardown()`
  (`DefaultTonexController.kt:492-495`) calls `readerJob?.cancelAndJoin()`,
  cancelling the coroutine collecting `transport.incoming()`, before
  `ProbeSession`'s `finally` block goes on to call `transport.close()`. So the
  old controller is not still trying to read from the old transport by the
  time `close()` runs. Nothing cute in `LoggingTonexTransport.kt` or
  `MessageCaptureTap.kt` either — both are plain `Flow.onEach`/pass-through
  decorators with no buffering or blocking behavior of their own.
- **The genuine, confirmed gap is inside `UsbRequestTonexTransport.close()`/
  `eventLoop()`** (`UsbRequestTonexTransport.kt`):
  - `close()` (lines 263-267) does `loopThread.interrupt(); loopThread.join(LOOP_WAIT_TIMEOUT_MILLIS * 2)` (2 × 500ms = 1000ms) and returns
    **without checking `loopThread.isAlive` afterward**. If the join doesn't
    actually succeed within that window, `close()` silently proceeds anyway.
  - The class's own KDoc (lines 36-46) states, as a load-bearing invariant,
    that **exactly one thread may ever call `connection.requestWait()`** on a
    given `UsbDeviceConnection`, because `requestWait()` returns whichever
    queued `UsbRequest` (on *any* endpoint) completed next — there is no
    per-endpoint variant — and two concurrent callers "could receive the
    other's completed request, silently misrouting a write completion as a
    read or vice versa." That reasoning is written for the write-vs-read-loop
    case *within one transport instance*; it applies identically, and is
    **not guarded against**, across two different `UsbRequestTonexTransport`
    instances (the backup drill's and the preset-diff drill's) sharing the
    same physical `connection`/`inEndpoint`/`outEndpoint` sequentially. If the
    old instance's `loopThread` is still alive when the new instance's
    constructor runs (`ProbeSession.kt:807`) and starts its own `loopThread`
    (`UsbRequestTonexTransport.kt:130-137`), there are now two threads calling
    `requestWait()` on the same connection. A completion belonging to the new
    transport's `inRequest`/`outRequest` can be handed to the *old* thread's
    `requestWait()` call, which compares by reference against its own
    (different) `inRequest`/`outRequest` objects, matches neither, and falls
    into `else -> Unit` (line 191, "a stale/cancelled request draining
    through close(); nothing to do") — silently discarding it. The new
    transport's collector never sees it.
  - **The `WRITE 17 byte(s)` log line does not prove the write itself
    completed.** `LoggingTonexTransport.write()` (`LoggingTonexTransport.kt:51-56`)
    logs the hex dump **before** calling `delegate.write(bytes)`, not after.
    So the log line is consistent with either "the write completed but no
    response ever arrived" or "the write's own `completion.await()`
    (`UsbRequestTonexTransport.kt:236-248`) never resolved at all" — both are
    equally invisible from this log alone. The two-loop-thread race above can
    swallow *either* the OUT (write) or IN (read) completion, so it explains
    both possibilities.
- **The Hello step has a real, working timeout — which sharpens the puzzle
  rather than resolving it.** `DefaultTonexController.connect()`'s Hello stage
  uses `requestAndAwait(..., timeouts.helloMillis, ...)`
  (`DefaultTonexController.kt:553`), and `requestAndAwait` wraps the wait in
  `withTimeoutOrNull(timeoutMillis)` (`DefaultTonexController.kt:350`) — a
  self-contained coroutine timeout, independent of the outer `NonCancellable`
  wrapping (`NonCancellable` only blocks *external* cancellation of the
  drill's own job; it does not disable a child `withTimeoutOrNull`'s internal
  cancellation). `ConnectionTimeouts.DEFAULT.helloMillis = 2_000L`
  (`ConnectionTimeouts.kt:93`). If nothing more serious happened, the expected
  failure path is: 2 seconds after the write, `requestAndAwait` times out,
  `connect()` returns `TonexResult.Failure(TonexError.Timeout("hello", 2000))`,
  and `ProbeSession.kt:818` logs `"Preset-change drill: connect() failed: ..."`.
  **That line is completely absent from the log**, along with everything
  else — no timeout error, no `[FINDING]`, no `[ERROR]`, no crash-capture
  entry, ever. Every ordinary "no response" code path in this codebase is
  timeout-bounded and logs on failure; the log's total silence after the
  write is therefore not explained by any single normal timeout firing. It
  requires either (a) the whole process dying outright (a native crash
  bypasses the JVM entirely, including the crash-capture sink, which only
  catches JVM-level `Thread.UncaughtExceptionHandler`), or (b) something
  external to these coroutine-timeout-protected paths freezing before the
  2-second window could even elapse and flush.

## 2. Ranked hypotheses

1. **(Highest plausibility) Two `UsbRequestTonexTransport.loopThread`s
   concurrently calling `connection.requestWait()` on the same
   `UsbDeviceConnection`**, because `close()` (backup drill's transport)
   silently proceeds without confirming `loopThread` actually exited
   (`UsbRequestTonexTransport.kt:263-267`, no `isAlive` check after `join`).
   This is a confirmed code gap, not speculation. Two outcomes are both
   consistent with the evidence and both plausible from this same root cause:
   - **Silent hang**: the preset-diff drill's Hello response (or even its
     Hello write's own OUT completion) gets handed to the dying old thread's
     `requestWait()` call and discarded via the `else -> Unit` branch,
     leaving the new transport's `completion.await()`/incoming flow waiting
     forever. This alone should still surface via `withTimeoutOrNull`/
     `withTimeout` at 1-2 seconds — so this sub-hypothesis on its own does
     **not** fully explain total silence, unless the timeout mechanism itself
     never got to run (see native-crash sub-hypothesis).
   - **Native crash**: two Java-level `UsbRequest` object graphs driving
     native `requestWait`/`queue` calls concurrently against the same kernel
     USB file descriptor is not a scenario AOSP's `UsbDeviceConnection`
     documents as safe. A native-layer crash (SIGSEGV) here would kill the
     process immediately and unconditionally, explaining every observed
     symptom at once: no timeout log (the process is gone before 2s), no
     `[ERROR]` catch block firing (a native crash bypasses Kotlin
     exception handling), and no crash-capture sink entry (PR #70's sink
     hooks `Thread.setDefaultUncaughtExceptionHandler`, which a native
     signal does not go through).
   - **Confidence: medium-high** that this mechanism (stale loop thread
     surviving into the next transport's lifetime) is real and reachable;
     **unconfirmed** whether it manifests as a hang or a crash without a
     tombstone.
2. **(Medium plausibility) ANR from a genuine deadlock**, but this fits the
   evidence worse than hypothesis 1: every code path in `connect()` that
   could stall has a working, independent timeout that logs a specific error
   on firing (confirmed above), so an ordinary "pedal didn't respond" hang
   should have surfaced an error line within ~2 seconds, not silence forever.
   An ANR would require the hang to sit *outside* those protected paths —
   e.g., if `transport.close()`'s `loopThread.join(1000ms)`
   (`UsbRequestTonexTransport.kt:266`) were somehow blocking the same thread
   that needs to service `withTimeoutOrNull`'s delayed cancellation — but
   that call already completes (successfully or not) inside the *backup*
   drill's `finally`, fully before the *preset-diff* drill's `connect()` is
   even invoked (per point 1 above), so it can't be the thing blocking the
   later timeout. No comparably concrete code-level mechanism was found for
   this hypothesis; ranked below hypothesis 1 for that reason.
3. **(Low plausibility) `NonCancellable` wrapping causing a silent freeze on
   its own**, independent of the USB-reuse theory. Ruled largely out:
   `withTimeoutOrNull` establishes its own child-job cancellation that is not
   defeated by the outer `NonCancellable` context — `NonCancellable` only
   prevents *external* cancellation of the drill's own coroutine, not the
   independent 2-second Hello timeout from firing and returning normally.
   For this hypothesis to hold, some other unbounded suspend call with no
   timeout would need to exist inside the `NonCancellable` block; none was
   found by inspection of `runPresetChangeSafetyDrill`
   (`ProbeSession.kt:831-893`) — every suspend call inside it
   (`runPresetChangeByteDiffDrill`, `controller.selectPreset`,
   `captureStateBlob`) ultimately routes through `requestAndAwait`'s bounded
   `withTimeoutOrNull`.

## 3. Evidence still needed to discriminate

None of the above can be confirmed further without hardware. Specifically
needed from the user's device, from the exact run that produced the silent
log:

- **`adb logcat` captured across the crash** (ideally started *before* the
  probe run, e.g. `adb logcat -b all > log.txt` left running through the
  whole session, not pulled after the fact — Android's ring buffer can
  overwrite an ANR/tombstone reference if too much else logs afterward).
- **A tombstone**, if one exists: `adb shell ls -la /data/tombstones/` (may
  require root) or `adb bugreport`, which bundles tombstones automatically.

What each pattern would tell us:

| logcat signature | What it confirms |
|---|---|
| `Fatal signal 11 (SIGSEGV)` (or SIGABRT/SIGBUS) mentioning `libusbhost`, `UsbRequest`, `usbfs`, or similar, with a native backtrace | Confirms hypothesis 1's native-crash sub-case — a genuine native-layer hazard from two `UsbRequest` graphs / two `requestWait()` callers on one connection. Would justify a real fix: either serializing transport teardown/construction with a hard wait for `loopThread` to be dead (not just a timed `join`), or never reusing the same `UsbDeviceConnection` across drills without releasing and reopening it. |
| `ANR in dev.tonexotg.app` with an "Input dispatching timed out" or "executing service" reason, plus a main-thread stack trace | Confirms hypothesis 2 — points at wherever the reported stack is actually blocked (would directly show whether it's inside `UsbDeviceConnection` native calls, a lock, or something in Compose/coroutine dispatch). |
| `IllegalStateException` mentioning `UsbRequest` (e.g. "already queued") or `queueRequest`/`requestWait` throwing before the write even logs | Would mean the *first* line of evidence (the WRITE log) masks an earlier failure than assumed — worth re-checking `write()`'s own exception paths (`UsbRequestTonexTransport.kt:219-231`), which currently only reports `IllegalStateException` from `queue()`, not from `requestWait()`/`initialize()`. |
| Total absence of any of the above (logcat also goes silent, or shows an OS-level watchdog kill / `Zygote`-level process death with no Java or native stack) | Would point toward something even lower-level — e.g. the kernel USB driver or the OS itself killing the process (out-of-memory killer, USB host controller reset) — and would argue against a purely app-code fix being sufficient at all. |

## 4. Explicitly not done here

No fix was attempted. Per this repo's CLAUDE.md ("Review rigor, scaled to
blast radius" and "Findings that can't be resolved without hardware"),
this finding should sit on issue #27 until a real device confirms which
mechanism is actually firing — a fix aimed at the wrong mechanism (e.g.
"just add an `isAlive` check to `close()`" when the real cause turns out to
be an ANR somewhere else) risks papering over the real bug the same way a
self-consistent test suite once hid the S7 wire-format bug on this project.
