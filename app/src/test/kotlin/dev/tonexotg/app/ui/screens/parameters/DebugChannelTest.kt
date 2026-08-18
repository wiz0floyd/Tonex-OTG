package dev.tonexotg.app.ui.screens.parameters

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DebugChannelTest {
    @Test
    fun `debug conflated channel with backgroundScope`() = runTest {
        val received = mutableListOf<Int>()
        val ch = Channel<Int>(Channel.CONFLATED)
        val job = backgroundScope.launch {
            println("worker started")
            for (v in ch) {
                received.add(v)
                println("received $v")
            }
        }
        println("job = $job, isActive=${job.isActive}")
        ch.trySend(1)
        ch.trySend(2)
        ch.trySend(3)
        advanceUntilIdle()
        println("final received = $received, job.isActive=${job.isActive}, isCompleted=${job.isCompleted}, isCancelled=${job.isCancelled}")
    }
}
