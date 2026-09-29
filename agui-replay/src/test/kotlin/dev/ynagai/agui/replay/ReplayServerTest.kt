package dev.ynagai.agui.replay

import kotlin.test.Test
import kotlin.test.assertFailsWith

class ReplayServerTest {
    @Test
    fun `two traces with one name are refused rather than one replacing the other`() {
        val trace = ReplayTrace.parse("a", "[]")
        assertFailsWith<IllegalArgumentException> { ReplayServer(traces = listOf(trace, ReplayTrace.parse("a", "[]"))) }
        ReplayServer(traces = listOf(trace, ReplayTrace.parse("b", "[]")))
    }
}
