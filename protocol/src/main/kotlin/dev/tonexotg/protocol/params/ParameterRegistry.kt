/*
 * Ported/adapted from: TonexOneController
 * Upstream repository: https://github.com/Builty/TonexOneController
 * Upstream file:        source/main/tonex_params.c
 * Upstream licence:     Apache-2.0 — Copyright 2025 Greg Smith
 * Full licence text:    LICENSE
 *
 * This file is a Kotlin port/adaptation of logic originally written for the
 * upstream project named above. See /CREDITS.md for full attribution.
 */

package dev.tonexotg.protocol.params

import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.ParameterScope
import dev.tonexotg.protocol.ParameterSpec
import dev.tonexotg.protocol.ParameterType

/**
 * Complete registry of all 116 Tonex One parameters (109 per-preset + 7 global).
 *
 * Ported from the upstream `TonexParameters[]` table in `tonex_params.c`
 * (https://github.com/Builty/TonexOneController), pinned to commit
 * `7079f157107a7bc91f171e51e3da0d799d31fcfb`.
 *
 * The upstream comment is explicit that parameter values in the table are
 * "only a default, overridden by the preset on load" — [ParameterSpec.default]
 * is a seed value, not authoritative pedal state. Use [ParameterSpec.min] and
 * [ParameterSpec.max] as the load-bearing bounds for clamping and scaling.
 *
 * All values are transmitted as float32 on the wire, including switches and
 * enum selectors. For enum (SELECT) parameters, the numeric value is the
 * index into the option list — the [ParameterSpec.max] value equals the index
 * of the last option, so option count = max + 1.
 *
 * Index 109 is the upstream `"LAST"` sentinel and is deliberately omitted here;
 * [ParameterId] will reject it if constructed. Per-preset parameters occupy
 * indices 0..108; global parameters occupy 110..116. See [ParameterId] for the
 * positional semantics.
 *
 * ## Parameter identification
 *
 * Each parameter carries two name strings:
 * - **[ParameterSpec.enumName]**: the unique stable identifier from the upstream
 *   enum (e.g., `NOISE_GATE_THRESHOLD`, `MASTER_VOLUME`), without the
 *   `TONEX_PARAM_` / `TONEX_GLOBAL_` prefix. Use this for lookups, logging, and
 *   persisted keys.
 * - **[ParameterSpec.name]**: the pedal's abbreviated display string for its
 *   small hardware screen (e.g., `"NG THRESH"`, `"MVOL"`), exactly as the pedal
 *   uses it. This string is **not unique** — indices 88 and 90 both carry
 *   `"MOD RO S"` (for SYNC and SPEED). Use `name` only for display; use
 *   `enumName` for identification.
 *
 * ## Two apparent upstream inconsistencies — resolved by reading upstream, not hardware (S20/#25)
 *
 * Both were originally flagged as needing real-hardware verification. Neither actually did —
 * both resolve from `Builty/TonexOneController` source alone:
 *
 * 1. **CABINET_TYPE (index 24)**: `tonex_params.h:92`'s comment on the *parameter-ID* enum entry
 *    ("0 = disabled. 1 = VIR. 2 = Tone Model") does contradict the *value* enum,
 *    `TonexCabinetTypes` (`tonex_params.h:51-55`: `TONEX_CABINET_TONE_MODEL, TONEX_CABINET_VIR,
 *    TONEX_CABINET_DISABLED` — 0/1/2 by plain sequencing) — a real discrepancy, not imagined. But
 *    `display_tonex.c:230-241`'s UI toggle logic (tested against physical hardware) uses that
 *    enum's named constants directly, so whatever the enum says is what actually executes; the
 *    stray comment is simply wrong. **0=Tone Model, 1=VIR, 2=Disabled is correct** — the ordering
 *    already encoded below.
 *
 * 2. **VIR M2X (index 31)**: max recorded as 2, while its counterpart VIR M1X (index 28) has max
 *    10. `tonex_params.c:86` writes `{0, 0, 2, ...}` explicitly and deliberately — not omitted,
 *    not auto-derived from M1X — and nothing else in that repository suggests it should be 10.
 *    vit3k/tonex_controller (the project's other upstream source) has no per-parameter table to
 *    cross-check against at all. The "likely a typo" theory had no upstream corroboration; treat
 *    the asymmetry with M1X as legitimate (a real difference in mic 2's usable X-axis range) and
 *    the value below as correct.
 */
