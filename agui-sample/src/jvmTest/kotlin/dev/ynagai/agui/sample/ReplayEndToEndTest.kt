package dev.ynagai.agui.sample

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.graphics.toAwtImage
import dev.ynagai.agui.replay.ReplayServer
import dev.ynagai.agui.replay.ReplayTrace
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * The whole sample, end to end, against upstream's recorded traffic over a real socket: the
 * replay server on a free port, `HttpAgent` on the other end, and the cards on screen.
 *
 * What a run against a live agent would show, minus the model -- which is the point. The three
 * hotels are the ones the dojo's `a2ui_advanced` agent generated the day the trace was recorded.
 */
@OptIn(ExperimentalTestApi::class)
class ReplayEndToEndTest {
    @Test
    fun the_recorded_hotel_comparison_draws_three_cards() = runComposeUiTest {
        val trace = ReplayTrace.resource("advanced-hotel-comparison")
        // A few milliseconds between events, so that the states between the first event and the
        // last -- the building skeleton, the progressive paints -- are on screen long enough to
        // be seen, rather than collapsed into one frame by a server faster than the compositor.
        val server = ReplayServer(traces = listOf(trace), port = 0, delayMillis = 5).start()
        try {
            val port = server.boundPort
            setContent { SampleApp(chat = SampleChat()) }

            onNodeWithText("AG-UI endpoint").performTextInput("http://localhost:$port/${trace.name}")
            onNodeWithText("Connect").performClick()
            onNodeWithText("Message").performTextInput("Compare three hotels")
            onNodeWithText("Send").performClick()

            // The middleware's `{"status": "building"}` activity, drawn by the Material 3 pending
            // renderer, before any card exists.
            waitUntil(timeoutMillis = 30_000) {
                onAllNodesWithText("Building UI").fetchSemanticsNodes().isNotEmpty()
            }
            waitUntil(timeoutMillis = 30_000) {
                onAllNodesWithText("Boutique Loft").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("The Ritz").assertExists()
            onNodeWithText("Holiday Inn").assertExists()
            onNodeWithText("Paris").assertExists()
            // The surface was painted once, by whichever carrier owns it -- not once per carrier.
            onAllNodesWithText("Boutique Loft").fetchSemanticsNodes().size.let { check(it == 1) { "$it copies" } }

            // A picture, for the humans: `agui-sample/build/replay-hotel-comparison.png`.
            System.getProperty("agui.sample.screenshot")?.let { path ->
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(path))
            }
        } finally {
            server.stop()
        }
    }
}
