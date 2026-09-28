package dev.ynagai.agui.smoketest

import dev.ynagai.agui.model.TextPart

/**
 * Touches one public symbol from `agui-model`, the one module published on all seven targets --
 * which is why this file is in `commonMain` and the other eight modules' counterparts are in the
 * group source sets `build.gradle.kts` describes.
 *
 * Resolution alone is not the whole property. An artifact can resolve and still be missing the
 * class -- a `.module` file can point a variant at a jar that does not carry it, and the metadata
 * module can resolve while a platform one does not. Compiling against a symbol from each module,
 * on every target, is what proves the published metadata leads somewhere real.
 */
@Suppress("unused")
internal fun part(): TextPart = TextPart(id = "p", text = "hello", messageId = "m")
