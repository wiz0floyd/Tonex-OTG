> **S9b implementation plan — part 1 of 2** (Opus architecture pass). Part 2 covers §F-§K. The same text is on the branch as `protocol/S9B_ARCHITECTURE_PLAN.md`, which the implementing agent deletes in its final commit.

# S9b Architecture Plan — Preset snapshot and revert (write-safety net)

Issue: [#14](https://github.com/wiz0floyd/Tonex-OTG/issues/14). Depends on S7, S9 (#13, merged
in PR #43 as `1d04023`). Related: #25 / S20 (hardware probe).

---

## 0. Read this first: the briefing's protocol assumption is wrong, and the issue is right

The dispatch briefing for this design stated that "S9b's snapshot capture needs the **full**
109-parameter-float variant (`PresetDetailsKind.FULL`)." **That is incorrect.** Issue #14's own
text — `request_preset_details(idx, 0)`, i.e. `full_details = 0x00` = **SUMMARY** — is correct.

Per CLAUDE.md ("hand-check protocol literals against the actual upstream source rather than
trusting a citation"), this was verified by fetching upstream at the commit
`ParameterRegistry` already pins, `7079f157107a7bc91f171e51e3da0d799d31fcfb`, file
`source/main/usb_tonex_one.c`. The evidence:

1. **Upstream reads preset parameters from the SUMMARY response.**
   `usb_tonex_one.c:1222` requests `usb_tonex_one_request_preset_details(current_preset, 0)` —
   `full_details = 0`. The response is wire type `0x0304`
   (`MessageType.PresetDetailsSummary` → `TonexMessage.PresetDetails(full = false, …)`), which
   upstream classifies as `TYPE_STATE_PRESET_DETAILS` (`:1049-1053`). The 109-float parse,
   `usb_tonex_one_parse_preset_parameters(data, length)`, is called from inside
   `case TYPE_STATE_PRESET_DETAILS:` at **`usb_tonex_one.c:1232`**.

2. **Upstream deliberately ignores the FULL response.** Wire type `0x0303`
   (`MessageType.FullPresetDetails`) is handled twice, and both are no-ops:
   - `usb_tonex_one.c:1112-1116`:
     `case TYPE_STATE_PRESET_DETAILS_FULL: { // don't need to process this anymore, thanks to IK
     new parameter comms :) return STATUS_OK; }`
   - `usb_tonex_one.c:1282-1286`: `case TYPE_STATE_PRESET_DETAILS_FULL: { // ignore … } break;`

   There is **no upstream code anywhere that parses a `0x0303` payload.** Building S9b against
   `PresetDetailsKind.FULL` would mean inventing protocol truth for a ~30 KB response no
   reference implementation has ever decoded — precisely what this project's house rules forbid,
   and it would additionally blow past `FrameReassembler.MAX_FRAME_CONTENT_BYTES` (8 KB).

**Consequence for the whole design:** the snapshot capture request is byte-identical to the
preset-name harvest request that `harvestPresetNames()` already issues —
`RequestPresetDetailsMessage.encode(idx, PresetDetailsKind.SUMMARY)`. The same ~2 KB response
carries *both* the name field (via `PresetNameExtractor`'s `B9 04 B9 02 BC 21` marker) and the
parameter block (via a *different* marker, below). S9b adds a second extractor over the same
payload shape; it adds no new request shape and no new `PresetDetailsKind`.

`PresetDetailsKind.FULL` stays in the codebase, unused, correctly documented. Do not delete it
and do not use it.

### 0.1 The parameter block's exact wire format (verified upstream literal)

`usb_tonex_one.c:955-1005`, `usb_tonex_one_parse_preset_parameters`:

```c
static void usb_tonex_one_parse_preset_parameters(uint8_t* raw_data, uint16_t length)
{
    uint8_t param_start_marker[] = {0xBA, 0x03, 0xBA, 0x6D};
    ...
    uint8_t* temp_ptr = memmem((void*)raw_data, length,
                               (void*)param_start_marker, sizeof(param_start_marker));
    if (temp_ptr != NULL)
    {
        temp_ptr += sizeof(param_start_marker);          // skip the marker
        ...
            // params here are start marker of 0x88, followed by a 4-byte float
            for (uint32_t loop = 0; loop < TONEX_PARAM_LAST; loop++)
            {
                if (*temp_ptr == 0x88)
                {
                    temp_ptr++;                          // skip the marker
                    memcpy((void*)&param_ptr[loop].Value, (void*)temp_ptr, sizeof(float));
                    temp_ptr += sizeof(float);           // skip the float
                }
                else
                {
                    ESP_LOGW(TAG, "Unexpected value during Param parse: ...");
                    break;
                }
            }
    ...
}
```

Decoded:

- **Block marker:** `BA 03 BA 6D` (4 bytes), located by search over the payload, **not** at a
  fixed offset — exactly the same discipline `PresetNameExtractor` already uses for its own
  marker.
- **Immediately after the marker:** `TONEX_PARAM_LAST` repetitions of `0x88` followed by a
  little-endian `float32`. **5 bytes per parameter, 545 bytes total.**
- **`TONEX_PARAM_LAST` = 109.** Verified in `tonex_params.h:59-198`: `enum TonexParameters`
  starts at `TONEX_PARAM_NOISE_GATE_POST` (implicitly 0) and `TONEX_PARAM_LAST` is declared
  immediately after the last real parameter with the comment `// must be last actual parameter`,
  before the seven `TONEX_GLOBAL_*` entries. This is the same sentinel `ParameterId` already
  documents ("Index `109` is a `"LAST"` sentinel … deliberately excluded from `VALID_RANGE`") and
  matches `PresetSnapshot.PARAMETER_COUNT = 109` and `ParameterId.PRESET_RANGE = 0..108` exactly.
- **Ordering is positional:** `param_ptr[loop]` for `loop = 0..108` — the *n*-th float in the
  block is the value of `ParameterId(n)`. This is the same "defined in the same order as they are
  sent by the Pedal" positional convention `ParameterId`'s KDoc already states. **This is the
  indexing convention `PresetSnapshot` must use.**
- **`0x88` + LE float32 is not a new encoding.** It is exactly `TonexVarint.MARKER_FLOAT32`
  (`Varint.kt:87`) / `VarintValue.FloatValue`, already implemented and tested, and the same form
  `SingleParameterPayloadCodec` uses. Reuse `TonexVarint.decode`; do not hand-roll a float read.

### 0.2 One deliberate divergence from upstream, and why it is a fix not a risk

Upstream runs `memmem` over `data` — the **raw, still-HDLC-framed, still-byte-stuffed** buffer
(`usb_tonex_one.c:1232` passes `data`, not the unstuffed `FramedBuffer`). We run our extractor
over the **decoded payload** (`TonexMessage.PresetDetails.payload`, post-unstuffing,
post-header-strip), exactly as `PresetNameExtractor` already does.

Ours is strictly more correct. The marker bytes themselves (`BA 03 BA 6D`) contain neither `0x7E`
nor `0x7D`, so the marker is never escaped and both approaches find it. But **float payload bytes
routinely can be `0x7E` or `0x7D`** and therefore *are* escaped on the wire. Upstream's raw-buffer
walk would silently misparse every float containing such a byte and would then hit a non-`0x88`
byte and `break` — leaving the remaining parameters at stale values with only a log warning. Our
decoded-payload walk cannot have that bug. Record this reasoning in the new extractor's KDoc.

### 0.3 What upstream gets wrong that we must not copy

Upstream's `break` on an unexpected byte leaves `param_ptr[loop..108]` holding **whatever was
there before** and reports nothing to the caller — a partially-populated table indistinguishable
from a complete one. That is exactly "patch-and-hope." Our extractor **must** fail the entire
extraction with a typed error and produce no partial array (§A).

---

## 1. Scope of the change

New files (2):

| Path | Purpose |
|---|---|
| `protocol/src/main/kotlin/dev/tonexotg/protocol/message/PresetParameterExtractor.kt` | §A — decode the 109-float block out of a preset-details payload |
| `protocol/src/test/kotlin/dev/tonexotg/protocol/message/PresetParameterExtractorTest.kt` | §H.1 |

Modified files (main, 4):

| Path | Change |
|---|---|
| `…/protocol/PresetSnapshot.kt` | §B — implement `valueOf` / `toMap` (remove both `TODO()`s) |
| `…/protocol/TonexError.kt` | §D — add `RevertIncomplete` |
| `…/protocol/connection/ConnectionTimeouts.kt` | §C — add `presetParametersMillis` |
| `…/protocol/connection/DefaultTonexController.kt` | §E/§F/§G — capture, write-path refactor, replay |

Modified files (test, 8): `ConnectionTestFixtures.kt`, `ConnectionTimeoutsTest.kt`,
`DefaultTonexControllerHandshakeTest.kt`, `DefaultTonexControllerHarvestTest.kt`,
`DefaultTonexControllerSetParameterTest.kt`, `DefaultTonexControllerDisconnectTest.kt`, plus two
new test files. Full breakdown in §H.

Explicitly **not** changed, and do not change them:

- `PedalState`, `SessionId`, `ReadGeneration`, `StateBlobPatcher`, `StateBlobReader`. Verified in
  §E.0.
- `revertActivePreset`'s guards 1-4. Verified in §G.1.
- `InMemorySnapshotStore` — already complete and already wired.
- `SnapshotStore` — already fully specified; implement to it, do not extend it.
- `TonexController` / `TonexEvent` KDoc — already written for S9b. §F.3 resolves its one
  documented ambiguity in prose only; no signature changes.
- `PresetDetailsKind`, `RequestPresetDetailsMessage` — unchanged (§0).

---

## 2. Design decisions, stated up front

| # | Question | Decision |
|---|---|---|
| D1 | Which detail level does capture request? | `PresetDetailsKind.SUMMARY` (§0). Byte-identical to the name-harvest request. |
| D2 | When does capture fire? | Two call sites: end of `connect()` for the initial active preset, and `applyStateUpdate()` on **every** active-preset transition — self-initiated and external alike (§E.2/§E.3). |
| D3 | Is capture fatal to `connect()`? | **No.** Best-effort, like `harvestMasterVolume()`. A failed capture records **nothing**, and `revertActivePreset` then refuses loudly with `NoSnapshotAvailable` — a path `TonexError.NoSnapshotAvailable`'s KDoc already anticipates verbatim (§E.5). |
| D4 | Is capture capability-gated? | **No.** It is a read, and the name harvest — the identical request — is not gated either. (Contrast `harvestMasterVolume`, gated only because upstream's *master-volume request* carries the Editor-firmware comment; `request_preset_details` does not.) |
| D5 | `FirstDestructiveWrite`: pre-write gate or post-hoc notice? | **Post-hoc notice, emitted only after the write actually succeeds** (§F.3). |
| D6 | Does revert reuse `setParameter`? | It **cannot** (`Mutex` is not reentrant → deadlock). Extract a shared `writeParameterLocked` used by both (§F.1). |
| D7 | Does revert write all 109 or only the diff? | **All 109, unconditionally, ascending wire index** (§G.3). |
| D8 | Partial revert failure? | Abort at the first failure, return new `TonexError.RevertIncomplete` carrying applied-count / failed parameter / underlying cause. Snapshot is retained so a retry is possible (§D, §G.4). |
| D9 | Snapshot value outside its registry range? | Pre-validate **all** 109 before issuing **any** write; refuse the whole revert with `ParameterValueOutOfRange` and zero writes (§G.2). |
| D10 | Does capture publish into `parameterValues`? | **Yes** — this is what makes that flow's existing "Updated on preset load/change" contract true (§E.4). |

---

## §A `PresetParameterExtractor` (new)

**Path:** `protocol/src/main/kotlin/dev/tonexotg/protocol/message/PresetParameterExtractor.kt`
**Package:** `dev.tonexotg.protocol.message`

Model this file on `PresetNameExtractor.kt` *exactly* — same upstream attribution header block
(per `CONTRIBUTING.md`; the upstream file is `source/main/usb_tonex_one.c`, Apache-2.0, Copyright
2025 Greg Smith), same private-`MARKER` + public-`marker()`-defensive-copy idiom and the same
reasoning in its KDoc, same `TonexResult` discipline, same "never throws" guarantee.

```kotlin
object PresetParameterExtractor {

    /** `param_start_marker` — `usb_tonex_one.c:957`. Private for the same reason as
     *  PresetNameExtractor.MARKER: a public ByteArray val is a shared mutable array. */
    private val MARKER: ByteArray =
        byteArrayOf(0xBA.toByte(), 0x03, 0xBA.toByte(), 0x6D)

    /** Bytes per parameter in the block: the 0x88 float32 marker plus its four float bytes. */
    const val BYTES_PER_PARAMETER: Int = 5

    /** A fresh defensive copy of `param_start_marker`. */
    fun marker(): ByteArray = MARKER.copyOf()

    /**
     * Extracts the 109 preset-parameter floats from a preset-details payload.
     * Returns a FloatArray of exactly PresetSnapshot.PARAMETER_COUNT entries, positionally
     * indexed: result[n] is the value of ParameterId(n).
     */
    fun extract(payload: ByteArray): TonexResult<FloatArray>
}
```

### A.1 `extract` algorithm

1. Locate `MARKER` in `payload` by forward search (copy `PresetNameExtractor.indexOfMarker`'s
   loop — do not add a dependency between the two objects, and do not extract a shared helper for
   two 10-line loops).
   Not found → `TonexResult.Failure(TonexError.MalformedFrame("preset parameters: marker not found in payload"))`.
2. `val blockStart = markerIndex + MARKER.size`.
3. Bounds check the whole block up front:
   `val needed = PresetSnapshot.PARAMETER_COUNT * BYTES_PER_PARAMETER` (= 545).
   If `blockStart + needed > payload.size` →
   `TonexResult.Failure(TonexError.UnexpectedBlobShape(expectedSize = needed, actualSize = (payload.size - blockStart).coerceAtLeast(0)))`.
   (Same error case, same shape, as `PresetNameExtractor`'s truncation branch.)
4. Loop `i` in `0 until PresetSnapshot.PARAMETER_COUNT`, `offset = blockStart + i * BYTES_PER_PARAMETER`:
   - `TonexVarint.decode(payload, offset)`; on `Failure`, return it unchanged (it already carries
     a precise reason).
   - The decoded `DecodedVarint.value` **must** be `VarintValue.FloatValue` and
     `bytesConsumed` **must** equal `BYTES_PER_PARAMETER`. If either does not hold →
     `TonexResult.Failure(TonexError.MalformedFrame("preset parameters: expected a float32 (0x88) marker for parameter $i at offset $offset, got …"))`.
     This is the case upstream `break`s on; **we fail the whole extraction** (§0.3).
   - Store into a local `FloatArray(109)`.
5. Return `TonexResult.Success(values)`.

**Never return a partially-populated array.** The `FloatArray` is a function-local built before
any return, so a failure path structurally cannot leak one.

**Note on `TonexVarint.decode`:** confirm the signature is `decode(bytes: ByteArray, offset: Int = 0)`
returning `TonexResult<DecodedVarint>` with `DecodedVarint(value: VarintValue, bytesConsumed: Int)`
— it is, at `Varint.kt:100`/`:41`. Using the strict `bytesConsumed == 5` check is what makes an
`0x80`/`0x81`/`0x82` int-marker at a parameter position a hard failure rather than a silent
desync.

---

## §B `PresetSnapshot.valueOf` / `toMap`

Replace both `TODO(...)` bodies in
`protocol/src/main/kotlin/dev/tonexotg/protocol/PresetSnapshot.kt`. No signature changes; the
existing `init { require(size == PARAMETER_COUNT) }` and `private val values = parameterValues.copyOf()`
stay as they are (the defensive copy is already correct and is one half of the aliasing test in §H).

```kotlin
fun valueOf(id: ParameterId): Float {
    require(id.index in ParameterId.PRESET_RANGE) {
        "PresetSnapshot covers only the ${PARAMETER_COUNT} PRESET-scoped parameters " +
            "(${ParameterId.PRESET_RANGE}); $id is not one of them"
    }
    return values[id.index]
}

fun toMap(): Map<ParameterId, Float> =
    ParameterId.PRESET_RANGE.associate { ParameterId(it) to values[it] }
```

**Indexing convention (load-bearing, document it in the KDoc):** `values[n]` is the value of
`ParameterId(n)`; position *is* identity. This is not an arbitrary choice — it is forced by
upstream's `param_ptr[loop]` (§0.1) and by `ParameterId`'s own "index is positional and
load-bearing" contract. Because `PRESET_RANGE` is exactly `0..108` and `PARAMETER_COUNT` is
exactly 109, `id.index` *is* the array index with no offset table and no registry lookup. Do not
introduce one.

`require` (throwing) rather than `TonexResult` is correct here and consistent with the file's
existing KDoc, which already documents `@throws IllegalArgumentException`: passing a GLOBAL-scoped
id to a preset snapshot is a programmer error with no safe reinterpretation, matching the
`ParameterWriteMessage.encode` / `TonexVarint.encodeInt` precedent.

`toMap()` returns a fresh `Map` each call (`associate` builds a new `LinkedHashMap`) — the second
half of the aliasing guarantee.

---

## §C `ConnectionTimeouts.presetParametersMillis`

Add one field to the `data class`, positioned **after `presetDetailsMillis`**:

```kotlin
val presetParametersMillis: Long,
```

- **`TonexError.Timeout.operation` string:** `"preset-parameters"`.
- **`DEFAULT` value: `3_000L`.**
- **KDoc `@property` line:** "budget for the snapshot-capture preset-details round trip.
  `operation == "preset-parameters"`."
- **Justification row for the `DEFAULT` table** (write it in this style, it is an acceptance
  criterion that no timing constant is a bare magic number):

  > `presetParametersMillis` | 3000 | The snapshot-capture read. The request is byte-identical to
  > the name harvest's and the response is the same ~2 KB summary, so the budget is numerically
  > equal to `presetDetailsMillis` — but it is kept as a **separate field for the same reason
  > `stateReadMillis` is separate from `getStateMillis`**: it is a different operation with a
  > different `operation` string, so a timeout here is diagnosable as "the snapshot capture
  > failed" (benign — revert is unavailable) rather than "the preset-name harvest failed" (fatal
  > to `connect`), and the two can be tuned independently later.

**No default parameter value** — the existing five fields have none, and adding one only to the
sixth would be inconsistent. This means the three explicit `ConnectionTimeouts(...)` constructions
in `ConnectionTimeoutsTest.kt` must be updated (§H.3); that is intentional friction, and it is
exactly what proves the field is genuinely injected.

---

## §D `TonexError.RevertIncomplete`

Add one case to the sealed class in `TonexError.kt`. Place it immediately after
`NoSnapshotAvailable` (they are the two revert-specific cases).

```kotlin
data class RevertIncomplete(
    val presetIndex: PresetIndex,
    val appliedCount: Int,
    val totalCount: Int,
    val failedParameter: ParameterId,
    val cause: TonexError,
) : TonexError() {
    override val message: String
        get() = "Revert of preset ${presetIndex.value} stopped after $appliedCount of " +
            "$totalCount parameter writes: parameter ${failedParameter.index} failed " +
            "(${cause.message}). The preset is now in a mixed state — $appliedCount snapshot " +
            "values were restored and the remainder are as they were. The snapshot is retained; " +
            "retrying revert is safe."
}
```

KDoc must state:

- Why this is not just the underlying error: FR11 requires surfacing a failure rather than
  assuming success, and a bare `TransportFailure` from write 47 of 109 tells a caller nothing
  about the 46 writes that *did* land. This case is what lets the UI say something true.
- `cause` is the underlying `TonexError` from the failing write, unwrapped and preserved.
- `appliedCount` writes, for `ParameterId(0)` through `ParameterId(appliedCount - 1)`, are known
  to have been accepted by the transport; `failedParameter` is `ParameterId(appliedCount)`.
  This exactness is only meaningful because §G.3 fixes the replay order.
- The snapshot is deliberately **not** discarded — retrying `revertActivePreset()` is safe and
  idempotent (§G.5).
- ⚠️ "Accepted by the transport" is not "confirmed applied by the pedal." Issue #14 explicitly
  rejects per-write read-back verification; this project does not pay a verification round trip.
  Say so in the KDoc so nobody later mistakes `appliedCount` for a pedal-confirmed count.

`TonexErrorTest.kt` has a test per case; add one asserting `message` mentions the counts and
names the failed parameter.

---

## §E Capture — `DefaultTonexController`

### E.0 Verified: capture needs **no** `PedalState` / `SessionId` / generation machinery

The briefing asked me to verify rather than assume this. **Confirmed, with one clarification.**

- Capture reads a **preset-details** message (`0x0304`), not a **state** message (`0x0306`).
  `PedalState.create` — and therefore `SessionId.mintReadGeneration()` — is called at exactly one
  site, `handleMessage`'s `is TonexMessage.StateUpdate` branch, which capture never enters.
- The read-generation machinery (`mintReadGeneration` / `consumeReadGeneration` /
  `invalidateCurrentRead` / `pinOrWidenBlobSize`) exists solely to authorize *whole-state blob
  writes* through `StateBlobPatcher`. Revert issues per-parameter writes and never touches the
  blob, so no generation is ever minted, spent, or invalidated by anything in S9b.
- Snapshots are keyed by `PresetIndex` in `InMemorySnapshotStore` and stamped with `SessionId` on
  `PresetSnapshot`. **Clarification:** the `SessionId` is used purely as an identity stamp —
  `PresetSnapshot`'s `internal constructor` requires one, and `DefaultTonexController` already
  holds `@Volatile private var session: SessionId?`. S9b calls **no** `internal` method on it.
  Cross-session staleness is handled structurally instead, by `teardown()` already calling
  `snapshotStore.clear()`; the stamp is belt-and-braces provenance, not an active check.
- One genuine consequence to honour: because `requestAndAwait`'s timeout path calls
  `session?.invalidateCurrentRead()` (contract call site 3), a **capture timeout will invalidate
  the current read generation**. That is correct and desirable — it only means "any `PedalState`
  read before this is no longer authorized to write," which is exactly right after a stall — and
  it costs nothing, because `selectPreset` always re-reads immediately before patching anyway. No
  change needed; just do not be surprised by it.

**Do not add a `PedalState` field, a snapshot-generation counter, or any new `SessionId` method.**

### E.1 The capture function

Add to `DefaultTonexController`, in a new `// ---- snapshot capture (S9b / §E) ----` section
placed **after** `harvestMasterVolume()` and **before** `disconnect()`.

```kotlin
private suspend fun captureSnapshotLocked(index: PresetIndex): TonexResult<Unit>
```

**Contract (state it in the KDoc):** the caller **must** already hold `operationMutex`.
`kotlinx.coroutines.sync.Mutex` is not reentrant, so this function must never take it itself —
`connect()` calls it while holding the lock (§E.2), and the launched path acquires the lock
*around* the call (§E.3).

Body, in order:

1. **Re-check liveness.** `if (_connectionState.value !is ConnectionState.Ready) return TonexResult.Failure(ProtocolStateViolation(_connectionState.value, "snapshot capture requires Ready"))`.
   Load-bearing for the launched path (§E.3), which can be dispatched after a racing
   `disconnect()`/detach.
2. **Pin the session identity.** `val s = session ?: return TonexResult.Failure(ProtocolStateViolation(_connectionState.value, "no session (internal invariant violated)"))`.
   Mirrors `selectPreset`'s existing guard verbatim.
3. **Request and await**, mirroring `harvestPresetNames`' awaiter shape exactly:

   ```kotlin
   val payload = requestAndAwait(
       RequestPresetDetailsMessage.encode(index, PresetDetailsKind.SUMMARY),
       timeouts.presetParametersMillis,
       "preset-parameters",
   ) { inb ->
       commonInbound("preset-parameters", inb) ?: when {
           inb is Inbound.Message && inb.message is TonexMessage.PresetDetails &&
               !(inb.message as TonexMessage.PresetDetails).full ->
               TonexResult.Success((inb.message as TonexMessage.PresetDetails).payload)
           else -> null // StateUpdate / ParameterChanged / Other interleaved: ignore, keep waiting
       }
   }.orReturn { return it }
   ```

   Note `!…full`: match the SUMMARY response specifically. See §E.6.
4. **Extract.** `val values = PresetParameterExtractor.extract(payload).orReturn { return it }`.
5. **Re-check that the capture is still about the currently active preset.**

   ```kotlin
   if (session !== s || _activePreset.value != index) {
       return TonexResult.Failure(
           TonexError.ProtocolStateViolation(
               _connectionState.value,
               "the active preset changed while preset ${index.value}'s snapshot was being " +
                   "captured; discarding the capture rather than recording a possibly-wrong snapshot",
           ),
       )
   }
   ```

   **Why this is not paranoia.** The reader coroutine can change `_activePreset` at any moment
   (a footswitch press). Upstream only ever parses the parameter block out of the response to a
   request for the *currently active* preset (`usb_tonex_one.c:1222` requests
   `current_preset`), so whether a *non-active* preset's summary even contains a parameter block
   — and if so, whether it contains that preset's own stored values or the live active preset's
   — **is unverified and unverifiable without hardware.** Rather than pin a guess, discard the
   capture. `SnapshotStore.record`'s KDoc already states the governing rule: "a stale snapshot
   from a different preset is worse than none." Filed for hardware on #25 (§I.1).
6. **Publish, then record — in that order.**

   ```kotlin
   applyCapturedValues(values)          // §E.4
   snapshotStore.record(PresetSnapshot(index, s, values))
   return TonexResult.Success(Unit)
   ```

   `PresetSnapshot`'s constructor copies `values`, so handing it the same array
   `applyCapturedValues` just read from is safe. `applyCapturedValues` must not retain the array
   either (§E.4 builds a `Map<ParameterId, Float>` of boxed floats, so it cannot).

### E.2 Call site 1 — `connect()`, the initial active preset

In `connect()`'s post-Ready harvest block, add one line after `harvestMasterVolume()`:

```kotlin
        // ---- Post-Ready harvest -------------------------------------------------------------
        harvestPresetNames().orFinish { return@withLock it } // fatal — see harvestPresetNames' KDoc
        harvestMasterVolume()                 // best-effort, result ignored
        captureSnapshotLocked(initialActive)  // best-effort, result ignored — see D3 / §E.5
        TonexResult.Success(Unit)
```

`connect()` already holds `operationMutex`, satisfying `captureSnapshotLocked`'s contract.
`initialActive` is already in scope (it is the local read from the handshake blob at
`StateBlobReader.activePreset(blob).orFinish { … }`). Use that local, **not**
`_activePreset.value`.

**Ordering rationale:** capture goes last because it is the only step whose failure is both
non-fatal *and* leaves a durable observable consequence (no snapshot ⇒ revert unavailable), and
because it must not delay `presets` being populated — every UI screen depends on that, nothing
depends on the snapshot until the user's first edit. Do not reorder it before `harvestPresetNames`.

### E.3 Call site 2 — `applyStateUpdate()`, every active-preset change

This is the single hook that covers **both** external footswitch changes *and* this controller's
own successful `selectPreset` — which is why the capture call sits **outside** the
`selfInitiatedPreset` if/else, not inside the `ExternalPresetChange` branch. Issue #14 says
"re-snapshot whenever the active preset changes, including changes made externally"; a
self-initiated change equally invalidates the previous preset's snapshot.

```kotlin
    private fun applyStateUpdate(state: PedalState) {
        val read = StateBlobReader.activePreset(state)
        if (read is TonexResult.Failure) return
        val idx = (read as TonexResult.Success).value
        val previous = _activePreset.value
        _activePreset.value = idx
        if (previous != null && previous != idx) {
            if (selfInitiatedPreset == idx) {
                selfInitiatedPreset = null
            } else {
                _events.tryEmit(TonexEvent.ExternalPresetChange(idx))
            }
            // ---- S9b ------------------------------------------------------------------------
            // Drop the outgoing preset's parameter values SYNCHRONOUSLY, before anything can
            // observe them against the new active preset (§E.4).
            dropPresetScopedValues()
            // Re-snapshot for BOTH self-initiated and external changes -- either way the previous
            // preset's snapshot is not valid for this one. Launched, never inline: this runs on
            // the reader coroutine, and captureSnapshotLocked suspends on a round trip. Blocking
            // the reader here would deadlock the very response it is waiting for.
            val s = session
            scope.launch {
                if (session !== s) return@launch // superseded by a teardown/reconnect
                operationMutex.withLock { captureSnapshotLocked(idx) }
            }
        }
    }
```

Four things the implementer must get right here, each of which is a real bug if missed:

1. **`scope.launch`, not a direct call.** `applyStateUpdate` is invoked from `handleMessage`,
   inside the reader coroutine. `captureSnapshotLocked` writes a request and awaits its response
   *through the reader*. Calling it inline would have the reader wait on itself — a guaranteed
   hang, and `applyStateUpdate` is not even a `suspend fun`, so it will not compile that way.
   This is the same "launch rather than call directly" pattern, for the same class of reason, as
   the existing `onTransportEnded`.
2. **No deadlock via `operationMutex`.** The launched coroutine may block on the mutex while
   `selectPreset` still holds it (the state-update push can arrive before `selectPreset` returns).
   That is fine: the *reader* never blocks — it has already returned from `applyStateUpdate` —
   and `selectPreset` releases the lock without ever waiting on the launched job.
3. **The `session !== s` identity guard**, mirroring `onTransportEnded`'s `endedReaderJob`
   check, established by PR #43 finding 4. Without it a queued capture can run against a fresh
   connection. `captureSnapshotLocked`'s own step-1/2 guards are a second line of defence;
   keep both.
4. **`previous != null` is retained.** The first-ever observation (`previous == null`) is
   `connect()`'s own initial read, already handled by §E.2. Do not remove the null check or the
   initial preset gets captured twice.

**Why `selectPreset` needs no capture call of its own.** After a successful `selectPreset`, the
pedal pushes a `StateUpdate` reflecting the new active preset; the reader routes it to
`applyStateUpdate`, `previous != idx` holds, and the capture fires from there. Adding a second
call inside `selectPreset` would capture *before* the pedal confirmed the change — reading a
preset that is not yet active — and would double-capture. **Do not add one.** Note also that
`selectPreset`'s own mandatory pre-patch re-read produces a `StateUpdate` carrying the *old*
active preset, for which `previous == idx`, so it correctly triggers nothing.

### E.4 Publishing captured values into `parameterValues`

`TonexController.parameterValues`' KDoc already promises "The last known value of every parameter
belonging to the active preset … Updated on preset load/change and after each successful
`setParameter`." Today only master volume and post-`setParameter` values ever land there; the
"preset load/change" half is unimplemented. S9b's capture is exactly that half, so implement it —
it is in scope, it is small, and without it the map lies.

Two private helpers next to `applyParameterChanged`:

```kotlin
/** Publishes a freshly captured preset's 109 values, replacing any prior PRESET-scoped entries
 *  and preserving GLOBAL-scoped ones (master volume is not part of any preset). */
private fun applyCapturedValues(values: FloatArray) {
    val captured = ParameterId.PRESET_RANGE.associate { ParameterId(it) to values[it] }
    _parameterValues.update { previous ->
        previous.filterKeys { it.index in ParameterId.GLOBAL_RANGE } + captured
    }
}

/** Drops every PRESET-scoped entry, keeping GLOBAL ones. Called the moment the active preset
 *  changes: the outgoing preset's values are not the incoming preset's values, and
 *  parameterValues' contract is that absence means "not yet known", never "zero". */
private fun dropPresetScopedValues() {
    _parameterValues.update { it.filterKeys { id -> id.index in ParameterId.GLOBAL_RANGE } }
}
```

`dropPresetScopedValues()` is called synchronously in `applyStateUpdate` (§E.3) so there is never
a window where a collector sees the old preset's values attributed to the new active preset. If
the subsequent capture then fails, the map is left with no preset entries — honest, and exactly
what `parameterValues`' "absence is not the same as a value of zero" contract is for.

`teardown()` already does `_parameterValues.value = emptyMap()`; leave it.

### E.5 Capture failure semantics — the load-bearing rule

**A failed capture records nothing.** There is no partial snapshot, no placeholder, no retry.
Every failure path in `captureSnapshotLocked` returns *before* `snapshotStore.record`, and
`PresetParameterExtractor` structurally cannot return a partial array (§A.1).

The user-visible consequence is that `revertActivePreset()` returns
`TonexError.NoSnapshotAvailable`. That is not a gap — `NoSnapshotAvailable`'s KDoc already
anticipates this exact case: *"even once S9b's capture path lands, a preset whose snapshot read
failed (or which became active before capture completed) legitimately has no snapshot, and revert
must still refuse loudly."* Both `connect()` and `applyStateUpdate` therefore discard the result.

Do **not** make capture failure fatal to `connect()`. A pedal that answers the name harvest but
not the parameter read still gives a fully usable app minus the revert affordance; failing the
whole connection would be a strictly worse outcome, and it would break the invariant
`harvestPresetNames`' KDoc states (`connect() Success ⟺ Ready ⟺ 20 presets`).

Do **not** add a retry loop. "Reject-and-explain beats patch-and-hope," and an automatic retry
here is precisely the "elaborate automatic-recovery safety net" CLAUDE.md warns against.

### E.6 Tighten the `PresetDetails` awaiters on `full`

`harvestPresetNames`' awaiter currently matches `is TonexMessage.PresetDetails` without inspecting
`full`. That is harmless today (nothing requests a FULL response), but with two distinct
preset-details reads in the codebase it becomes a latent cross-match. Add `&& !(…).full` to
**both** awaiters — `harvestPresetNames`' and `captureSnapshotLocked`'s — and note in the KDoc
that a FULL (`0x0303`) response is never requested by this module and would be ignored rather than
mis-consumed. One line, closes the hazard permanently.

---
_Generated by [Claude Code](https://claude.ai/code)_

> **S9b implementation plan — part 2 of 2** (Opus architecture pass). Part 1 (§0-§E) is the preceding comment.

## §F The write path — refactor, and the first-destructive-write call

### F.1 Extract `writeParameterLocked` (D6)

`revertActivePreset` runs inside `operationMutex.withLock`. `setParameter` *also* opens with
`operationMutex.withLock`. `kotlinx.coroutines.sync.Mutex` is **not reentrant**, so
`revertActivePreset` calling `setParameter` deadlocks permanently — the coroutine parks forever
holding the lock, and only `disconnect()` (which deliberately does not take the mutex first) could
ever unwedge the controller. This is not a style preference; reuse-as-is is impossible.

The right shape is one shared, mutex-free write path used by both:

```kotlin
/**
 * The single per-parameter write path, shared by [setParameter] and [revertActivePreset]'s
 * replay: registry lookup, range rejection, PRESET/master-volume/other-global routing, the
 * framed write, and the post-success [parameterValues] update.
 *
 * ## Caller contract
 * The caller MUST already hold [operationMutex] and MUST already have verified
 * [ConnectionState.Ready]. This function takes no lock and performs no lifecycle check — Mutex
 * is not reentrant, so a version that locked internally could not be called from
 * [revertActivePreset]'s replay loop at all (it would deadlock on the lock that loop already
 * holds).
 *
 * Deliberately does NOT emit [TonexEvent.FirstDestructiveWrite]: that signal belongs to a
 * user-initiated edit, not to a revert restoring values the user already had. See
 * [maybeSignalFirstDestructiveWrite].
 */
private suspend fun writeParameterLocked(id: ParameterId, value: Float): TonexResult<Unit>
```

Its body is the **current body of `setParameter` with the `withLock` wrapper and the Ready check
removed**, and `return@withLock` rewritten as `return`. Move it verbatim — the registry lookup,
the `ParameterValueOutOfRange` rejection, the three-way `when` routing (PRESET →
`ParameterWriteMessage.encode`, `MASTER_VOLUME` → `MasterVolumeMessage.encode`, other global →
`ProtocolStateViolation`), the `writeFramed`, and the `_parameterValues.update { it + (id to value) }`
that runs **after success only**. Do not change any of that logic; S9's tests pin it.

`setParameter` becomes:

```kotlin
override suspend fun setParameter(id: ParameterId, value: Float): TonexResult<Unit> =
    operationMutex.withLock {
        if (_connectionState.value !is ConnectionState.Ready) {
            return@withLock TonexResult.Failure(
                TonexError.ProtocolStateViolation(_connectionState.value, "setParameter requires Ready"),
            )
        }
        val result = writeParameterLocked(id, value)
        if (result is TonexResult.Success) maybeSignalFirstDestructiveWrite(id)
        result
    }
```

Every existing `DefaultTonexControllerSetParameterTest` assertion must still pass unchanged after
this refactor except the two `parameterValues` tests that §E.4 legitimately changes (§H.4). Treat
any other diff in that file as a refactor bug.

### F.2 `maybeSignalFirstDestructiveWrite`

```kotlin
/**
 * Fires [TonexEvent.FirstDestructiveWrite] at most once per session, after the session's first
 * successful parameter write that actually altered a snapshotted preset. See [TonexController]
 * and §F.3 of the S9b plan for why this is a post-hoc notice rather than a pre-write gate.
 *
 * Always called with [operationMutex] held, which is what makes the read-then-mark sequence
 * below safe without further synchronisation.
 */
private fun maybeSignalFirstDestructiveWrite(id: ParameterId) {
    if (snapshotStore.hasWarnedThisSession()) return
    val spec = ParameterRegistry.byIndex(id.index) ?: return
    if (spec.scope != ParameterScope.PRESET) return      // master volume is global; not part of a preset
    val active = _activePreset.value ?: return
    if (snapshotStore.snapshotFor(active) == null) return // "against a snapshotted preset" — SnapshotStore's own wording
    snapshotStore.markWarned()                            // mark BEFORE emit: "at most once" is the contract
    _events.tryEmit(TonexEvent.FirstDestructiveWrite(active))
}
```

Notes for the implementer:

- **The four conditions are all mandated by existing KDoc, not invented here.**
  `SnapshotStore.hasWarnedThisSession`'s KDoc defines the triggering fact as "a parameter write
  against a **snapshotted** preset," which pins both the snapshot-exists condition and (with
  `PresetSnapshot`'s "a snapshot covers exactly the 109 PRESET-scoped parameters") the
  PRESET-scope condition.
- **Master volume is excluded** and that is right: it is a global, it is not part of any preset,
  it is not captured by any snapshot, and revert does not restore it. Writing it destroys nothing
  a snapshot could have saved.
- **`markWarned()` precedes `tryEmit`** so the once-per-session guarantee is structural rather
  than dependent on the emit succeeding. `_events` has `extraBufferCapacity = 16` with
  `DROP_OLDEST`, so `tryEmit` effectively always succeeds; the ordering just makes the contract
  unconditional.
- **The check-then-act on `hasWarnedThisSession`/`markWarned` is safe** solely because every call
  site holds `operationMutex`. Say so in the KDoc so nobody later moves the call outside the lock.
- `snapshotStore.clear()` in `teardown()` already resets the flag, so a reconnect warns again —
  correct, "once per session."

### F.3 Decision: post-hoc notice, not a pre-write gate (D5)

`TonexEvent.FirstDestructiveWrite`'s KDoc says the write "is about to happen (or has just
happened — see `TonexController` for exactly when this fires)", and `TonexController` does not in
fact say. S9's plan explicitly left this open as "a call for the product owner at the time S9b is
designed." **Making the call now, under CLAUDE.md's standing merge authority for routine
engineering decisions: post-hoc notice, emitted only after the first destructive write has
actually succeeded.** Reasoning, in descending order of weight:

1. **A pre-write gate is not expressible with the API that exists, and building one is a
   different story.** `events` is documented as a `SharedFlow` of "one-off occurrences, not
   ongoing state," with `replay = 0` and `tryEmit`. A gate needs the protocol layer to *suspend
   until a human answers* — a suspending confirmation callback, or a `pendingConfirmation`
   `StateFlow` plus a resume call. Neither exists, and `TonexEvent` cannot express either. This
   is not a coin-flip between two available options; one option is a new public API.
2. **A gate would have to block inside `operationMutex`.** `setParameter` holds the mutex for its
   whole body. Suspending there awaiting a UI answer wedges `connect`, `selectPreset`,
   `setParameter` and `revertActivePreset` for the entire duration of a modal dialog, and a user
   who never answers wedges the controller until `disconnect()`. That is a serious regression
   against S9's hard-won "must not wedge" property (PR #43 review findings 1 and 2).
3. **Warning before the write would be dishonest when the write then fails.** Firing pre-write
   burns the once-per-session signal on an operation that may return
   `TransportFailure`/`Timeout` and alter nothing. The user is then warned about a change that
   never happened and gets **no** warning for the first change that actually does. Post-success
   firing means the event's claim is always true — the house "never report success on an
   operation that didn't fully succeed" rule, applied to notifications.
4. **The warning is not what protects the user; the snapshot is.** By the time this fires, the
   snapshot already exists (it is a precondition of firing), so the change the user is being told
   about is *recoverable via `revertActivePreset()`*. The message is "your edits are now changing
   the saved preset — here's the undo," not "are you sure?". A post-hoc notice carries that
   perfectly.

**Action for the implementer:** update `TonexEvent.FirstDestructiveWrite`'s KDoc to remove the
"(or has just happened…)" ambiguity, stating plainly that it fires **immediately after** the
session's first successful parameter write against a snapshotted preset, that it is a
notification and never a gate, and that a caller must not treat it as something to acknowledge
before the write proceeds — the write has already happened. Add a matching sentence to
`TonexController.setParameter`'s KDoc. **No signature changes.**

This is a notification-timing decision fully inside the "routine engineering decisions" grant, not
a product-facing tradeoff: the D3 UI layer is free to present it as a blocking modal, a toast, or
a persistent banner — this decision does not constrain that.

---

## §G `revertActivePreset` — the replay

### G.1 Guards 1-4 are unchanged (verified)

`revertActivePreset`'s existing four-guard chain — Ready → `supportsSingleParameterWrite` →
active-preset-known → snapshot-exists — stays **exactly as merged**. Its KDoc already states this
("steps 1-4 below are the permanent implementation and do not change when that lands"), and I
independently confirm it is correct and complete for S9b:

- Guard 2 discharges issue #14's firmware-fallback prohibition in full. It runs **before** any
  write, returns `TonexError.UnsupportedByFirmware("revert-active-preset")`, and there is no
  fallback branch anywhere. `writeParameterLocked` → `ParameterWriteMessage.encode(id, value, capabilities)`
  independently re-checks the same capability and returns
  `UnsupportedByFirmware("single-parameter-write")`, so the prohibition is enforced twice, at two
  layers, on two distinct operation strings. **Change nothing here.**
- The only edit to guards 1-4 is that guard 4's result must now be **bound**:
  `val snapshot = snapshotStore.snapshotFor(active) ?: return@withLock TonexResult.Failure(TonexError.NoSnapshotAvailable(active))`.
  It is currently discarded.
- Delete the `// 5. ── S9b INSERTS THE REPLAY HERE ──` comment block and the trailing
  `TonexResult.Failure(TonexError.NoSnapshotAvailable(active)) // unreachable` line, replacing
  them with §G.2 + §G.3. Rewrite the function KDoc: guard 4 is now genuinely reachable-to-Success,
  so the "structurally unreachable in S9" paragraph is obsolete and must go.

### G.2 Step 5a — pre-validate all 109 before writing any (D9)

```kotlin
        // 5a. Pre-validate the WHOLE snapshot before issuing a single write. A value that the
        //     per-write range check would reject must not be discovered at parameter 47, with
        //     46 writes already on the wire and the preset left half-reverted.
        for (i in ParameterId.PRESET_RANGE) {
            val id = ParameterId(i)
            val spec = ParameterRegistry.byIndex(i) ?: return@withLock TonexResult.Failure(
                TonexError.ProtocolStateViolation(
                    _connectionState.value,
                    "parameter index $i is not in the registry (internal invariant violated)",
                ),
            )
            val v = snapshot.valueOf(id)
            if (v < spec.min || v > spec.max) {
                return@withLock TonexResult.Failure(
                    TonexError.ParameterValueOutOfRange(id, v, spec.min, spec.max),
                )
            }
        }
```

**Why this exists.** The snapshot holds values the *pedal* reported. `writeParameterLocked`
rejects out-of-range values (it must — `setParameter`'s contract is "rejected rather than silently
clamped"). If any captured value falls outside the registry's bounds, the replay would abort
mid-flight. `ParameterRegistry`'s own KDoc flags two entries as possibly wrong and pending
hardware verification — **VIR M2X (index 31), max recorded as 2 while its counterpart VIR M1X has
max 10** — so this is a live, documented possibility, not a hypothetical.

**Why refuse rather than clamp.** Clamping would write a value the user never had, silently, as
part of an operation whose entire purpose is faithful restoration — data loss dressed as a safety
net. Note that clamping is not even reachable as a design option: `ParameterWriteMessage.encode`
clamps internally, so "write the captured value verbatim" is impossible through the existing
encoder regardless. Refusing up front, with zero writes, is the only outcome that is both honest
and atomic.

Reuse the existing `ParameterValueOutOfRange` case rather than adding a new one — its
`id`/`value`/`min`/`max` fields already say everything, and the taxonomy does not need a third
revert-flavoured error. Do add a sentence to `revertActivePreset`'s KDoc explaining that this
error from *revert* (as opposed to from `setParameter`) most likely indicates the registry's
bounds are wrong for this pedal's firmware rather than a bad caller, and pointing at #25.

### G.3 Step 5b — replay all 109, ascending wire index (D7)

```kotlin
        // 5b. Replay. Per-parameter writes only, NEVER a whole-state write (issue #14). Ascending
        //     wire index, so RevertIncomplete's appliedCount identifies exactly which parameters
        //     landed.
        var applied = 0
        for (i in ParameterId.PRESET_RANGE) {
            val id = ParameterId(i)
            when (val r = writeParameterLocked(id, snapshot.valueOf(id))) {
                is TonexResult.Success -> applied++
                is TonexResult.Failure -> return@withLock TonexResult.Failure(
                    TonexError.RevertIncomplete(
                        presetIndex = active,
                        appliedCount = applied,
                        totalCount = PresetSnapshot.PARAMETER_COUNT,
                        failedParameter = id,
                        cause = r.error,
                    ),
                )
            }
        }
        TonexResult.Success(Unit)
```

**Why all 109 rather than only the parameters that differ from `parameterValues`.** A diff-based
replay is tempting (109 writes → typically 2) and issue #14's wording, "restores every *changed*
parameter," permits it. Reject it anyway:

- `parameterValues` is a **local mirror**, and after S9b it is seeded from the last capture. It
  can drift from the pedal without our knowing — the reader deliberately applies only
  `index == 0` of an inbound `ParameterChanged` (`applyParameterChanged`'s restraint, pinned by
  its KDoc and by #25), so a nonzero-index change made by another controller or the desktop editor
  updates the pedal and not our map. A diff computed against a drifted mirror **silently skips a
  parameter that genuinely needed restoring** and reports `Success`. That is a false success in
  the one operation whose entire job is to be trustworthy — "reject-and-explain beats
  patch-and-hope," and here the alternative to explaining is not even rejecting, it is hoping.
- The cost is negligible and the story does not justify optimising it. Each write is ~25 framed
  bytes with **no awaited round trip** (`writeFramed` returns as soon as the transport accepts the
  bytes), so a full replay is ~2.7 KB. NFR2's sub-200 ms target is about continuous slider drags,
  not a deliberate one-shot revert, and CLAUDE.md explicitly says not to over-build for latency.
- Rewriting a parameter to the value it already holds is a no-op on an auto-saving pedal.
- Writing all 109 unconditionally makes `appliedCount` mean something exact and makes the
  operation idempotent (§G.5).

Ascending `ParameterId.PRESET_RANGE` order is **load-bearing**, not cosmetic: it is what makes
`RevertIncomplete.appliedCount` decodable as "`ParameterId(0)` … `ParameterId(appliedCount-1)`
were accepted." Do not reorder, parallelise, or pipeline it.

### G.4 Partial-failure story (D8)

**Abort at the first failure.** Do not continue through the remaining parameters, and do not
aggregate a list of failures. A `writeFramed` failure means the transport threw, short-wrote, or
timed out; the next 62 writes are overwhelmingly likely to fail identically, turning one clear
error into a 62-deep pile-up and a long stall while `transportWriteMillis` elapses for each.

What the caller gets:

- `TonexError.RevertIncomplete` naming the preset, `appliedCount` of `totalCount`, the exact
  `failedParameter`, and the underlying `cause` (§D).
- `parameterValues` reflecting reality as closely as this module can know it:
  `writeParameterLocked` updates the map **only after each individual write succeeds**, so after a
  partial revert the map holds snapshot values for the parameters that landed and pre-revert
  values for those that did not. This is what gives the UI a truthful picture of the mixed state,
  and it falls out of the existing code for free — do not batch the map update to the end of the
  loop.
- The **snapshot is retained**. Do not clear it on partial failure.

### G.5 Retry is safe, by construction

Because the replay writes all 109 unconditionally from an immutable snapshot, calling
`revertActivePreset()` again after a `RevertIncomplete` simply re-issues everything from scratch;
the parameters that already landed are rewritten to the values they already hold. Nothing
accumulates, nothing is skipped. State this in the KDoc. Do **not** implement an automatic retry
(CLAUDE.md: no elaborate automatic-recovery nets) — offering the user a retry is D3's call.

### G.6 The whole-state write remains structurally impossible

Confirm and note in the KDoc: `revertActivePreset` reaches the wire only through
`writeParameterLocked` → `ParameterWriteMessage.encode` / `MasterVolumeMessage.encode` →
`writeFramed`. `SetStateMessage.encode` — the only whole-state write in the module — is called at
exactly one site, inside `selectPreset`, and nothing in S9b adds another. Issue #14's "issues no
whole-state write" criterion is satisfied structurally, and §H.5 pins it with a test rather than
leaving it to inspection.

---

## §H Test plan

Conventions to follow, all already established by S9's suite: `runTest` with `backgroundScope`
handed to the controller, `FakeTonexTransport`, fixtures from `ConnectionTestFixtures.kt`,
`testScheduler.runCurrent()` (**never** `advanceUntilIdle()`, which fast-forwards past in-flight
timeouts), `kotlin.test` assertions, and Turbine where an event/flow sequence is under test.

### H.1 `PresetParameterExtractorTest.kt` (new, `…/protocol/message/`)

Pure, no coroutines. Per CLAUDE.md, prefer literal assertions over round-trip self-consistency.

1. `marker() returns the exact upstream literal BA 03 BA 6D` — assert against a hand-written
   `byteArrayOf(0xBA.toByte(), 0x03, 0xBA.toByte(), 0x6D)`, i.e. the literal transcribed from
   `usb_tonex_one.c:957`, **not** from the production constant.
2. `marker() returns a fresh copy each call` — mutate the result, call again, assert unaffected
   (mirrors `PresetNameExtractorTest`).
3. `extracts 109 floats from a hand-built literal block` — build the payload by hand
   (`marker + (0x88 + 4 LE bytes) * 109` via `ByteBuffer.order(LITTLE_ENDIAN).putFloat`, **not**
   via `TonexVarint.encodeFloat`, so the test pins the wire literal independently of our own
   encoder), with 109 distinct values; assert `size == 109` and every element.
4. `finds the block at a non-zero offset` — prepend arbitrary junk that does not contain the
   marker; assert identical results. Pins the "search, don't assume an offset" contract.
5. `values containing 0x7E and 0x7D bytes decode correctly` — choose floats whose LE encodings
   contain HDLC delimiter/escape bytes. This is the §0.2 divergence, made executable.
6. `a missing marker fails with MalformedFrame` and nothing is returned.
7. `a truncated block fails with UnexpectedBlobShape` — marker present, only 108 parameters'
   worth of bytes after it. Assert `expectedSize == 545`.
8. `a non-0x88 marker at parameter 60 fails the whole extraction` — assert
   `MalformedFrame`, and assert the message names index 60. **This is the upstream bug we
   deliberately do not reproduce** (§0.3); reference that in the test name or a comment.
9. `an int-marker (0x80) at a parameter position fails rather than desyncing` — pins the strict
   `bytesConsumed == 5` check.
10. `a full real-shaped payload carrying both the name marker and the parameter block yields both`
    — run `PresetNameExtractor.extract` and `PresetParameterExtractor.extract` over one payload,
    asserting both succeed. This is the actual response shape (§0), so pin it.

### H.2 `PresetSnapshotTest.kt` (new, `…/protocol/`)

The issue calls float-array aliasing out by name as "an easy aliasing bug"; these are the
acceptance-criterion tests, so make them explicit and unmissable.

1. **`mutating the source array after construction does not change the snapshot`** — build a
   `FloatArray(109)`, construct, then write to the source array, then assert `valueOf` still
   returns the original. Pins the constructor's `copyOf()`.
2. **`mutating a map returned by toMap does not change the snapshot`** — `toMap().toMutableMap()`,
   mutate, re-read via `valueOf` and a fresh `toMap()`.
3. **`two toMap calls return independent instances`** — `assertNotSame`, plus equal contents.
4. `valueOf returns the value at the id's own wire index for all 109` — construct with
   `FloatArray(109) { it.toFloat() }` and assert `valueOf(ParameterId(n)) == n.toFloat()` for
   every `n`. Pins the positional indexing convention (§B).
5. `toMap has exactly 109 entries whose keys are exactly PRESET_RANGE` — asserted count, not
   sampled (issue #14's first acceptance criterion).
6. `valueOf on a GLOBAL id throws IllegalArgumentException` — e.g. `ParameterId(116)`
   (MASTER_VOLUME).
7. `constructing with 108 or 110 values throws` — pins the existing `init` require.
8. `presetIndex and sessionId are carried through`.

### H.3 `ConnectionTimeoutsTest.kt` (modify)

Add `presetParametersMillis` to all four existing tests: the positivity check, the
documented-value check (`assertEquals(3_000L, d.presetParametersMillis)`), and the two explicit
six-field → seven-field constructions. Add a test mirroring the existing
`getStateMillis`/`stateReadMillis` one: `presetDetailsMillis and presetParametersMillis are
numerically equal but independently settable`.

### H.4 `ConnectionTestFixtures.kt` (modify) — **read this before touching any controller test**

Adding a capture read to `connect()` changes what every post-`Ready` test must feed the fake.
Get the fixtures right first; otherwise ~40 tests fail in confusing ways.

New builders:

```kotlin
/** The parameter block: `BA 03 BA 6D` followed by 109 × (0x88 + LE float32). */
fun presetParameterBlock(values: FloatArray): ByteArray

/** A PresetDetailsSummary response carrying BOTH the name field and the parameter block —
 *  the real response shape (§0): name marker + 32-byte field, then the parameter block. */
fun presetDetailsSummaryWithParameters(name: String, values: FloatArray): ByteArray

/** 109 distinct, in-range values: for each index i, spec.min + (spec.max - spec.min) * (i % 9) / 9f
 *  clamped into range. Distinct per index so a cross-index copy or off-by-one shows up as a
 *  mismatch rather than hiding behind coincidentally-equal values — the same reasoning as
 *  plausibleBlob()'s non-zero filler. */
fun snapshotValues(): FloatArray
```

`driveToReady` gains a trailing parameter and two extra steps:

```kotlin
suspend fun TestScope.driveToReady(
    fake: FakeTonexTransport,
    activeSlot: PresetSlot = PresetSlot.A,
    a: Int = 0, b: Int = 1, c: Int = 2,
    captureValues: FloatArray? = snapshotValues(),   // null ⇒ never answer the capture read
) {
    // … unchanged: hello, state update, 20 preset-name responses …

    // S9b: harvestMasterVolume() runs before the capture read whenever the capability is
    // confirmed. Answer it so the sequence proceeds on runCurrent() alone, without having to
    // advance virtual time past its 2 s timeout. With NONE_CONFIRMED the harvest returns
    // without awaiting and this frame is simply dropped by the reader (tryEmit with no
    // subscriber) — harmless either way.
    testScheduler.runCurrent()
    fake.emitMessage(masterVolumeChanged(MasterVolumeMessage.decibelsToNative(0f)))

    // S9b: the snapshot-capture read.
    if (captureValues != null) {
        testScheduler.runCurrent()
        fake.emitMessage(presetDetailsSummaryWithParameters("Capture", captureValues))
    }
    testScheduler.runCurrent()
}
```

> ⚠️ **Why the master-volume response is now emitted.** Before S9b, `harvestMasterVolume()` was
> the last step, so capability-confirmed tests simply let it time out during
> `connectDeferred.await()` (which advances virtual time). With capture appended *after* it,
> `driveToReady` would have to advance past that 2 s timeout before it could emit the capture
> response, breaking the file's deliberate `runCurrent()`-only discipline. Answering the harvest
> is simpler, faster, and closer to what a real pedal does.
>
> **This changes one existing behaviour:** `parameterValues` now contains MASTER_VOLUME after
> `driveToReady` in capability-confirmed tests. Check the affected assertions.

`DefaultTonexControllerDisconnectTest.kt` has its **own private `driveToReady`** (around line 231)
that shadows the shared one. Update it identically or delete it in favour of the shared fixture.
Do not miss it.

### H.5 `DefaultTonexControllerSnapshotTest.kt` (new) — capture

1. `connect captures a snapshot of the initial active preset` — after `driveToReady`, assert
   `revertActivePreset()` no longer returns `NoSnapshotAvailable`, and assert
   `parameterValues` holds all 109 captured values.
2. `the capture request is a SUMMARY preset-details request for the active preset index` —
   decode the last written message; assert the `0x0300` envelope and a payload whose final two
   bytes are `activeIndex` and `0x00`. **Assert `0x00`, not `0x01`** — this is the §0 correction,
   pinned as a literal so it cannot silently regress to FULL.
3. `capture timeout leaves no snapshot and connect still succeeds` — `driveToReady(captureValues = null)`;
   assert `connect` returned `Success`, `connectionState` is `Ready`, `presets.size == 20`, and
   `revertActivePreset()` returns `NoSnapshotAvailable`. (Directly pins D3/§E.5.)
4. `a capture response with a corrupt parameter block leaves no snapshot` — emit a summary whose
   parameter block has a bad marker byte; same assertions as (3), plus: `parameterValues` holds
   no PRESET-scoped entries.
5. `an external preset change re-snapshots` — after Ready, push a `stateUpdateMessage` whose blob
   makes a different preset active; assert (Turbine) that `ExternalPresetChange` is emitted, that
   a second capture request is written, and that after answering it the new preset has a snapshot
   while `parameterValues` holds the *new* values.
6. `a self-initiated selectPreset also re-snapshots` — drive `selectPreset`, push the confirming
   `StateUpdate`, assert a capture request is written and **no** `ExternalPresetChange` is
   emitted. Pins the "capture sits outside the self/external if-else" decision (§E.3).
7. `preset-scoped values are dropped the instant the active preset changes` — after the state
   update but *before* answering the capture read, assert `parameterValues` contains no
   PRESET-scoped keys and still contains MASTER_VOLUME (§E.4).
8. `a capture whose preset stops being active before the response arrives is discarded` — trigger
   a change to preset 3, then push another state update making preset 7 active, then answer the
   first capture; assert no snapshot was recorded for preset 3 (§E.1 step 5).
9. `disconnect clears snapshots` — connect, capture, `disconnect()`, reconnect with
   `captureValues = null`, assert `NoSnapshotAvailable`. Pins `teardown()`'s existing
   `snapshotStore.clear()` against the new capture path.
10. `the snapshot survives a setParameter that changes a captured value` — write a parameter, then
    assert the snapshot still holds the *original* value (via a successful revert restoring it).
    The aliasing criterion, exercised end-to-end through the controller rather than only on
    `PresetSnapshot` in isolation.

### H.6 `DefaultTonexControllerRevertTest.kt` (new) — replay

1. `revert issues exactly 109 single-parameter writes, all KIND_PARAMETER, in ascending index
   order` — decode every write after the baseline; assert count `== 109`, assert each decoded
   `SingleParameterPayload.kind == KIND_PARAMETER`, and assert `index` is `0..108` in order.
   Asserted count, not sampled.
2. `revert issues no whole-state write` — assert no written message has
   `MessageType.StateUpdate` (`0x0306`). Issue #14's explicit criterion, and §G.6's structural
   claim made executable.
3. `revert restores the exact captured values` — after `setParameter`-ing several parameters to
   new values, revert, then assert each write's decoded float equals the captured value and that
   `parameterValues` matches `snapshot.toMap()`.
4. `revert returns Success when every write succeeds`.
5. `revert of an unmodified preset still writes all 109` — pins D7 (no diff-based skipping), and
   documents in the test name that this is deliberate.
6. `a write failure at parameter 47 aborts with RevertIncomplete carrying appliedCount 47` — use
   `FakeTonexTransport.writeBehavior` to short-write (or `writeThrows`) on the 47th
   post-baseline write; assert the error type and every field, and assert **no further writes
   were issued** after the failing one.
7. `after a partial revert, parameterValues holds restored values for the applied prefix only` —
   the "clear picture of what did and didn't apply" requirement (§G.4).
8. `the snapshot is retained after a partial revert, and a retry succeeds` — clear the write
   failure, revert again, assert `Success` and 109 fresh writes (§G.5).
9. `a snapshot value outside its registry range fails before any write is issued` — construct the
   controller with an injected `SnapshotStore` pre-loaded with an out-of-range snapshot; assert
   `ParameterValueOutOfRange` naming the offending id, and assert the write count is **unchanged**
   (§G.2). *(The `snapshotStore` constructor parameter already exists and is injectable — this is
   what it is for.)*
10. `revert does not emit FirstDestructiveWrite` — Turbine on `events` across a full revert;
    assert no such event even when 109 writes land (§F.1).
11. `revert with NONE_CONFIRMED still fails UnsupportedByFirmware even with a snapshot present` —
    inject a pre-loaded store, assert guard 2 wins over guard 4 and **zero writes** are issued.
    The firmware-fallback prohibition, tested with the snapshot guard no longer masking it — this
    was structurally untestable in S9.

### H.7 `DefaultTonexControllerFirstDestructiveWriteTest.kt` (new)

1. `the first setParameter on a snapshotted preset emits FirstDestructiveWrite exactly once` —
   Turbine; write three different parameters, assert exactly one event carrying the active
   `PresetIndex`.
2. `a failed first write emits nothing, and the next successful write emits` — `writeThrows`,
   attempt, assert no event; clear, write, assert exactly one. Pins §F.3 reason 3.
3. `master volume does not emit` — even as the session's very first write.
4. `no snapshot means no event` — `driveToReady(captureValues = null)`, then `setParameter`;
   assert success and no event (`SnapshotStore`'s "against a snapshotted preset" wording).
5. `a preset change does not re-arm the warning within one session` — capture, write (event),
   change preset, re-capture, write again; assert **no** second event.
6. `reconnecting re-arms it` — disconnect, reconnect, capture, write; assert one event in the new
   session. Pins `clear()`'s reset of the warned flag.

### H.8 Existing tests that **will** break — fix these, do not paper over them

Adding one request to `connect()` shifts every exact count. Verified list:

| File:line | Current | Becomes | Why |
|---|---|---|---|
| `DefaultTonexControllerHandshakeTest.kt:107` | `assertEquals(22, written.size)` | `23` | + the capture request |
| `DefaultTonexControllerHarvestTest.kt:43` | `assertEquals(20, presetDetailsRequests.size)` | `21` | the capture request is a `0x0300` preset-details request too (§0) — filter on the detail-level byte, or just expect 21 and assert the 21st is the capture |
| `DefaultTonexControllerHarvestTest.kt:110` | `assertEquals(22, …)` (`NONE_CONFIRMED`) | `23` | + capture (capture is **not** capability-gated, D4) |
| `DefaultTonexControllerHarvestTest.kt:130` | `assertEquals(23, …)` | `24` | + capture |
| `DefaultTonexControllerHarvestTest.kt:137` | master-volume-timeout test | review | `driveToReady` now answers the MV harvest (§H.4); this test must build its own drive sequence that deliberately does not |
| `DefaultTonexControllerSetParameterTest.kt:201-213` | `assertTrue(!parameterValues.containsKey(presetParam.id))` before the write | rewrite | capture now pre-populates all 109; assert the value is the *captured* one before and `-25f` after |
| `DefaultTonexControllerSetParameterTest.kt:215-229` | `parameterValues is unchanged when the write fails` | rewrite | assert it still equals the captured value, not that the key is absent |
| `DefaultTonexControllerSetParameterTest.kt:248-264` | `revertActivePreset … fails NoSnapshotAvailable — unreachable success in S9` | rewrite | a snapshot now exists. Keep a `NoSnapshotAvailable` test using `driveToReady(captureValues = null)`, and move the success path to §H.6. Update the `"revertActivePreset must never write in S9"` message. |

Before finishing, `grep -rn "assertEquals(2[0-9]\|writtenMessages()\.size\|containsKey" protocol/src/test/kotlin/dev/tonexotg/protocol/connection/` and re-check every hit — the table above is from a careful pass, but a shifted count is exactly the kind of thing that hides.

---

## §I Hardware questions to file on #25 / S20

CLAUDE.md: findings that cannot be resolved without hardware must be filed as a precise resolving
observation on the hardware-probe story, not silently pinned. Post these as **one comment on
issue #25**, referencing #14. Each must state the exact observation that resolves it.

1. **Does a SUMMARY preset-details response for a *non-active* preset contain the `BA 03 BA 6D`
   parameter block, and if so whose values?** Upstream only ever parses the block from a request
   for `current_preset` (`usb_tonex_one.c:1222`). *Observation that resolves it:* with preset 5
   active, send `request_preset_details(9, 0)` and dump the response; report whether the marker is
   present, and if so whether the 109 floats match preset 9's stored values or preset 5's live
   ones. If non-active presets do return their own values, §E.1's step-5 discard could be relaxed
   and snapshots could be pre-captured for all 20 presets.
2. **Can the pedal report a preset-parameter value outside `ParameterRegistry`'s `min..max`?**
   Specifically VIR M2X (index 31, max recorded as 2 while VIR M1X's is 10) and CABINET_TYPE
   (index 24), both already flagged in the registry. *Observation:* capture a snapshot on real
   hardware and report any index whose value falls outside its registered bounds. If any does,
   §G.2 will refuse revert entirely for that preset and the registry entry must be corrected.
3. **Are the 109 floats in the block really in `ParameterId` wire order?** Upstream's
   `param_ptr[loop]` implies yes, but it is inferred from a table walk, not observed.
   *Observation:* set one distinctive value via `setParameter` (e.g. NOISE_GATE_THRESHOLD = -37 dB,
   index 2), re-read the summary, and confirm the third float in the block is `-37.0`.
4. **Does the pedal emit a `ParameterChanged` push for nonzero indices when another controller
   edits a parameter?** Bears directly on §G.3's rejection of a diff-based replay. *Observation:*
   with the app connected, change a parameter from the desktop editor and report whether a
   `0x0309` frame arrives and what its index field holds. Ties into
   `applyParameterChanged`'s existing restraint, already tracked on #25.
5. **Does writing a parameter to the value it already holds cause any observable pedal-side
   effect?** §G.3 assumes it is a no-op. *Observation:* replay an unmodified preset's 109 values
   and report any audible glitch, LED change, or wear concern.

Additionally, **correct the record on #14 itself**: leave a comment noting that `request_preset_details(idx, 0)`
(SUMMARY) is confirmed correct against upstream and that `PresetDetailsKind.FULL` is never parsed
by any known implementation — so nobody re-derives the FULL assumption later.

---

## §J Build and commit order

CLAUDE.md's session-limit rule: **commit after each step and push immediately.** Each step below
compiles and its own tests pass on their own, so a session terminated mid-way leaves a coherent
branch. Branch name: `s9b-preset-snapshot-revert`. Do not commit to `main`.

1. `PresetParameterExtractor` + `PresetParameterExtractorTest` (§A, §H.1). Pure, no controller
   changes. **Commit, push.**
2. `PresetSnapshot.valueOf`/`toMap` + `PresetSnapshotTest` (§B, §H.2). **Commit, push.**
3. `ConnectionTimeouts.presetParametersMillis` + `TonexError.RevertIncomplete` + their test
   updates (§C, §D, §H.3). Small and mechanical. **Commit, push.**
4. `writeParameterLocked` extraction + `maybeSignalFirstDestructiveWrite` + the KDoc resolution on
   `TonexEvent`/`TonexController` (§F). **No behaviour change is expected** beyond the new event;
   the existing `SetParameterTest` should still be green here except the two `parameterValues`
   tests, which step 5 touches. **Commit, push.**
5. Capture: `captureSnapshotLocked`, both call sites, `applyCapturedValues`/`dropPresetScopedValues`,
   the `full` tightening (§E) — plus the `ConnectionTestFixtures` update (§H.4) and the §H.8
   existing-test fixes **in the same commit**, since the suite is red in between. **Commit, push.**
6. `revertActivePreset` replay (§G) + `DefaultTonexControllerRevertTest` (§H.6). **Commit, push.**
7. `DefaultTonexControllerSnapshotTest` (§H.5) + `DefaultTonexControllerFirstDestructiveWriteTest`
   (§H.7). **Commit, push.**
8. File the #25 comment and the #14 correction comment (§I). **Commit, push.**
9. `git rm protocol/S9B_ARCHITECTURE_PLAN.md`, run the **full** `:protocol` suite plus a
   no-Android-SDK build (`./gradlew :protocol:test` — `:protocol` must keep building with no
   Android SDK present, per #15). **Commit, push.**

Then: request an **adversarial Opus review** before merge. This is unambiguously high-stakes code
by CLAUDE.md's definition — it writes to the pedal (109 writes per revert), it parses the pedal's
responses, and a wrong snapshot is echoed straight back as corrupted state. Brief the reviewer to
assume the implementation is wrong until proven otherwise, and specifically to re-verify §0's
upstream claims against the real upstream file rather than trusting this plan.

---

## §K Explicit non-goals — do not build these

- **No per-write read-back verification.** Issue #14 rejects it by name; FR11 is satisfied by
  typed errors on write failure.
- **No whole-state fallback in revert, under any condition.** §G.1/§G.6.
- **No automatic re-capture retry, no automatic revert retry.** §E.5, §G.5.
- **No persistence of snapshots across sessions.** `SnapshotStore`'s KDoc: "there is deliberately
  no persistence API here."
- **No pre-capture of all 20 presets' snapshots.** Unverified whether a non-active preset's
  summary even carries the block (§I.1), and it would add 20 round trips to `connect()`.
- **No new `SnapshotStore` methods**, and no second `SnapshotStore` implementation.
- **No use of `PresetDetailsKind.FULL`**, and do not delete it either (§0).
- **No `PedalState` field on the controller**, for any reason. The class KDoc forbids it in
  capitals; §E.0 confirms S9b gives no reason to want one.
- **No blocking confirmation gate on `events`.** §F.3.

---
_Generated by [Claude Code](https://claude.ai/code)_

