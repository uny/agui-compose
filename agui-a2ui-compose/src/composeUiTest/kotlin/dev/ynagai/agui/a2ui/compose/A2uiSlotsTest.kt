package dev.ynagai.agui.a2ui.compose

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ynagai.a2ui.compose.A2uiRenderer
import dev.ynagai.a2ui.compose.ComponentRegistry
import dev.ynagai.a2ui.compose.ComponentRenderer
import dev.ynagai.a2ui.compose.rememberString
import dev.ynagai.agui.a2ui.A2uiTranslation
import dev.ynagai.agui.a2ui.AguiA2ui
import dev.ynagai.agui.compose.AguiComponents
import dev.ynagai.agui.compose.AguiTranscript
import dev.ynagai.agui.compose.LocalAguiComponents
import dev.ynagai.agui.model.ActivityPart
import dev.ynagai.agui.model.ToolCallPart
import dev.ynagai.agui.model.ToolCallStatus
import dev.ynagai.agui.model.UiMessage
import dev.ynagai.agui.model.UiRole
import dev.ynagai.agui.model.UiTranscript
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class A2uiSlotsTest {
    /** A registry that draws a `Text` component's `text` and nothing else. */
    private val registry = ComponentRegistry(
        mapOf(
            "Text" to ComponentRenderer { scope, modifier ->
                BasicText(scope.rememberString("text") ?: "", modifier)
            },
        ),
    )

    private fun operations(surfaceId: String, text: String) = Json.parseToJsonElement(
        """{"a2ui_operations":[
            {"version":"v0.9","createSurface":{"surfaceId":"$surfaceId","catalogId":"${AguiA2ui.UPSTREAM_BASIC_CATALOG_ID}"}},
            {"version":"v0.9","updateComponents":{"surfaceId":"$surfaceId","components":[{"id":"root","component":"Text","text":"$text"}]}}
        ]}""",
    )

    private fun activity(content: kotlinx.serialization.json.JsonElement) = UiMessage(
        id = "a",
        role = UiRole.ACTIVITY,
        parts = listOf(ActivityPart(id = "activity:a", messageId = "a", activityType = AguiA2ui.ACTIVITY_TYPE, content = content)),
    )

    @Test
    fun anActivityDrawsItsSurfaceAndRedrawsOnTheNextSnapshot() = runComposeUiTest {
        var transcript by mutableStateOf(UiTranscript(messages = listOf(activity(operations("s", "first")))))
        setContent {
            val renderer = remember { A2uiRenderer() }
            val host = rememberA2uiHost(transcript, renderer)
            val components = remember(host) { AguiComponents().withA2ui(host, registry) }
            CompositionLocalProvider(LocalAguiComponents provides components) {
                AguiTranscript(transcript)
            }
        }
        onNodeWithText("first").assertIsDisplayed()

        transcript = UiTranscript(messages = listOf(activity(operations("s", "second"))))
        onNodeWithText("second").assertIsDisplayed()
    }

    @Test
    fun theLifecycleDrawsAsPendingAndTheSurfaceReplacesItInPlace() = runComposeUiTest {
        var transcript by mutableStateOf(
            UiTranscript(messages = listOf(activity(Json.parseToJsonElement("""{"status":"building"}""")))),
        )
        setContent {
            val renderer = remember { A2uiRenderer() }
            val host = rememberA2uiHost(transcript, renderer)
            val components = remember(host) { AguiComponents().withA2ui(host, registry) }
            CompositionLocalProvider(LocalAguiComponents provides components) {
                AguiTranscript(transcript)
            }
        }
        onNodeWithText("building").assertIsDisplayed()

        transcript = UiTranscript(messages = listOf(activity(operations("s", "painted"))))
        onNodeWithText("painted").assertIsDisplayed()
    }

    @Test
    fun aRenderCallShadowedByTheActivityDrawsNothingAndAnotherToolCallKeepsItsSlot() = runComposeUiTest {
        val ops = operations("s", "surface")
        val render = ToolCallPart(
            id = "tool:r",
            toolCallId = "r",
            name = AguiA2ui.RENDER_TOOL_NAME,
            arguments = """{"surfaceId":"s","components":[{"id":"root","component":"Text","text":"surface"}]}""",
            parsedArguments = Json.parseToJsonElement("""{"surfaceId":"s","components":[{"id":"root","component":"Text","text":"surface"}]}"""),
            status = ToolCallStatus.AWAITING_RESULT,
        )
        val outer = ToolCallPart(
            id = "tool:g",
            toolCallId = "g",
            name = "generate_a2ui",
            status = ToolCallStatus.COMPLETE,
            result = ops.toString(),
        )
        val transcript = UiTranscript(
            messages = listOf(
                UiMessage(id = "m", role = UiRole.ASSISTANT, parts = listOf(outer, render)),
                activity(ops),
            ),
        )
        val drawnToolCalls = mutableListOf<String>()
        setContent {
            val renderer = remember { A2uiRenderer() }
            val host = rememberA2uiHost(transcript, renderer)
            val components = remember(host) {
                AguiComponents(toolCall = { part, modifier -> drawnToolCalls += part.name; BasicText(part.name, modifier) })
                    .withA2ui(host, registry)
            }
            CompositionLocalProvider(LocalAguiComponents provides components) {
                AguiTranscript(transcript)
            }
        }
        onNodeWithText("surface").assertIsDisplayed()
        onNodeWithText("generate_a2ui").assertIsDisplayed()
        assertEquals(emptyList(), drawnToolCalls.filter { it == AguiA2ui.RENDER_TOOL_NAME })
    }

    @Test
    fun aBatchTheRendererRefusesDrawsAsMalformedUntilThePayloadMovesOn() = runComposeUiTest {
        // A data-model path that is not a JSON Pointer: a2ui-core throws on it, atomically.
        val refused = Json.parseToJsonElement(
            """{"a2ui_operations":[
                {"version":"v0.9","createSurface":{"surfaceId":"s","catalogId":"${AguiA2ui.UPSTREAM_BASIC_CATALOG_ID}"}},
                {"version":"v0.9","updateDataModel":{"surfaceId":"s","path":"noslash","value":1}}
            ]}""",
        )
        var transcript by mutableStateOf(UiTranscript(messages = listOf(activity(refused))))
        val warnings = mutableListOf<String>()
        setContent {
            val renderer = remember { A2uiRenderer() }
            // Built inline on purpose: a fresh lambda every recomposition must not mean a fresh host.
            val host = rememberA2uiHost(transcript, renderer, onWarning = { warnings += it })
            val components = remember(host) { AguiComponents().withA2ui(host, registry) }
            CompositionLocalProvider(LocalAguiComponents provides components) {
                AguiTranscript(transcript)
            }
        }
        onNodeWithText("malformed", substring = true).assertIsDisplayed()
        assertEquals(1, warnings.size, "$warnings")

        // The middleware tries again under the same messageId: its state, not the stale error.
        transcript = UiTranscript(messages = listOf(activity(Json.parseToJsonElement("""{"status":"retrying"}"""))))
        onNodeWithText("retrying").assertIsDisplayed()

        // And the retry lands: the surface, with nothing refused any more.
        transcript = UiTranscript(messages = listOf(activity(operations("s", "painted"))))
        onNodeWithText("painted").assertIsDisplayed()
        assertEquals(1, warnings.size, "the refused batch was not replayed: $warnings")

        // A refusal, then a payload that names no surface: the surface goes, and so does the error.
        transcript = UiTranscript(messages = listOf(activity(refused)))
        onNodeWithText("malformed", substring = true).assertIsDisplayed()
        transcript = UiTranscript(messages = listOf(activity(Json.parseToJsonElement("""{"a2ui_operations":[]}"""))))
        onNodeWithText("malformed", substring = true).assertDoesNotExist()
        onNodeWithText("painted").assertDoesNotExist()
    }

    @Test
    fun aTranslationBuiltInlineKeepsTheHostAndItsSurfaces() = runComposeUiTest {
        var transcript by mutableStateOf(UiTranscript(messages = listOf(activity(operations("s", "first")))))
        val warnings = mutableListOf<String>()
        setContent {
            val renderer = remember { A2uiRenderer() }
            // `A2uiTranslation` compares by identity; a new one per composition must not start
            // the books over against a renderer that still holds the surface.
            val host = rememberA2uiHost(transcript, renderer, translation = A2uiTranslation(), onWarning = { warnings += it })
            val components = remember(host) { AguiComponents().withA2ui(host, registry) }
            CompositionLocalProvider(LocalAguiComponents provides components) {
                AguiTranscript(transcript)
            }
        }
        onNodeWithText("first").assertIsDisplayed()
        transcript = UiTranscript(messages = listOf(activity(operations("s", "second"))))
        onNodeWithText("second").assertIsDisplayed()
        assertEquals(emptyList(), warnings)
    }
}
