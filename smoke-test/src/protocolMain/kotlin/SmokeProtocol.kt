package dev.ynagai.agui.smoketest

import com.agui.client.agent.AbstractAgent
import dev.ynagai.agui.agent.AgentSession
import dev.ynagai.agui.core.UiTranscriptReducer

/**
 * Touches one public symbol from each of the two modules published on the upstream SDK's five
 * targets, `iosX64` included and the web excluded. See `Smoke.kt` for why a symbol and not just a
 * coordinate.
 *
 * One of these reaches across the module's own `api` scope on purpose. `AgentSession` takes
 * upstream's `AbstractAgent`; a consumer holds that type to call the constructor at all, and this
 * file holds it having resolved these coordinates and nothing else.
 */
@Suppress("unused")
internal fun reducer(): UiTranscriptReducer = UiTranscriptReducer()

@Suppress("unused")
internal fun session(agent: AbstractAgent): AgentSession = AgentSession(agent)
