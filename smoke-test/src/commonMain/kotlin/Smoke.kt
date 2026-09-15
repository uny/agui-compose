package dev.ynagai.agui.smoketest

import com.agui.client.agent.AbstractAgent
import dev.ynagai.agui.agent.AgentSession
import dev.ynagai.agui.core.UiTranscriptReducer
import dev.ynagai.agui.model.TextPart

/**
 * Touches one public symbol from each of the three modules published on all five targets,
 * `iosX64` included -- which is why this file is in `commonMain` and the other six modules'
 * counterpart is in `noIosX64Main`.
 *
 * Resolution alone is not the whole property. An artifact can resolve and still be missing the
 * class -- a `.module` file can point a variant at a jar that does not carry it, and the metadata
 * module can resolve while a platform one does not. Compiling against a symbol from each module,
 * on every target, is what proves the published metadata leads somewhere real.
 *
 * One of these reaches across the module's own `api` scope on purpose. `AgentSession` takes
 * upstream's `AbstractAgent`; a consumer holds that type to call the constructor at all, and this
 * file holds it having resolved these coordinates and nothing else.
 */
@Suppress("unused")
internal fun part(): TextPart = TextPart(id = "p", text = "hello", messageId = "m")

@Suppress("unused")
internal fun reducer(): UiTranscriptReducer = UiTranscriptReducer()

@Suppress("unused")
internal fun session(agent: AbstractAgent): AgentSession = AgentSession(agent)
