package dev.ynagai.agui.material3

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** A frame width the alignment assertions can be stated against. */
private const val TRANSCRIPT_WIDTH = 400

/** The bubble's own `insideContainer` padding (12.dp), plus a little, so the trailing-edge
 *  assertion is about alignment rather than about the exact padding value. */
private const val PADDING_SLACK = 16

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
     *
     * `bubble.left > answer.left` alone does **not** say that. The bubble pads its content by
     * `insideContainer` and the assistant branch pads nothing horizontally, so that comparison
     * holds at 12.dp vs 0.dp however the row is arranged -- it stays green with
     * `Arrangement.Start`, with `fill = true`, and with the `fillMaxWidth` removed. Measured
     * against a known frame width instead: the bubble has to start past the middle of the row and
     * end at its trailing edge, which is false for every one of those three regressions.
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

        setContent {
            Material3TestSurface {
                AguiTranscript(transcript, modifier = Modifier.width(TRANSCRIPT_WIDTH.dp))
            }
        }

        val bubble = onNodeWithText("asked").getBoundsInRoot()
        val answer = onNodeWithText("answered").getBoundsInRoot()

        assertTrue(
            bubble.left > answer.left,
            "the user bubble should be pushed to the end of the row while the answer starts at " +
                "the leading edge: bubble text began at ${bubble.left}, answer at ${answer.left}",
        )
        assertTrue(
            bubble.left > (TRANSCRIPT_WIDTH / 2).dp,
            "a five-character message should sit in the trailing half of a " +
                "${TRANSCRIPT_WIDTH}.dp row, not stretch across it: bubble text began at " +
                "${bubble.left}",
        )
        assertTrue(
            bubble.right > (TRANSCRIPT_WIDTH - PADDING_SLACK).dp,
            "the bubble should end at the row's trailing edge: bubble text ended at " +
                "${bubble.right} in a ${TRANSCRIPT_WIDTH}.dp row",
        )
    }

    /**
     * The second local earns its place: prose drawn through this module reads the theme's type.
     *
     * This is the module's central claim -- `ProvideMaterial3Agui` provides a text renderer as well
     * as a slot table, because `agui-compose`'s default draws through `BasicText`, which has no
     * ambient text style. Nothing asserted it. Swapping `Material3AguiTextRenderer`'s `Text` for a
     * `BasicText`, or defaulting the parameter to `PlainAguiTextRenderer`, left every other test in
     * this suite green while prose silently ignored the theme.
     *
     * Both renderers draw the same string in one composition under a deliberately enormous
     * `bodyLarge`, so the comparison is about which one reads it and not about the words.
     */
    @Test
    fun theMaterialTextRendererReadsTheThemeTypeScaleAndThePlainOneDoesNot() = runComposeUiTest {
        val transcript = UiTranscript(
            messages = listOf(
                UiMessage(
                    id = "m1",
                    role = UiRole.ASSISTANT,
                    parts = listOf(TextPart(id = "t1", text = "sized-prose", messageId = "m1")),
                ),
            ),
        )

        setContent {
            MaterialTheme(typography = Typography(bodyLarge = TextStyle(fontSize = 40.sp))) {
                Column {
                    ProvideMaterial3Agui { AguiTranscript(transcript) }
                    ProvideMaterial3Agui(textRenderer = PlainAguiTextRenderer) {
                        AguiTranscript(transcript)
                    }
                }
            }
        }

        val material = onAllNodesWithText("sized-prose")[0].getBoundsInRoot()
        val plain = onAllNodesWithText("sized-prose")[1].getBoundsInRoot()
        assertTrue(
            material.bottom - material.top > plain.bottom - plain.top,
            "prose drawn through this module should pick up the theme's 40.sp body while the " +
                "plain renderer stays at BasicText's default, but they measured " +
                "${material.bottom - material.top} and ${plain.bottom - plain.top}",
        )
    }

    /**
     * System and developer messages are labelled, and drawn smaller than the conversation.
     *
     * Both halves were untestable from the outside before: inverting the label so a system message
     * read "Developer", or deleting the `ProvideTextStyle` that shrinks the body, left the suite
     * green. The size comparison pins the text style; the ambient content colour that dims them
     * alongside it is still unasserted, because a colour needs a screenshot to read.
     *
     * The labels are checked by their *order*, not merely by being present. Both words are on
     * screen either way round, so asserting each one exists survives the two being swapped -- the
     * system message is drawn first, so its label has to sit above the developer message's.
     */
    @Test
    fun systemAndDeveloperAreLabelledAndDrawnSmallerThanTheConversation() = runComposeUiTest {
        fun messageOf(role: UiRole) = UiMessage(
            id = "m-$role",
            role = role,
            parts = listOf(TextPart(id = "t-$role", text = "aside", messageId = "m-$role")),
        )

        setContent {
            Material3TestSurface {
                Column {
                    AguiMessage(messageOf(UiRole.ASSISTANT))
                    AguiMessage(messageOf(UiRole.SYSTEM))
                    AguiMessage(messageOf(UiRole.DEVELOPER))
                }
            }
        }

        val systemLabel = onNodeWithText(AguiStrings.SYSTEM).getBoundsInRoot()
        val developerLabel = onNodeWithText(AguiStrings.DEVELOPER).getBoundsInRoot()
        assertTrue(
            systemLabel.top < developerLabel.top,
            "the system message is drawn first, so its label belongs above the developer " +
                "message's: System at ${systemLabel.top}, Developer at ${developerLabel.top}",
        )

        val assistant = onAllNodesWithText("aside")[0].getBoundsInRoot()
        val system = onAllNodesWithText("aside")[1].getBoundsInRoot()
        assertTrue(
            system.bottom - system.top < assistant.bottom - assistant.top,
            "a system message should draw at bodySmall against the assistant's bodyLarge, but " +
                "they measured ${system.bottom - system.top} and " +
                "${assistant.bottom - assistant.top}",
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

    /**
     * And the finished state, which no other test reaches.
     *
     * `STREAMING_ARGUMENTS` and `AWAITING_RESULT` are both covered above; without this,
     * `AguiStrings.toolCallStatus` could report a completed call as still running with the whole
     * suite green.
     */
    @Test
    fun aCompletedToolCallSaysSo() = runComposeUiTest {
        val transcript = UiTranscript(
            messages = listOf(
                UiMessage(
                    id = "m1",
                    role = UiRole.ASSISTANT,
                    parts = listOf(
                        ToolCallPart(
                            id = "c1",
                            toolCallId = "c1",
                            name = "finished_tool",
                            status = ToolCallStatus.COMPLETE,
                            result = "3 hits",
                        ),
                    ),
                ),
            ),
        )

        setContent { Material3TestSurface { AguiTranscript(transcript) } }

        onNodeWithText("finished_tool").assertIsDisplayed()
        onNodeWithText("done").assertIsDisplayed()
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
