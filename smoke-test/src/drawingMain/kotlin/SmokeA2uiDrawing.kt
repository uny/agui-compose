package dev.ynagai.agui.smoketest

import dev.ynagai.agui.a2ui.A2uiTranslation
import dev.ynagai.agui.a2ui.compose.A2uiPendingRenderer
import dev.ynagai.agui.a2ui.compose.BasicPending
import dev.ynagai.agui.a2ui.material3.Material3A2uiPending

/**
 * Touches one public symbol from each of the three A2UI modules, on the six targets the drawing
 * modules share: no `iosX64`, and both web backends. What `agui-a2ui` publishes for every one of
 * them is the translation and the surfaces; its three native-only declarations are in
 * `SmokeA2ui.kt`. See `Smoke.kt` for why a symbol and not just a coordinate, and `SmokeDrawing.kt`
 * for why no Compose compiler plugin is needed.
 */
@Suppress("unused")
internal fun translation(): A2uiTranslation = A2uiTranslation.Default

@Suppress("unused")
internal fun pendingRenderers(): List<A2uiPendingRenderer> = listOf(
    BasicPending,
    Material3A2uiPending,
)
