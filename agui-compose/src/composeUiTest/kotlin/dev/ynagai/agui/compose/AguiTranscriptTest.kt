package dev.ynagai.agui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.rememberLazyListState
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
import kotlin.test.assertEquals
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
        onNodeWithText("search_tool (AWAITING_RESULT)").assertIsDisplayed()
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
        // The tool call does not draw through the prose renderer, so it is untouched. Asserting
        // only that "untouched_tool" is on screen would not say that: were the tool-call slot
        // routed through the renderer it would draw "rendered:untouched_tool (...)", which
        // contains that substring too. The absence is the half that carries the claim.
        onNodeWithText("untouched_tool (STREAMING_ARGUMENTS)").assertIsDisplayed()
        onNodeWithText("rendered:untouched_tool", substring = true).assertDoesNotExist()
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
                        TextPart(id = "t1", text = "growing", messageId = "m1", streaming = true),
                        TextPart(id = "t2", text = "settled", messageId = "m1", streaming = false),
                        // Both prose parts draw through the one renderer, so both have to carry
                        // the flag: a parser handed `false` on a half-arrived reasoning run
                        // commits to fence syntax that is not finished being typed.
                        ReasoningPart(id = "r1", text = "musing", messageId = "m1", streaming = true),
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
        onNodeWithText("musing:true").assertIsDisplayed()
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
    /**
     * Several messages, which is the shape everything about [AguiTranscript] is built for and the
     * only shape in which its keying has observable behaviour at all.
     *
     * `agui-core` guarantees the ids are distinct; this asserts the renderer draws one item per
     * message and does not collapse, reorder or drop any of them.
     */
    @Test
    fun everyMessageInATranscriptDraws() = runComposeUiTest {
        val transcript = UiTranscript(
            messages = listOf(
                UiMessage(
                    id = "u1",
                    role = UiRole.USER,
                    parts = listOf(TextPart(id = "t1", text = "asked-a-question", messageId = "u1")),
                ),
                UiMessage(
                    id = "a1",
                    role = UiRole.ASSISTANT,
                    parts = listOf(TextPart(id = "t2", text = "gave-an-answer", messageId = "a1")),
                ),
                UiMessage(
                    id = "a2",
                    role = UiRole.ASSISTANT,
                    parts = listOf(TextPart(id = "t3", text = "and-a-follow-up", messageId = "a2")),
                ),
            ),
        )

        setContent { AguiTranscript(transcript) }

        onNodeWithText("asked-a-question").assertIsDisplayed()
        onNodeWithText("gave-an-answer").assertIsDisplayed()
        onNodeWithText("and-a-follow-up").assertIsDisplayed()
    }

    /**
     * Both hoisted parameters, which nothing else here passes.
     *
     * The `state` is the one the KDoc says a caller keeps in order to follow a streaming response,
     * so a [LazyColumn][androidx.compose.foundation.lazy.LazyColumn] that quietly remembered its
     * own would leave that caller driving a list nobody is watching. The `modifier` is checked the
     * way any Compose caller would: it has to reach the root this composable makes.
     */
    @Test
    fun theTranscriptUsesTheCallersModifierAndState() = runComposeUiTest {
        val transcript = UiTranscript(
            messages = (1..3).map { i ->
                UiMessage(
                    id = "m$i",
                    role = UiRole.ASSISTANT,
                    parts = listOf(TextPart(id = "t$i", text = "part-$i", messageId = "m$i")),
                )
            },
        )
        lateinit var state: androidx.compose.foundation.lazy.LazyListState

        setContent {
            state = rememberLazyListState()
            AguiTranscript(transcript, Modifier.testTag("transcript"), state)
        }

        onNodeWithTag("transcript").assertIsDisplayed()
        assertEquals(3, state.layoutInfo.totalItemsCount)
    }

    /**
     * The *default* message frame, which the replaceable-frame test above cannot speak for: it
     * provides a frame of its own before asserting, so it proves only that the test's lambda uses
     * the modifier it was handed.
     */
    @Test
    fun theDefaultMessageFrameReceivesTheModifier() = runComposeUiTest {
        val message = UiMessage(
            id = "m1",
            role = UiRole.ASSISTANT,
            parts = listOf(TextPart(id = "t1", text = "default-framed", messageId = "m1")),
        )

        setContent { AguiMessage(message, Modifier.testTag("default-frame")) }

        onNodeWithTag("default-frame").assertIsDisplayed()
        onNodeWithText("default-framed").assertIsDisplayed()
    }

    /**
     * The documented fallback in the default `file` slot: the protocol carries no name for every
     * attachment, and the slot promises the MIME type in its place.
     */
    @Test
    fun aFileWithNoNameFallsBackToItsMimeType() = runComposeUiTest {
        val transcript = UiTranscript(
            messages = listOf(
                UiMessage(
                    id = "m1",
                    role = UiRole.ASSISTANT,
                    parts = listOf(
                        FilePart(id = "f1", mimeType = "application/pdf", url = "https://example.invalid/a"),
                        FilePart(
                            id = "f2",
                            mimeType = "image/png",
                            url = "https://example.invalid/b",
                            filename = "named.png",
                        ),
                    ),
                ),
            ),
        )

        setContent { AguiTranscript(transcript) }

        onNodeWithText("application/pdf").assertIsDisplayed()
        // A name, when there is one, still wins over the type.
        onNodeWithText("named.png").assertIsDisplayed()
        onNodeWithText("image/png").assertDoesNotExist()
    }
}
