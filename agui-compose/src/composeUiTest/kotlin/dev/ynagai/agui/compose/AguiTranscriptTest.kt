package dev.ynagai.agui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
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
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalTestApi::class)
class AguiTranscriptTest {

    @Test
    fun everyPartKindDrawsSomething() = runComposeUiTest {
        val transcript = UiTranscript(
            messages = listOf(
                UiMessage(
                    id = "m1",
                    role = UiRole.ASSISTANT,
                    parts = listOf(
                        ReasoningPart(id = "r1", text = "thinking-out-loud", messageId = "r1"),
                        TextPart(id = "t1", text = "spoken-answer", messageId = "t1"),
                        ToolCallPart(
                            id = "c1",
                            toolCallId = "c1",
                            name = "search_tool",
                            status = ToolCallStatus.AWAITING_RESULT,
                        ),
                        ActivityPart(
                            id = "a1",
                            messageId = "a1",
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

        setContent { AguiTranscript(transcript) }

        onNodeWithText("thinking-out-loud").assertIsDisplayed()
        onNodeWithText("spoken-answer").assertIsDisplayed()
        onNodeWithText("search_tool", substring = true).assertIsDisplayed()
        onNodeWithText("a2ui-surface").assertIsDisplayed()
        onNodeWithText("diagram.png").assertIsDisplayed()
    }

    /**
     * The seam a Markdown renderer arrives through. Both prose parts have to move, because the
     * point of one renderer serving both is that an application fits it once.
     */
    @Test
    fun replacingTheTextRendererRedrawsProseAndOnlyProse() = runComposeUiTest {
        val transcript = UiTranscript(
            messages = listOf(
                UiMessage(
                    id = "m1",
                    role = UiRole.ASSISTANT,
                    parts = listOf(
                        ReasoningPart(id = "r1", text = "reasoned", messageId = "r1"),
                        TextPart(id = "t1", text = "spoke", messageId = "t1"),
                        ToolCallPart(id = "c1", toolCallId = "c1", name = "untouched_tool"),
                    ),
                ),
            ),
        )

        setContent {
            CompositionLocalProvider(
                LocalAguiTextRenderer provides
                    AguiTextRenderer { text, _, modifier ->
                        PlainAguiTextRenderer.Render("rendered:$text", false, modifier)
                    },
            ) { AguiTranscript(transcript) }
        }

        onNodeWithText("rendered:reasoned").assertIsDisplayed()
        onNodeWithText("rendered:spoke").assertIsDisplayed()
        // The tool call does not draw through the prose renderer, so it is untouched.
        onNodeWithText("untouched_tool", substring = true).assertIsDisplayed()
    }

    /** The streaming flag has to reach the renderer: a Markdown parser needs it to hold back
     * half-typed syntax. */
    @Test
    fun theStreamingFlagReachesTheTextRenderer() = runComposeUiTest {
        val transcript = UiTranscript(
            messages = listOf(
                UiMessage(
                    id = "m1",
                    role = UiRole.ASSISTANT,
                    parts = listOf(
                        TextPart(id = "t1", text = "growing", messageId = "t1", streaming = true),
                        TextPart(id = "t2", text = "settled", messageId = "t2", streaming = false),
                    ),
                ),
            ),
        )

        setContent {
            CompositionLocalProvider(
                LocalAguiTextRenderer provides
                    AguiTextRenderer { text, streaming, modifier ->
                        PlainAguiTextRenderer.Render("$text:$streaming", false, modifier)
                    },
            ) { AguiTranscript(transcript) }
        }

        onNodeWithText("growing:true").assertIsDisplayed()
        onNodeWithText("settled:false").assertIsDisplayed()
    }

    /**
     * The message frame is a slot too, and the modifier a caller hands [AguiMessage] has to reach
     * it -- that is the whole of what `modifier` means to a Compose caller. A frame that dropped it
     * would leave `AguiMessage(message, Modifier.testTag(...))` silently doing nothing.
     */
    @Test
    fun theMessageFrameIsReplaceableAndReceivesTheModifier() = runComposeUiTest {
        val message = UiMessage(
            id = "m1",
            role = UiRole.ASSISTANT,
            parts = listOf(TextPart(id = "t1", text = "framed-prose", messageId = "t1")),
        )

        setContent {
            CompositionLocalProvider(
                LocalAguiComponents provides
                    AguiComponents().copy(
                        message = { msg, modifier, content ->
                            Column(modifier) {
                                PlainAguiTextRenderer.Render("role:" + msg.role, false, Modifier)
                                content()
                            }
                        },
                    ),
            ) { AguiMessage(message, Modifier.testTag("frame")) }
        }

        onNodeWithTag("frame").assertIsDisplayed()
        onNodeWithText("role:ASSISTANT").assertIsDisplayed()
        // The replaced frame still draws the parts it was handed.
        onNodeWithText("framed-prose").assertIsDisplayed()
    }

    /** A replaced slot wins over the default, and the slots it did not replace still draw. */
    @Test
    fun replacingOneSlotLeavesTheRest() = runComposeUiTest {
        val transcript = UiTranscript(
            messages = listOf(
                UiMessage(
                    id = "m1",
                    role = UiRole.ASSISTANT,
                    parts = listOf(
                        TextPart(id = "t1", text = "default-prose", messageId = "t1"),
                        ToolCallPart(id = "c1", toolCallId = "c1", name = "weather"),
                    ),
                ),
            ),
        )

        setContent {
            CompositionLocalProvider(
                LocalAguiComponents provides
                    AguiComponents().copy(
                        toolCall = { part, modifier ->
                            PlainAguiTextRenderer.Render("custom(${part.name})", false, modifier)
                        },
                    ),
            ) { AguiTranscript(transcript) }
        }

        onNodeWithText("custom(weather)").assertIsDisplayed()
        onNodeWithText("default-prose").assertIsDisplayed()
    }
}
