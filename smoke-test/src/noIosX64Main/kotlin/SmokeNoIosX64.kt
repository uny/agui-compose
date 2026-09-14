package dev.ynagai.agui.smoketest

import dev.ynagai.a2ui.core.protocol.CatalogDefinition
import dev.ynagai.agui.a2ui.A2uiRequest
import dev.ynagai.agui.a2ui.compose.A2uiPendingRenderer
import dev.ynagai.agui.a2ui.compose.BasicPending
import dev.ynagai.agui.a2ui.material3.Material3A2uiPending
import dev.ynagai.agui.compose.AguiTextRenderer
import dev.ynagai.agui.compose.PlainAguiTextRenderer
import dev.ynagai.agui.markdown.MarkdownAguiTextRenderer
import dev.ynagai.agui.material3.Material3AguiTextRenderer

/**
 * Touches one public symbol from each of the six modules that publish no `iosX64`, on the four
 * targets they do publish. See `Smoke.kt` for why a symbol and not just a coordinate.
 *
 * `A2uiRequest` takes `a2ui-compose`'s `CatalogDefinition`, reached across `agui-a2ui`'s `api`
 * scope the way `Smoke.kt` reaches upstream's `AbstractAgent`.
 *
 * Nothing here is `@Composable`, and this build applies no Compose compiler plugin. The renderer
 * objects and the constructors below are plain Kotlin declarations whose *signatures* name
 * Compose types; naming the declarations is enough to require the class files, and it is the
 * class files that a wrongly published variant would be missing.
 */
@Suppress("unused")
internal fun request(catalog: CatalogDefinition): A2uiRequest = A2uiRequest(catalog)

@Suppress("unused")
internal fun textRenderers(): List<AguiTextRenderer> = listOf(
    PlainAguiTextRenderer,
    Material3AguiTextRenderer,
    MarkdownAguiTextRenderer(),
)

@Suppress("unused")
internal fun pendingRenderers(): List<A2uiPendingRenderer> = listOf(
    BasicPending,
    Material3A2uiPending,
)