object ParameterRegistry {

    private val ALL_PARAMETERS: List<ParameterSpec> = listOf(
        // Noise Gate (0–4)
        ParameterSpec(ParameterId(0), ParameterScope.PRESET, "NG POST", "NOISE_GATE_POST", ParameterType.SWITCH, 0f, 1f, 0f, ""),
        ParameterSpec(ParameterId(1), ParameterScope.PRESET, "NG POWER", "NOISE_GATE_ENABLE", ParameterType.SWITCH, 0f, 1f, 1f, ""),
        ParameterSpec(ParameterId(2), ParameterScope.PRESET, "NG THRESH", "NOISE_GATE_THRESHOLD", ParameterType.RANGE, -100f, 0f, -64f, "dB"),
        ParameterSpec(ParameterId(3), ParameterScope.PRESET, "NG REL", "NOISE_GATE_RELEASE", ParameterType.RANGE, 5f, 500f, 20f, "ms"),
        ParameterSpec(ParameterId(4), ParameterScope.PRESET, "NG DEPTH", "NOISE_GATE_DEPTH", ParameterType.RANGE, -100f, -20f, -60f, "dB"),

        // Compressor (5–9)
        ParameterSpec(ParameterId(5), ParameterScope.PRESET, "COMP POST", "COMP_POST", ParameterType.SWITCH, 0f, 1f, 1f, ""),
        ParameterSpec(ParameterId(6), ParameterScope.PRESET, "COMP POWER", "COMP_ENABLE", ParameterType.SWITCH, 0f, 1f, 0f, ""),
        ParameterSpec(ParameterId(7), ParameterScope.PRESET, "COMP THRESH", "COMP_THRESHOLD", ParameterType.RANGE, -40f, 0f, -14f, "dB"),
        ParameterSpec(ParameterId(8), ParameterScope.PRESET, "COMP GAIN", "COMP_MAKE_UP", ParameterType.RANGE, -30f, 10f, -12f, "dB"),
        ParameterSpec(ParameterId(9), ParameterScope.PRESET, "COMP ATTACK", "COMP_ATTACK", ParameterType.RANGE, 1f, 51f, 14f, "ms"),

        // Parametric EQ (10–17)
        ParameterSpec(ParameterId(10), ParameterScope.PRESET, "EQ POST", "EQ_POST", ParameterType.SWITCH, 0f, 1f, 0f, ""),
        ParameterSpec(ParameterId(11), ParameterScope.PRESET, "EQ BASS", "EQ_BASS", ParameterType.RANGE, 0f, 10f, 5f, ""),
        ParameterSpec(ParameterId(12), ParameterScope.PRESET, "EQ BFREQ", "EQ_BASS_FREQ", ParameterType.RANGE, 75f, 600f, 300f, "Hz"),
        ParameterSpec(ParameterId(13), ParameterScope.PRESET, "EQ MID", "EQ_MID", ParameterType.RANGE, 0f, 10f, 5f, ""),
        ParameterSpec(ParameterId(14), ParameterScope.PRESET, "EQ MIDQ", "EQ_MIDQ", ParameterType.RANGE, 0.2f, 3.0f, 0.7f, ""),
        ParameterSpec(ParameterId(15), ParameterScope.PRESET, "EQ MFREQ", "EQ_MID_FREQ", ParameterType.RANGE, 150f, 5000f, 750f, "Hz"),
        ParameterSpec(ParameterId(16), ParameterScope.PRESET, "EQ TREBLE", "EQ_TREBLE", ParameterType.RANGE, 0f, 10f, 5f, ""),
        ParameterSpec(ParameterId(17), ParameterScope.PRESET, "EQ TFREQ", "EQ_TREBLE_FREQ", ParameterType.RANGE, 1000f, 4000f, 1900f, "Hz"),

        // Amplifier / Cabinet (18–24)
        ParameterSpec(ParameterId(18), ParameterScope.PRESET, "MDL AMP", "MODEL_AMP_ENABLE", ParameterType.SWITCH, 0f, 1f, 1f, ""),
        ParameterSpec(ParameterId(19), ParameterScope.PRESET, "MDL SW1", "MODEL_SW1", ParameterType.SWITCH, 0f, 1f, 0f, ""),
        ParameterSpec(ParameterId(20), ParameterScope.PRESET, "MDL GAIN", "MODEL_GAIN", ParameterType.RANGE, 0f, 10f, 5f, ""),
        ParameterSpec(ParameterId(21), ParameterScope.PRESET, "MDL VOL", "MODEL_VOLUME", ParameterType.RANGE, 0f, 10f, 5f, ""),
        ParameterSpec(ParameterId(22), ParameterScope.PRESET, "MDL MIX", "MODEX_MIX", ParameterType.RANGE, 0f, 100f, 100f, "%"),
        ParameterSpec(ParameterId(23), ParameterScope.PRESET, "MDL CABU", "CABINET_UNKNOWN", ParameterType.SWITCH, 0f, 1f, 1f, ""),
        // Ordering: 0=Tone Model, 1=VIR, 2=Disabled — resolved against upstream source, not
        // hardware; see this file's class KDoc for the conflicting-comment evidence and why the
        // enum + its real usage wins over the stray comment.
        ParameterSpec(ParameterId(24), ParameterScope.PRESET, "MDL CAB", "CABINET_TYPE", ParameterType.SELECT, 0f, 2f, 0f, ""),

        // Virtual IR Cabinet (25–33)
        ParameterSpec(ParameterId(25), ParameterScope.PRESET, "VIR_CMDL", "VIR_CABINET_MODEL", ParameterType.SELECT, 0f, 10f, 5f, ""),
        ParameterSpec(ParameterId(26), ParameterScope.PRESET, "VIR_RESO", "VIR_RESO", ParameterType.RANGE, 0f, 10f, 0f, ""),
        ParameterSpec(ParameterId(27), ParameterScope.PRESET, "VIR M1", "VIR_MIC_1", ParameterType.SELECT, 0f, 2f, 0f, ""),
        ParameterSpec(ParameterId(28), ParameterScope.PRESET, "VIR M1X", "VIR_MIC_1_X", ParameterType.RANGE, 0f, 10f, 0f, ""),
        ParameterSpec(ParameterId(29), ParameterScope.PRESET, "VIR M1Z", "VIR_MIC_1_Z", ParameterType.RANGE, 0f, 10f, 0f, ""),
        ParameterSpec(ParameterId(30), ParameterScope.PRESET, "VIR M2", "VIR_MIC_2", ParameterType.SELECT, 0f, 2f, 0f, ""),
        // max=2 (vs. VIR_MIC_1_X's 10) is written explicitly and deliberately in upstream's own
        // table, not omitted or derived — treat the asymmetry as real; see this file's class KDoc.
        ParameterSpec(ParameterId(31), ParameterScope.PRESET, "VIR M2X", "VIR_MIC_2_X", ParameterType.RANGE, 0f, 2f, 0f, ""),
        ParameterSpec(ParameterId(32), ParameterScope.PRESET, "VIR M2Z", "VIR_MIC_2_Z", ParameterType.RANGE, 0f, 10f, 0f, ""),
        ParameterSpec(ParameterId(33), ParameterScope.PRESET, "VIR BLEND", "VIR_BLEND", ParameterType.RANGE, -100f, 100f, 0f, ""),

        // Amplifier Extras (34–35)
        ParameterSpec(ParameterId(34), ParameterScope.PRESET, "MDL PRE", "MODEL_PRESENCE", ParameterType.RANGE, 0f, 10f, 5f, ""),
        ParameterSpec(ParameterId(35), ParameterScope.PRESET, "MDL DEP", "MODEL_DEPTH", ParameterType.RANGE, 0f, 10f, 5f, ""),

        // Reverb (36–62) — 6 complete parameter sets (Spring1–4, Room, Plate)
        ParameterSpec(ParameterId(36), ParameterScope.PRESET, "RVB POS", "REVERB_POSITION", ParameterType.SWITCH, 0f, 1f, 0f, ""),
        ParameterSpec(ParameterId(37), ParameterScope.PRESET, "RVB POWER", "REVERB_ENABLE", ParameterType.SWITCH, 0f, 1f, 1f, ""),
        ParameterSpec(ParameterId(38), ParameterScope.PRESET, "RVB MODEL", "REVERB_MODEL", ParameterType.SELECT, 0f, 5f, 0f, ""),
        ParameterSpec(ParameterId(39), ParameterScope.PRESET, "RVB S1 T", "REVERB_SPRING1_TIME", ParameterType.RANGE, 0f, 10f, 5f, ""),
        ParameterSpec(ParameterId(40), ParameterScope.PRESET, "RVB S1 P", "REVERB_SPRING1_PREDELAY", ParameterType.RANGE, 0f, 500f, 0f, "ms"),
        ParameterSpec(ParameterId(41), ParameterScope.PRESET, "RVB S1 C", "REVERB_SPRING1_COLOR", ParameterType.RANGE, -10f, 10f, 0f, ""),
        ParameterSpec(ParameterId(42), ParameterScope.PRESET, "RVB S1 M", "REVERB_SPRING1_MIX", ParameterType.RANGE, 0f, 100f, 0f, "%"),
        ParameterSpec(ParameterId(43), ParameterScope.PRESET, "RVB S2 T", "REVERB_SPRING2_TIME", ParameterType.RANGE, 0f, 10f, 5f, ""),
        ParameterSpec(ParameterId(44), ParameterScope.PRESET, "RVB S2 P", "REVERB_SPRING2_PREDELAY", ParameterType.RANGE, 0f, 500f, 0f, "ms"),
        ParameterSpec(ParameterId(45), ParameterScope.PRESET, "RVB S2 C", "REVERB_SPRING2_COLOR", ParameterType.RANGE, -10f, 10f, 0f, ""),
        ParameterSpec(ParameterId(46), ParameterScope.PRESET, "RVB S2 M", "REVERB_SPRING2_MIX", ParameterType.RANGE, 0f, 100f, 0f, "%"),
        ParameterSpec(ParameterId(47), ParameterScope.PRESET, "RVB S3 T", "REVERB_SPRING3_TIME", ParameterType.RANGE, 0f, 10f, 5f, ""),
        ParameterSpec(ParameterId(48), ParameterScope.PRESET, "RVB S3 P", "REVERB_SPRING3_PREDELAY", ParameterType.RANGE, 0f, 500f, 0f, "ms"),
        ParameterSpec(ParameterId(49), ParameterScope.PRESET, "RVB S3 C", "REVERB_SPRING3_COLOR", ParameterType.RANGE, -10f, 10f, 0f, ""),
        ParameterSpec(ParameterId(50), ParameterScope.PRESET, "RVB S3 M", "REVERB_SPRING3_MIX", ParameterType.RANGE, 0f, 100f, 0f, "%"),
        ParameterSpec(ParameterId(51), ParameterScope.PRESET, "RVB S4 T", "REVERB_SPRING4_TIME", ParameterType.RANGE, 0f, 10f, 5f, ""),
        ParameterSpec(ParameterId(52), ParameterScope.PRESET, "RVB S4 P", "REVERB_SPRING4_PREDELAY", ParameterType.RANGE, 0f, 500f, 0f, "ms"),
        ParameterSpec(ParameterId(53), ParameterScope.PRESET, "RVB S4 C", "REVERB_SPRING4_COLOR", ParameterType.RANGE, -10f, 10f, 0f, ""),
        ParameterSpec(ParameterId(54), ParameterScope.PRESET, "RVB S4 M", "REVERB_SPRING4_MIX", ParameterType.RANGE, 0f, 100f, 0f, "%"),
        ParameterSpec(ParameterId(55), ParameterScope.PRESET, "RVB RM T", "REVERB_ROOM_TIME", ParameterType.RANGE, 0f, 10f, 5f, ""),
        ParameterSpec(ParameterId(56), ParameterScope.PRESET, "RVB RM P", "REVERB_ROOM_PREDELAY", ParameterType.RANGE, 0f, 500f, 0f, "ms"),
        ParameterSpec(ParameterId(57), ParameterScope.PRESET, "RVB RM C", "REVERB_ROOM_COLOR", ParameterType.RANGE, -10f, 10f, 0f, ""),
        ParameterSpec(ParameterId(58), ParameterScope.PRESET, "RVB RM M", "REVERB_ROOM_MIX", ParameterType.RANGE, 0f, 100f, 0f, "%"),
        ParameterSpec(ParameterId(59), ParameterScope.PRESET, "RVB PL T", "REVERB_PLATE_TIME", ParameterType.RANGE, 0f, 10f, 5f, ""),
        ParameterSpec(ParameterId(60), ParameterScope.PRESET, "RVB PL P", "REVERB_PLATE_PREDELAY", ParameterType.RANGE, 0f, 500f, 0f, "ms"),
        ParameterSpec(ParameterId(61), ParameterScope.PRESET, "RVB PL C", "REVERB_PLATE_COLOR", ParameterType.RANGE, -10f, 10f, 0f, ""),
        ParameterSpec(ParameterId(62), ParameterScope.PRESET, "RVB PL M", "REVERB_PLATE_MIX", ParameterType.RANGE, 0f, 100f, 0f, "%"),

        // Modulation (63–93) — 5 complete parameter sets (Chorus, Tremolo, Phaser, Flanger, Rotary)
        ParameterSpec(ParameterId(63), ParameterScope.PRESET, "MOD POST", "MODULATION_POST", ParameterType.SWITCH, 0f, 1f, 0f, ""),
        ParameterSpec(ParameterId(64), ParameterScope.PRESET, "MOD POWER", "MODULATION_ENABLE", ParameterType.SWITCH, 0f, 1f, 0f, ""),
        ParameterSpec(ParameterId(65), ParameterScope.PRESET, "MOD MODEL", "MODULATION_MODEL", ParameterType.SELECT, 0f, 4f, 0f, ""),
        ParameterSpec(ParameterId(66), ParameterScope.PRESET, "MOD CH S", "MODULATION_CHORUS_SYNC", ParameterType.SWITCH, 0f, 1f, 0f, ""),
        ParameterSpec(ParameterId(67), ParameterScope.PRESET, "MOD CH T", "MODULATION_CHORUS_TS", ParameterType.SELECT, 0f, 17f, 0f, ""),
        ParameterSpec(ParameterId(68), ParameterScope.PRESET, "MOD CH R", "MODULATION_CHORUS_RATE", ParameterType.RANGE, 0.1f, 10f, 0.5f, "Hz"),
        ParameterSpec(ParameterId(69), ParameterScope.PRESET, "MOD CH D", "MODULATION_CHORUS_DEPTH", ParameterType.RANGE, 0f, 100f, 0f, "%"),
        ParameterSpec(ParameterId(70), ParameterScope.PRESET, "MOD CH L", "MODULATION_CHORUS_LEVEL", ParameterType.RANGE, 0f, 10f, 0f, ""),
        ParameterSpec(ParameterId(71), ParameterScope.PRESET, "MOD TR S", "MODULATION_TREMOLO_SYNC", ParameterType.SWITCH, 0f, 1f, 0f, ""),
        ParameterSpec(ParameterId(72), ParameterScope.PRESET, "MOD TR T", "MODULATION_TREMOLO_TS", ParameterType.SELECT, 0f, 17f, 0f, ""),
        ParameterSpec(ParameterId(73), ParameterScope.PRESET, "MOD TR R", "MODULATION_TREMOLO_RATE", ParameterType.RANGE, 0.1f, 10f, 0.5f, "Hz"),
        ParameterSpec(ParameterId(74), ParameterScope.PRESET, "MOD TR P", "MODULATION_TREMOLO_SHAPE", ParameterType.RANGE, 0f, 10f, 0f, ""),
        ParameterSpec(ParameterId(75), ParameterScope.PRESET, "MOD TR D", "MODULATION_TREMOLO_SPREAD", ParameterType.RANGE, 0f, 100f, 0f, "%"),
        ParameterSpec(ParameterId(76), ParameterScope.PRESET, "MOD TR L", "MODULATION_TREMOLO_LEVEL", ParameterType.RANGE, 0f, 10f, 0f, ""),
        ParameterSpec(ParameterId(77), ParameterScope.PRESET, "MOD PH S", "MODULATION_PHASER_SYNC", ParameterType.SWITCH, 0f, 1f, 0f, ""),
        ParameterSpec(ParameterId(78), ParameterScope.PRESET, "MOD PH T", "MODULATION_PHASER_TS", ParameterType.SELECT, 0f, 17f, 0f, ""),
        ParameterSpec(ParameterId(79), ParameterScope.PRESET, "MOD PH R", "MODULATION_PHASER_RATE", ParameterType.RANGE, 0.1f, 10f, 0.5f, "Hz"),
        ParameterSpec(ParameterId(80), ParameterScope.PRESET, "MOD PH D", "MODULATION_PHASER_DEPTH", ParameterType.RANGE, 0f, 100f, 0f, "%"),
        ParameterSpec(ParameterId(81), ParameterScope.PRESET, "MOD PH L", "MODULATION_PHASER_LEVEL", ParameterType.RANGE, 0f, 10f, 0f, ""),
        ParameterSpec(ParameterId(82), ParameterScope.PRESET, "MOD FL S", "MODULATION_FLANGER_SYNC", ParameterType.SWITCH, 0f, 1f, 0f, ""),
        ParameterSpec(ParameterId(83), ParameterScope.PRESET, "MOD FL T", "MODULATION_FLANGER_TS", ParameterType.SELECT, 0f, 17f, 0f, ""),
        ParameterSpec(ParameterId(84), ParameterScope.PRESET, "MOD FL R", "MODULATION_FLANGER_RATE", ParameterType.RANGE, 0.1f, 10f, 0.5f, "Hz"),
        ParameterSpec(ParameterId(85), ParameterScope.PRESET, "MOD FL D", "MODULATION_FLANGER_DEPTH", ParameterType.RANGE, 0f, 100f, 0f, "%"),
        ParameterSpec(ParameterId(86), ParameterScope.PRESET, "MOD FL F", "MODULATION_FLANGER_FEEDBACK", ParameterType.RANGE, 0f, 100f, 0f, "%"),
        ParameterSpec(ParameterId(87), ParameterScope.PRESET, "MOD FL L", "MODULATION_FLANGER_LEVEL", ParameterType.RANGE, 0f, 10f, 0f, ""),
        ParameterSpec(ParameterId(88), ParameterScope.PRESET, "MOD RO S", "MODULATION_ROTARY_SYNC", ParameterType.SWITCH, 0f, 1f, 0f, ""),
        ParameterSpec(ParameterId(89), ParameterScope.PRESET, "MOD RO T", "MODULATION_ROTARY_TS", ParameterType.SELECT, 0f, 17f, 0f, ""),
        ParameterSpec(ParameterId(90), ParameterScope.PRESET, "MOD RO S", "MODULATION_ROTARY_SPEED", ParameterType.RANGE, 0f, 400f, 0f, "RPM"),
        ParameterSpec(ParameterId(91), ParameterScope.PRESET, "MOD RO R", "MODULATION_ROTARY_RADIUS", ParameterType.RANGE, 0f, 300f, 0f, ""),
        ParameterSpec(ParameterId(92), ParameterScope.PRESET, "MOD RO D", "MODULATION_ROTARY_SPREAD", ParameterType.RANGE, 0f, 100f, 0f, "%"),
        ParameterSpec(ParameterId(93), ParameterScope.PRESET, "MOD RO L", "MODULATION_ROTARY_LEVEL", ParameterType.RANGE, 0f, 10f, 0f, ""),

        // Delay (94–108) — 2 complete parameter sets (Digital, Tape)
        ParameterSpec(ParameterId(94), ParameterScope.PRESET, "DLY POST", "DELAY_POST", ParameterType.SWITCH, 0f, 1f, 0f, ""),
        ParameterSpec(ParameterId(95), ParameterScope.PRESET, "DLY POWER", "DELAY_ENABLE", ParameterType.SWITCH, 0f, 1f, 0f, ""),
        ParameterSpec(ParameterId(96), ParameterScope.PRESET, "DLY MODEL", "DELAY_MODEL", ParameterType.SELECT, 0f, 1f, 0f, ""),
        ParameterSpec(ParameterId(97), ParameterScope.PRESET, "DLY DG S", "DELAY_DIGITAL_SYNC", ParameterType.SWITCH, 0f, 1f, 0f, ""),
        ParameterSpec(ParameterId(98), ParameterScope.PRESET, "DLY DG T", "DELAY_DIGITAL_TS", ParameterType.SELECT, 0f, 17f, 0f, ""),
        ParameterSpec(ParameterId(99), ParameterScope.PRESET, "DLY DT M", "DELAY_DIGITAL_TIME", ParameterType.RANGE, 0f, 1000f, 0f, "ms"),
        ParameterSpec(ParameterId(100), ParameterScope.PRESET, "DLY DT F", "DELAY_DIGITAL_FEEDBACK", ParameterType.RANGE, 0f, 100f, 0f, "%"),
        ParameterSpec(ParameterId(101), ParameterScope.PRESET, "DLY DT O", "DELAY_DIGITAL_MODE", ParameterType.SWITCH, 0f, 1f, 0f, ""),
        ParameterSpec(ParameterId(102), ParameterScope.PRESET, "DLY DT X", "DELAY_DIGITAL_MIX", ParameterType.RANGE, 0f, 100f, 0f, "%"),
        ParameterSpec(ParameterId(103), ParameterScope.PRESET, "DLY TA S", "DELAY_TAPE_SYNC", ParameterType.SWITCH, 0f, 1f, 0f, ""),
        ParameterSpec(ParameterId(104), ParameterScope.PRESET, "DLY TA T", "DELAY_TAPE_TS", ParameterType.SELECT, 0f, 17f, 0f, ""),
        ParameterSpec(ParameterId(105), ParameterScope.PRESET, "DLY TA M", "DELAY_TAPE_TIME", ParameterType.RANGE, 0f, 1000f, 0f, "ms"),
        ParameterSpec(ParameterId(106), ParameterScope.PRESET, "DLY TA F", "DELAY_TAPE_FEEDBACK", ParameterType.RANGE, 0f, 100f, 0f, "%"),
        ParameterSpec(ParameterId(107), ParameterScope.PRESET, "DLY TA O", "DELAY_TAPE_MODE", ParameterType.SWITCH, 0f, 1f, 0f, ""),
        ParameterSpec(ParameterId(108), ParameterScope.PRESET, "DLY TA X", "DELAY_TAPE_MIX", ParameterType.RANGE, 0f, 100f, 0f, "%"),

        // Global Parameters (110–116)
        ParameterSpec(ParameterId(110), ParameterScope.GLOBAL, "BPM", "BPM", ParameterType.RANGE, 40f, 240f, 80f, "BPM"),
        ParameterSpec(ParameterId(111), ParameterScope.GLOBAL, "TRIM", "INPUT_TRIM", ParameterType.RANGE, -15f, 15f, 0f, "dB"),
        ParameterSpec(ParameterId(112), ParameterScope.GLOBAL, "CABSIM", "CABSIM_BYPASS", ParameterType.SWITCH, 0f, 1f, 0f, ""),
        ParameterSpec(ParameterId(113), ParameterScope.GLOBAL, "TEMPOS", "TEMPO_SOURCE", ParameterType.SWITCH, 0f, 1f, 0f, ""),
        ParameterSpec(ParameterId(114), ParameterScope.GLOBAL, "TUNEREF", "TUNING_REFERENCE", ParameterType.RANGE, 415f, 465f, 440f, "Hz"),
        ParameterSpec(ParameterId(115), ParameterScope.GLOBAL, "BYPASS", "BYPASS", ParameterType.SWITCH, 0f, 1f, 0f, ""),
        ParameterSpec(ParameterId(116), ParameterScope.GLOBAL, "MVOL", "MASTER_VOLUME", ParameterType.RANGE, -40f, 3f, 0f, "dB"),
    )

