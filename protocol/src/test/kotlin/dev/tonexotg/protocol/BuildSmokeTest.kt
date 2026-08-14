package dev.tonexotg.protocol

import app.cash.turbine.test
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Proves the test stack is actually wired: JUnit 5, coroutines-test and Turbine.
 *
 * This exists so S1 has a real, failing-if-broken acceptance check rather than an
 * assertion that the build merely configures. It is expected to be deleted once the
 * codec tests (S4/S5) carry that weight.
 */
class BuildSmokeTest {

    @Test
    fun `junit5 runs`() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun `coroutines-test and turbine are wired`() = runTest {
        flowOf(0x7E, 0x7D).test {
            assertEquals(0x7E, awaitItem())
            assertEquals(0x7D, awaitItem())
            awaitComplete()
        }
    }
}
