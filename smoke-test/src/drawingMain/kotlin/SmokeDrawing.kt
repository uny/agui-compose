package dev.ynagai.agui.smoketest

import dev.ynagai.agui.compose.AguiTextRenderer
import dev.ynagai.agui.compose.PlainAguiTextRenderer
import dev.ynagai.agui.markdown.MarkdownAguiTextRenderer
import dev.ynagai.agui.material3.Material3AguiTextRenderer

/**
 * Touches one public symbol from each of the three drawing modules that name no upstream type, on
 * the six targets they publish: no `iosX64`, and both web backends. See `Smoke.kt` for why a
 * symbol and not just a coordinate.
 *
 * Nothing here is `@Composable`, and this build applies no Compose compiler plugin. The renderer
 * objects and the constructor below are plain Kotlin declarations whose *signatures* name Compose
 * types; naming the declarations is enough to require the class files, and it is the class files
 * that a wrongly published variant would be missing.
 */
@Suppress("unused")
internal fun textRenderers(): List<AguiTextRenderer> = listOf(
    PlainAguiTextRenderer,
    Material3AguiTextRenderer,
    MarkdownAguiTextRenderer(),
)
