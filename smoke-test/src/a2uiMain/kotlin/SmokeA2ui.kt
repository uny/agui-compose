package dev.ynagai.agui.smoketest

import dev.ynagai.a2ui.core.protocol.CatalogDefinition
import dev.ynagai.agui.a2ui.A2uiRequest
import dev.ynagai.agui.a2ui.compose.A2uiPendingRenderer
import dev.ynagai.agui.a2ui.compose.BasicPending
import dev.ynagai.agui.a2ui.material3.Material3A2uiPending

/**
 * Touches one public symbol from each of the three A2UI modules, on the four targets they
 * publish: neither `iosX64` nor the web, because they reach both the upstream SDK and
 * `a2ui-compose`. See `Smoke.kt` for why a symbol and not just a coordinate, and `SmokeDrawing.kt`
 * for why no Compose compiler plugin is needed.
 *
 * `A2uiRequest` takes `a2ui-compose`'s `CatalogDefinition`, reached across `agui-a2ui`'s `api`
 * scope the way `SmokeProtocol.kt` reaches upstream's `AbstractAgent`.
 */
@Suppress("unused")
internal fun request(catalog: CatalogDefinition): A2uiRequest = A2uiRequest(catalog)

@Suppress("unused")
internal fun pendingRenderers(): List<A2uiPendingRenderer> = listOf(
    BasicPending,
    Material3A2uiPending,
)
