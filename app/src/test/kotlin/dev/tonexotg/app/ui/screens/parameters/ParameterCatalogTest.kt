package dev.tonexotg.app.ui.screens.parameters

import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.params.ParameterRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the coverage guarantee [ParameterCatalog]'s own class KDoc promises: every one of the
 * registry's 116 parameters lands in exactly one full-tier place — a category's flat/always-on
 * rows, or one of its model bank's per-model rows — so D3's tiering never silently drops a
 * parameter the protocol layer can still read/write, and never renders the same wire index twice
 * under two different categories.
 *
 * The quick tier is deliberately excluded from the "exactly one" check: D3 §1.2's own footnote
 * says Gain (and, by the same reasoning, Bass/Mid/Treble) is promoted to the quick tier *and*
 * still appears in its home category "so a player browsing the full tier doesn't wonder where it
 * went" — an intentional overlap with the full tier, not a bug this test should flag.
 */
class ParameterCatalogTest {

    private val allRegistryIds: Set<ParameterId> = ParameterRegistry.all().map { it.id }.toSet()

    @Test
    fun everyRegistryParameter_isCoveredByExactlyOneFullTierPlacement() {
        val placements = mutableListOf<Pair<ParameterId, String>>() // (id, "category > source") for duplicate diagnostics

        for (category in ParameterCatalog.categories) {
            category.flatIds.forEach { placements += it to "${category.title} (flat)" }
            category.alwaysOnIds.forEach { placements += it to "${category.title} (always-on)" }
            category.modelBank?.models?.forEach { model ->
                model.parameterIds.forEach { placements += it to "${category.title} > ${model.label}" }
            }
        }

        val coveredIds = placements.map { it.first }.toSet()
        // issue #83: the 6 home-screen global ids are deliberately absent from every full-tier
        // category/bank now (unlike MASTER_VOLUME, which keeps a category placement alongside its
        // dock) - their one legitimate placement is ParameterCatalog.homeScreenGlobalIds instead.
        val missing = allRegistryIds - coveredIds - ParameterCatalog.homeScreenGlobalIds.toSet()
        assertTrue("registry parameters missing from every full-tier category/bank: $missing", missing.isEmpty())

        val extra = coveredIds - allRegistryIds
        assertTrue("full-tier placements reference ids not in the registry at all: $extra", extra.isEmpty())

        val duplicates = placements.groupBy { it.first }.filterValues { it.size > 1 }
        assertTrue(
            "each id must appear in exactly one full-tier placement, but these appear in more than one: $duplicates",
            duplicates.isEmpty(),
        )

        val homeScreenIdsStillInFullTier = coveredIds.intersect(ParameterCatalog.homeScreenGlobalIds.toSet())
        assertTrue(
            "home-screen global ids must not also appear in a full-tier category/bank: $homeScreenIdsStillInFullTier",
            homeScreenIdsStillInFullTier.isEmpty(),
        )
    }

    @Test
    fun homeScreenGlobalIds_isExactlyTheSixIssue83Parameters() {
        val expectedEnumNames = setOf("BPM", "INPUT_TRIM", "CABSIM_BYPASS", "TEMPO_SOURCE", "TUNING_REFERENCE", "BYPASS")
        val actualEnumNames = ParameterCatalog.homeScreenGlobalIds
            .map { id -> requireNotNull(ParameterRegistry.byIndex(id.index)) { "no ParameterSpec for $id" }.enumName }
            .toSet()
        assertEquals(expectedEnumNames, actualEnumNames)
    }

    @Test
    fun quickTierFixedIds_areAlsoPresentInTheirHomeCategory() {
        // D3 §1.2 footnote: promotion to the quick tier does not remove a parameter from its
        // full-tier category — the opposite (silently vanishing from the full tier) is the bug
        // this pins against.
        val fullTierFlatAndAlwaysOnIds = ParameterCatalog.categories
            .flatMap { it.flatIds + it.alwaysOnIds }
            .toSet()

        val quickTierFixedIds = ParameterCatalog.quickTier
            .filterIsInstance<ParameterCatalog.QuickTierItem.Fixed>()
            .map { it.id }

        quickTierFixedIds.forEach { id ->
            assertTrue("quick-tier fixed id $id must still appear in its home category's rows", id in fullTierFlatAndAlwaysOnIds)
        }
    }

    @Test
    fun everyModelBankSelector_hasAKnownLabel() {
        // D3 §3.2: the four model selectors are exactly the SELECT parameters with known option
        // labels; every other SELECT gets a stepper.
        val bankSelectorIds = setOf(
            ParameterCatalog.reverbBank.selectorId,
            ParameterCatalog.modulationBank.selectorId,
            ParameterCatalog.delayBank.selectorId,
            ParameterCatalog.cabinetBank.selectorId,
        )
        assertEquals(bankSelectorIds, ParameterCatalog.knownLabelSelectIds)
    }

    @Test
    fun resolvedQuickTierItems_resolveToARealMixParameterForEveryModel() {
        // Every model of the reverb/delay bank must contain exactly one *_MIX-suffixed parameter
        // for QuickTierItem.Resolved.resolve() to find - a bank model with none would NPE at
        // runtime the first time that model becomes active.
        val resolvedItems = ParameterCatalog.quickTier.filterIsInstance<ParameterCatalog.QuickTierItem.Resolved>()
        for (item in resolvedItems) {
            for (model in item.bank.models) {
                val mixId = item.bank.mixParameterFor(model.index)
                val spec = ParameterRegistry.byIndex(mixId.index)
                assertTrue("model ${model.label} of ${item.label} must resolve to a real *_MIX parameter", spec != null)
                assertTrue("resolved parameter for ${model.label} must actually end in _MIX", spec!!.enumName.endsWith("_MIX"))
            }
        }
    }
}
