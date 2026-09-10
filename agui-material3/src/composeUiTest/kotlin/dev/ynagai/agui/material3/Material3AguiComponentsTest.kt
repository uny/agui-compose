package dev.ynagai.agui.material3

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ynagai.agui.compose.AguiMessage
import dev.ynagai.agui.compose.AguiTextRenderer
import dev.ynagai.agui.compose.AguiTranscript
import dev.ynagai.agui.compose.PlainAguiTextRenderer
import dev.ynagai.agui.model.ActivityPart
import dev.ynagai.agui.model.FilePart
import dev.ynagai.agui.model.ReasoningPart
import dev.ynagai.agui.model.TextPart
import dev.ynagai.agui.model.ToolCallPart
import dev.ynagai.agui.model.ToolCallStatus
import dev.ynagai.agui.model.UiMessage
import dev.ynagai.agui.model.UiRole
import dev.ynagai.agui.model.UiTranscript
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalTestApi::class)
class Material3AguiComponentsTest {

    @Test
    fun everyPartKindDrawsSomething() = runComposeUiTest {
        val transcript = UiTranscript(
            messages = listOf(
                UiMessage(
                    id = "m1",
                    role = UiRole.ASSISTANT,
                    parts = listOf(
                        // Streaming, so the disclosure is open and the text is on screen. The
                        // collapsed case is `Material3ReasoningTest`'s.
                        ReasoningPart(
                            id = "r1",
                            text = "thinking-out-loud",
                            messageId = "m1",
                            streaming = true,
                        ),
                        TextPart(id = "t1", text = "spoken-answer", messageId = "m1"),
                        ToolCallPart(
                            id = "c1",
                            toolCallId = "c1",
                            name = "search_tool",
                            status = ToolCallStatus.AWAITING_RESULT,
                        ),
                        ActivityPart(
                            id = "a1",
                            messageId = "m1",
                            activityType = "a2ui-surface",
                            content = JsonPrimitive("opaque"),
                        ),
                        FilePart(
                            id = "f1",
                            mimeType = "image/png",
                            url = "https://example.invalid/diagram.png",
                            filename = "diagram.png",
                        ),
                    ),
                ),
            ),
        )

        setContent { Material3TestSurface { AguiTranscript(transcript) } }

        onNodeWithText("thinking-out-loud").assertIsDisplayed()
        onNodeWithText("spoken-answer").assertIsDisplayed()
        onNodeWithText("search_tool").assertIsDisplayed()
        onNodeWithText("running").assertIsDisplayed()
        onNodeWithText("a2ui-surface").assertIsDisplayed()
        onNodeWithText("diagram.png").assertIsDisplayed()
    }

    /**
     * The seam, still open one layer up.
     *
     * This module's whole argument for not calling `Text` in its prose slots is that an application
     * can fit a Markdown renderer and keep every Material 3 frame around it. The assertion is that
     * both prose kinds move and nothing else does -- the tool call is drawn by this module and has
     * to be untouched by a renderer swap.
     */
    @Test
    fun aReplacedTextRendererStillDrawsInsideTheMaterialFrames() = runComposeUiTest {
        val transcript = UiTranscript(
            messages = listOf(
                UiMessage(
                    id = "m1",
                    role = UiRole.ASSISTANT,
                    parts = listOf(
                        ReasoningPart(
                            id = "r1",
                            text = "reasoned",
                            messageId = "m1",
                            streaming = true,
                        ),
                        TextPart(id = "t1", text = "spoke", messageId = "m1"),
                        ToolCallPart(id = "c1", toolCallId = "c1", name = "untouched_tool"),
                    ),
                ),
            ),
        )

        setContent {
            MaterialTheme {
                ProvideMaterial3Agui(
                    textRenderer = AguiTextRenderer { text, _, modifier ->
                        PlainAguiTextRenderer.Render("rendered:$text", false, modifier)
                    },
                ) {
                    AguiTranscript(transcript)
                }
            }
        }

        onNodeWithText("rendered:reasoned").assertIsDisplayed()
        onNodeWithText("rendered:spoke").assertIsDisplayed()
        // Drawn by this module rather than through the renderer, and still framed by it: the
        // status word exists only because `Material3ToolCall` drew it.
        onNodeWithText("untouched_tool").assertIsDisplayed()
        onNodeWithText("running").assertIsDisplayed()
    }

