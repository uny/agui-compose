package dev.ynagai.agui.a2ui.material3

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ynagai.a2ui.compose.A2uiRenderer
import dev.ynagai.a2ui.compose.A2uiRendererConfig
import dev.ynagai.a2ui.compose.BasicCatalog
import dev.ynagai.agui.a2ui.AguiA2ui
import dev.ynagai.agui.a2ui.compose.rememberA2uiHost
import dev.ynagai.agui.compose.AguiTranscript
import dev.ynagai.agui.material3.Material3AguiComponents
import dev.ynagai.agui.material3.ProvideMaterial3Agui
import dev.ynagai.agui.model.ActivityPart
import dev.ynagai.agui.model.UiMessage
import dev.ynagai.agui.model.UiRole
import dev.ynagai.agui.model.UiTranscript
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class Material3A2uiTest {
    private fun activity(content: JsonElement) = UiTranscript(
        messages = listOf(
            UiMessage(
                id = "a",
                role = UiRole.ACTIVITY,
                parts = listOf(ActivityPart(id = "activity:a", messageId = "a", activityType = AguiA2ui.ACTIVITY_TYPE, content = content)),
            ),
        ),
    )

    @Test
    fun buildingThenABasicCatalogSurfaceThroughMaterial3() = runComposeUiTest {
        var transcript by mutableStateOf(activity(Json.parseToJsonElement("""{"status":"building"}""")))
        setContent {
            val renderer = remember { A2uiRenderer(A2uiRendererConfig.Default.withCatalogs(listOf(BasicCatalog.definition))) }
            val host = rememberA2uiHost(transcript, renderer)
            val components = remember(host) { Material3AguiComponents().withMaterial3A2ui(host) }
            ProvideMaterial3Agui(components = components) { AguiTranscript(transcript) }
        }
        onNodeWithText("Building UI").assertIsDisplayed()

        // Upstream's own spelling of the basic catalog, remapped to the one the renderer holds --
        // so `Column` and `Text` resolve, and the row of two cards below draws through the
        // Material 3 renderers rather than as placeholders.
        transcript = activity(
            Json.parseToJsonElement(
                """{"a2ui_operations":[
                    {"version":"v0.9","createSurface":{"surfaceId":"s","catalogId":"${AguiA2ui.UPSTREAM_BASIC_CATALOG_ID}"}},
                    {"version":"v0.9","updateComponents":{"surfaceId":"s","components":[
                        {"id":"root","component":"Column","children":["t1","t2"]},
                        {"id":"t1","component":"Text","text":"hello from A2UI"},
                        {"id":"t2","component":"Text","text":{"path":"/greeting"}}
                    ]}},
                    {"version":"v0.9","updateDataModel":{"surfaceId":"s","path":"/","value":{"greeting":"bound"}}}
                ]}""",
            ),
        )
        onNodeWithText("hello from A2UI").assertIsDisplayed()
        onNodeWithText("bound").assertIsDisplayed()
    }
}