    private val INDEX_MAP: Map<Int, ParameterSpec> = ALL_PARAMETERS.associateBy { it.id.index }
    private val ENUM_NAME_MAP: Map<String, ParameterSpec> = ALL_PARAMETERS.associateBy { it.enumName }

    /**
     * Retrieve a parameter specification by its wire index (0–108 for preset, 110–116 for global).
     *
     * @param index the wire-position parameter index
     * @return the [ParameterSpec], or null if the index is invalid (including 109, the LAST sentinel)
     */
    fun byIndex(index: Int): ParameterSpec? = INDEX_MAP[index]

    /**
     * Retrieve a parameter specification by its unique upstream enum identifier (e.g.,
     * `"NOISE_GATE_THRESHOLD"`, `"MASTER_VOLUME"`).
     *
     * Use this for lookups, logging, and persisted keys. Do not use the display name
     * ([ParameterSpec.name]) for identification, as display names are not unique (e.g.,
     * indices 88 and 90 both carry `"MOD RO S"`).
     *
     * @param enumName the parameter's upstream identifier (without `TONEX_PARAM_` / `TONEX_GLOBAL_` prefix)
     * @return the [ParameterSpec], or null if no parameter has that enum name
     */
    fun byEnumName(enumName: String): ParameterSpec? = ENUM_NAME_MAP[enumName]

    /**
     * Return the full ordered list of all 116 parameters. Index 109 (the `"LAST"` sentinel) is absent.
     *
     * @return an immutable list of [ParameterSpec], ordered by wire index (0..108, then 110..116)
     */
    fun all(): List<ParameterSpec> = ALL_PARAMETERS

    /**
     * Clamp a value to the legal range for a given parameter.
     *
     * @param id the parameter to clamp for
     * @param value the raw value
     * @return the clamped value, between [ParameterSpec.min] and [ParameterSpec.max]
     * @throws IllegalArgumentException if the parameter id is invalid (including 109)
     */
    fun clamp(id: ParameterId, value: Float): Float {
        val spec = byIndex(id.index) ?: throw IllegalArgumentException("Invalid parameter id: $id")
        return value.coerceIn(spec.min, spec.max)
    }
}