    /**
     * `agui-compose`'s `AguiMessage` promises the caller's modifier reaches the frame's root, and
     * every branch of the Material 3 frame has to keep that promise -- the user branch nests a
     * `Surface` inside a `Row`, which is exactly the shape that loses a modifier by accident.
     */
    @Test
    fun theModifierReachesTheFrameRootForEveryRole() = runComposeUiTest {
        // One composition rather than one per role: `setContent` may be called once per test, so a
        // loop around it fails on the second iteration rather than on an assertion.
        setContent {
            Material3TestSurface {
                Column {
                    for (role in UiRole.entries) {
                        AguiMessage(
                            message = UiMessage(
                                id = "m-$role",
                                role = role,
                                parts = listOf(
                                    TextPart(
                                        id = "t-$role",
                                        text = "framed-$role",
                                        messageId = "m-$role",
                                    ),
                                ),
                            ),
                            modifier = Modifier.testTag("frame-$role"),
                        )
                    }
                }
            }
        }

        for (role in UiRole.entries) {
            onNodeWithTag("frame-$role").assertIsDisplayed()
            onNodeWithText("framed-$role").assertIsDisplayed()
        }
    }

    /**
     * A user message is an end-aligned bubble; an assistant message starts at the leading edge.
     *
     * Asserted on where the two runs of text *land* rather than on colours or shapes, because the
     * alignment is what a reader sees first and what a layout mistake destroys: a `Surface` that
     * filled its row would still be tonally a bubble and would look nothing like one. The two
     * messages carry the same string so that the comparison is about the frames and not about how
     * wide the words are.
     */
    @Test
    fun aUserMessageIsEndAlignedAndAnAssistantMessageIsNot() = runComposeUiTest {
        val transcript = UiTranscript(
            messages = listOf(
                UiMessage(
                    id = "u1",
                    role = UiRole.USER,
                    parts = listOf(TextPart(id = "t1", text = "asked", messageId = "u1")),
                ),
                UiMessage(
                    id = "a1",
                    role = UiRole.ASSISTANT,
                    parts = listOf(TextPart(id = "t2", text = "answered", messageId = "a1")),
                ),
            ),
        )

        setContent { Material3TestSurface { AguiTranscript(transcript) } }

        val bubble = onNodeWithText("asked").getBoundsInRoot()
        val answer = onNodeWithText("answered").getBoundsInRoot()
        assertTrue(
            bubble.left > answer.left,
            "the user bubble should be pushed to the end of the row while the answer starts at " +
                "the leading edge: bubble text began at ${bubble.left}, answer at ${answer.left}",
        )
    }

    /** The failure state has to be visible without reading an enum name. */
    @Test
    fun aFailedToolCallSaysSo() = runComposeUiTest {
        val transcript = UiTranscript(
            messages = listOf(
                UiMessage(
                    id = "m1",
                    role = UiRole.ASSISTANT,
                    parts = listOf(
                        ToolCallPart(
                            id = "c1",
                            toolCallId = "c1",
                            name = "broken_tool",
                            status = ToolCallStatus.FAILED,
                            error = "boom",
                        ),
                    ),
                ),
            ),
        )

        setContent { Material3TestSurface { AguiTranscript(transcript) } }

        onNodeWithText("broken_tool").assertIsDisplayed()
        onNodeWithText("failed").assertIsDisplayed()
    }
}

/**
 * A `MaterialTheme` and both of this module's locals: the arrangement every test here needs, and
 * the one the KDoc tells a consumer to use.
 */
@Composable
internal fun Material3TestSurface(content: @Composable () -> Unit) {
    MaterialTheme {
        ProvideMaterial3Agui(content = content)
    }
}
