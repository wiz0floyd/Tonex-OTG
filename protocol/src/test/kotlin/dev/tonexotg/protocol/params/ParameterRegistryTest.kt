package dev.tonexotg.protocol.params

import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.ParameterScope
import dev.tonexotg.protocol.ParameterType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ParameterRegistryTest {

    @Test
    fun `registry has exactly 109 preset parameters`() {
        val presetParams = ParameterRegistry.all().filter { it.scope == ParameterScope.PRESET }
        assertEquals(109, presetParams.size, "Expected 109 preset parameters")
    }

    @Test
    fun `registry has exactly 7 global parameters`() {
        val globalParams = ParameterRegistry.all().filter { it.scope == ParameterScope.GLOBAL }
        assertEquals(7, globalParams.size, "Expected 7 global parameters")
    }

    @Test
    fun `registry has exactly 116 total parameters`() {
        assertEquals(116, ParameterRegistry.all().size, "Expected 116 total parameters")
    }

    @Test
    fun `all preset parameter indices are 0 to 108`() {
        val presetParams = ParameterRegistry.all().filter { it.scope == ParameterScope.PRESET }
        val indices = presetParams.map { it.id.index }.toSet()
        assertEquals((0..108).toSet(), indices, "Preset indices should be 0..108 with no gaps")
    }

    @Test
    fun `all global parameter indices are 110 to 116`() {
        val globalParams = ParameterRegistry.all().filter { it.scope == ParameterScope.GLOBAL }
        val indices = globalParams.map { it.id.index }.toSet()
        assertEquals((110..116).toSet(), indices, "Global indices should be 110..116 with no gaps")
    }

    @Test
    fun `no duplicate parameter indices`() {
        val indices = ParameterRegistry.all().map { it.id.index }
        assertEquals(indices.size, indices.toSet().size, "All parameter indices should be unique")
    }

    @Test
    fun `index 109 LAST sentinel is absent`() {
        assertNull(ParameterRegistry.byIndex(109), "Index 109 (LAST sentinel) should not be in registry")
    }

    @Test
    fun `all enumNames are unique`() {
        val enumNames = ParameterRegistry.all().map { it.enumName }
        assertEquals(enumNames.size, enumNames.toSet().size, "All enumNames should be unique")
    }

    @Test
    fun `known display name collision at indices 88 and 90 both carry "MOD RO S"`() {
        // Indices 88 and 90 both have the same display name "MOD RO S" because they represent
        // SYNC and SPEED variants of the rotary modulation. This is upstream truth, not a defect.
        // Use enumName for unique identification: MODULATION_ROTARY_SYNC vs MODULATION_ROTARY_SPEED.
        val param88 = ParameterRegistry.byIndex(88)
        val param90 = ParameterRegistry.byIndex(90)

        assertNotNull(param88)
        assertNotNull(param90)

        assertEquals("MOD RO S", param88.name, "Index 88 display name should be MOD RO S")
        assertEquals("MOD RO S", param90.name, "Index 90 display name should be MOD RO S")

        assertEquals("MODULATION_ROTARY_SYNC", param88.enumName, "Index 88 should be SYNC")
        assertEquals("MODULATION_ROTARY_SPEED", param90.enumName, "Index 90 should be SPEED")
    }

    @Test
    fun `min less than or equal to default for every parameter`() {
        ParameterRegistry.all().forEach { param ->
            assertTrue(
                param.min <= param.default,
                "Parameter ${param.enumName} (index ${param.id.index}): min (${param.min}) > default (${param.default})"
            )
        }
    }

    @Test
    fun `default less than or equal to max for every parameter`() {
        ParameterRegistry.all().forEach { param ->
            assertTrue(
                param.default <= param.max,
                "Parameter ${param.enumName} (index ${param.id.index}): default (${param.default}) > max (${param.max})"
            )
        }
    }

    @Test
    fun `every SWITCH has min 0 and max 1`() {
        ParameterRegistry.all().filter { it.type == ParameterType.SWITCH }.forEach { param ->
            assertEquals(
                0f,
                param.min,
                "SWITCH parameter ${param.enumName} (index ${param.id.index}) should have min 0"
            )
            assertEquals(
                1f,
                param.max,
                "SWITCH parameter ${param.enumName} (index ${param.id.index}) should have max 1"
            )
        }
    }

    @Test
    fun `every SELECT has integer range with meaningful max`() {
        ParameterRegistry.all().filter { it.type == ParameterType.SELECT }.forEach { param ->
            assertEquals(
                0f,
                param.min,
                "SELECT parameter ${param.enumName} (index ${param.id.index}) should have min 0"
            )
            assertTrue(
                param.max > 0,
                "SELECT parameter ${param.enumName} (index ${param.id.index}) should have max > 0"
            )
            assertTrue(
                param.max % 1f == 0f,
                "SELECT parameter ${param.enumName} (index ${param.id.index}) max should be an integer"
            )
        }
    }

    @Test
    fun `byIndex returns parameter for valid indices`() {
        assertNotNull(ParameterRegistry.byIndex(0), "Index 0 should exist")
        assertNotNull(ParameterRegistry.byIndex(54), "Index 54 should exist")
        assertNotNull(ParameterRegistry.byIndex(108), "Index 108 should exist")
        assertNotNull(ParameterRegistry.byIndex(110), "Index 110 should exist")
        assertNotNull(ParameterRegistry.byIndex(116), "Index 116 should exist")
    }

    @Test
    fun `byIndex returns null for invalid indices`() {
        assertNull(ParameterRegistry.byIndex(-1), "Index -1 should not exist")
        assertNull(ParameterRegistry.byIndex(109), "Index 109 (LAST sentinel) should not exist")
        assertNull(ParameterRegistry.byIndex(117), "Index 117 should not exist")
    }

    @Test
    fun `byEnumName returns parameter for valid enum names`() {
        assertNotNull(ParameterRegistry.byEnumName("NOISE_GATE_THRESHOLD"), "NOISE_GATE_THRESHOLD should exist")
        assertNotNull(ParameterRegistry.byEnumName("MASTER_VOLUME"), "MASTER_VOLUME should exist")
        assertNotNull(ParameterRegistry.byEnumName("BPM"), "BPM should exist")
    }

    @Test
    fun `byEnumName returns null for invalid enum names`() {
        assertNull(ParameterRegistry.byEnumName("NONEXISTENT"), "Nonexistent enum name should return null")
        assertNull(ParameterRegistry.byEnumName(""), "Empty enum name should return null")
    }

    @Test
    fun `spot check noise gate threshold parameter`() {
        val param = ParameterRegistry.byIndex(2)
        assertNotNull(param)
        assertEquals("NG THRESH", param.name)
        assertEquals("NOISE_GATE_THRESHOLD", param.enumName)
        assertEquals(ParameterType.RANGE, param.type)
        assertEquals(-100f, param.min)
        assertEquals(0f, param.max)
        assertEquals(-64f, param.default)
        assertEquals("dB", param.unit)
    }

    @Test
    fun `spot check compressor attack parameter`() {
        val param = ParameterRegistry.byIndex(9)
        assertNotNull(param)
        assertEquals("COMP ATTACK", param.name)
        assertEquals("COMP_ATTACK", param.enumName)
        assertEquals(ParameterType.RANGE, param.type)
        assertEquals(1f, param.min)
        assertEquals(51f, param.max)
        assertEquals(14f, param.default)
        assertEquals("ms", param.unit)
    }

    @Test
    fun `spot check EQ mid Q parameter`() {
        val param = ParameterRegistry.byIndex(14)
        assertNotNull(param)
        assertEquals("EQ MIDQ", param.name)
        assertEquals("EQ_MIDQ", param.enumName)
        assertEquals(ParameterType.RANGE, param.type)
        assertEquals(0.2f, param.min)
        assertEquals(3.0f, param.max)
        assertEquals(0.7f, param.default)
    }

    @Test
    fun `spot check cabinet type parameter (SELECT, ordering resolved against upstream source)`() {
        val param = ParameterRegistry.byIndex(24)
        assertNotNull(param)
        assertEquals("MDL CAB", param.name)
        assertEquals("CABINET_TYPE", param.enumName)
        assertEquals(ParameterType.SELECT, param.type)
        assertEquals(0f, param.min)
        assertEquals(2f, param.max) // 0=Tone Model, 1=VIR, 2=Disabled — see ParameterRegistry's class KDoc
        assertEquals(0f, param.default)
    }

    /**
     * Deliberately diverges from upstream `tonex_params.c` (which says max 10, at both the pinned
     * commit and upstream `main`). Real hardware returned 25.0 for this parameter on factory-stock
     * preset 0 per the S22 drill summary on issue #27 (raw log not available to the session that
     * made this change — see ParameterRegistry's class KDoc for exact provenance). 25 is a
     * hardware-observed floor, not a confirmed option count; raise it if a higher value is ever
     * seen. It must never regress below 25, or the pedal's own factory state becomes unrestorable.
     */
    @Test
    fun `spot check VIR cabinet model parameter (max 25 from hardware, not upstream's 10)`() {
        val param = ParameterRegistry.byIndex(25)
        assertNotNull(param)
        assertEquals("VIR_CMDL", param.name)
        assertEquals("VIR_CABINET_MODEL", param.enumName)
        assertEquals(ParameterType.SELECT, param.type)
        assertEquals(0f, param.min)
        assertEquals(25f, param.max)
        assertEquals(5f, param.default)
        assertTrue(
            param.max >= 25f,
            "VIR_CABINET_MODEL's max must admit the 25.0 a real pedal reports on stock preset 0",
        )
    }

    @Test
    fun `spot check VIR M2X parameter (max is genuinely 2, not a typo for M1X's 10)`() {
        val param = ParameterRegistry.byIndex(31)
        assertNotNull(param)
        assertEquals("VIR M2X", param.name)
        assertEquals("VIR_MIC_2_X", param.enumName)
        assertEquals(ParameterType.RANGE, param.type)
        assertEquals(0f, param.min)
        assertEquals(2f, param.max) // written explicitly in upstream's own table — see ParameterRegistry's class KDoc
        assertEquals(0f, param.default)
    }

    @Test
    fun `spot check VIR M1X parameter for comparison with M2X`() {
        val param = ParameterRegistry.byIndex(28)
        assertNotNull(param)
        assertEquals("VIR M1X", param.name)
        assertEquals("VIR_MIC_1_X", param.enumName)
        assertEquals(ParameterType.RANGE, param.type)
        assertEquals(0f, param.min)
        assertEquals(10f, param.max) // M1X has max 10, M2X has max 2 - likely typo
        assertEquals(0f, param.default)
    }

    @Test
    fun `spot check reverb model parameter (SELECT) - 6 options Spring1-4 Room Plate`() {
        val param = ParameterRegistry.byIndex(38)
        assertNotNull(param)
        assertEquals("RVB MODEL", param.name)
        assertEquals("REVERB_MODEL", param.enumName)
        assertEquals(ParameterType.SELECT, param.type)
        assertEquals(0f, param.min)
        assertEquals(5f, param.max) // 6 options: Spring1, Spring2, Spring3, Spring4, Room, Plate
        assertEquals(0f, param.default)
    }

    @Test
    fun `spot check modulation model parameter (SELECT with 5 options) - Chorus Tremolo Phaser Flanger Rotary`() {
        val param = ParameterRegistry.byIndex(65)
        assertNotNull(param)
        assertEquals("MOD MODEL", param.name)
        assertEquals("MODULATION_MODEL", param.enumName)
        assertEquals(ParameterType.SELECT, param.type)
        assertEquals(0f, param.min)
        assertEquals(4f, param.max) // 5 options: Chorus, Tremolo, Phaser, Flanger, Rotary
        assertEquals(0f, param.default)
    }

    @Test
    fun `spot check delay model parameter (SELECT with 2 options) - Digital Tape`() {
        val param = ParameterRegistry.byIndex(96)
        assertNotNull(param)
        assertEquals("DLY MODEL", param.name)
        assertEquals("DELAY_MODEL", param.enumName)
        assertEquals(ParameterType.SELECT, param.type)
        assertEquals(0f, param.min)
        assertEquals(1f, param.max) // 2 options: Digital, Tape
        assertEquals(0f, param.default)
    }

    @Test
    fun `spot check BPM global parameter`() {
        val param = ParameterRegistry.byIndex(110)
        assertNotNull(param)
        assertEquals("BPM", param.name)
        assertEquals("BPM", param.enumName)
        assertEquals(ParameterScope.GLOBAL, param.scope)
        assertEquals(ParameterType.RANGE, param.type)
        assertEquals(40f, param.min)
        assertEquals(240f, param.max)
        assertEquals(80f, param.default)
        assertEquals("BPM", param.unit)
    }

    @Test
    fun `spot check input trim global parameter`() {
        val param = ParameterRegistry.byIndex(111)
        assertNotNull(param)
        assertEquals("TRIM", param.name)
        assertEquals("INPUT_TRIM", param.enumName)
        assertEquals(ParameterScope.GLOBAL, param.scope)
        assertEquals(ParameterType.RANGE, param.type)
        assertEquals(-15f, param.min)
        assertEquals(15f, param.max)
        assertEquals(0f, param.default)
        assertEquals("dB", param.unit)
    }

    @Test
    fun `spot check master volume global parameter`() {
        val param = ParameterRegistry.byIndex(116)
        assertNotNull(param)
        assertEquals("MVOL", param.name)
        assertEquals("MASTER_VOLUME", param.enumName)
        assertEquals(ParameterScope.GLOBAL, param.scope)
        assertEquals(ParameterType.RANGE, param.type)
        assertEquals(-40f, param.min)
        assertEquals(3f, param.max)
        assertEquals(0f, param.default)
        assertEquals("dB", param.unit)
    }

    @Test
    fun `all list is immutable - cannot add parameters after construction`() {
        val all1 = ParameterRegistry.all()
        val all2 = ParameterRegistry.all()
        assertEquals(all1, all2, "Repeated calls should return equal lists")
        assertEquals(116, all1.size)
    }

    @Test
    fun `clamp respects min bound`() {
        val param = ParameterRegistry.byIndex(2)!! // NG THRESH, min=-100
        val clamped = ParameterRegistry.clamp(param.id, -150f)
        assertEquals(-100f, clamped)
    }

    @Test
    fun `clamp respects max bound`() {
        val param = ParameterRegistry.byIndex(2)!! // NG THRESH, max=0
        val clamped = ParameterRegistry.clamp(param.id, 50f)
        assertEquals(0f, clamped)
    }

    @Test
    fun `clamp passes through in-range value`() {
        val param = ParameterRegistry.byIndex(2)!! // NG THRESH, min=-100, max=0
        val clamped = ParameterRegistry.clamp(param.id, -50f)
        assertEquals(-50f, clamped)
    }

    @Test
    fun `clamp throws on invalid parameter id 109`() {
        assertFailsWith<IllegalArgumentException> {
            ParameterId(109)
        }
    }

    @Test
    fun `parameter order matches wire positional semantics`() {
        // Verify that the ordering is strictly positional as described in ParameterId
        val all = ParameterRegistry.all()
        val expectedIndices = (0..108).toList() + (110..116).toList()
        val actualIndices = all.map { it.id.index }
        assertEquals(expectedIndices, actualIndices, "Parameters should be ordered by wire index")
    }

    @Test
    fun `time signature selects have max 17 (18 options)`() {
        // Multiple parameters use time signature range: 0..17
        // These select which of 18 beat subdivisions to use for tempo sync
        val tsParams = listOf(
            67,  // MOD CH T (MODULATION_CHORUS_TS)
            72,  // MOD TR T (MODULATION_TREMOLO_TS)
            78,  // MOD PH T (MODULATION_PHASER_TS)
            83,  // MOD FL T (MODULATION_FLANGER_TS)
            89,  // MOD RO T (MODULATION_ROTARY_TS)
            98,  // DLY DG T (DELAY_DIGITAL_TS)
            104, // DLY TA T (DELAY_TAPE_TS)
        )
        tsParams.forEach { index ->
            val param = ParameterRegistry.byIndex(index)
            assertNotNull(param)
            assertEquals(17f, param.max, "Time signature parameter at index $index should have max 17 (18 options)")
        }
    }

    @Test
    fun `cabinet simulation bypass is a switch at index 112`() {
        val param = ParameterRegistry.byIndex(112)
        assertNotNull(param)
        assertEquals("CABSIM", param.name)
        assertEquals("CABSIM_BYPASS", param.enumName)
        assertEquals(ParameterScope.GLOBAL, param.scope)
        assertEquals(ParameterType.SWITCH, param.type)
        assertEquals(0f, param.min)
        assertEquals(1f, param.max)
    }

    @Test
    fun `tuning reference in Hz range 415-465`() {
        val param = ParameterRegistry.byIndex(114)
        assertNotNull(param)
        assertEquals("TUNEREF", param.name)
        assertEquals("TUNING_REFERENCE", param.enumName)
        assertEquals(ParameterScope.GLOBAL, param.scope)
        assertEquals(415f, param.min)
        assertEquals(465f, param.max)
        assertEquals(440f, param.default) // A4 standard
        assertEquals("Hz", param.unit)
    }

    @Test
    fun `all parameters with negative min values are properly bounded`() {
        val paramsWithNegativeMin = ParameterRegistry.all().filter { it.min < 0 }
        assertTrue(paramsWithNegativeMin.isNotEmpty(), "There should be parameters with negative min values")
        paramsWithNegativeMin.forEach { param ->
            assertTrue(
                param.default >= param.min,
                "Parameter ${param.enumName}: default (${param.default}) < min (${param.min})"
            )
            assertTrue(
                param.default <= param.max,
                "Parameter ${param.enumName}: default (${param.default}) > max (${param.max})"
            )
        }
    }
}
