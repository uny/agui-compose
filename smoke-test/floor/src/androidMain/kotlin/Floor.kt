package dev.ynagai.agui.smoketest.floor

import dev.ynagai.agui.a2ui.A2uiCarrier
import dev.ynagai.agui.core.UiTranscriptReducer
import dev.ynagai.agui.model.TextPart

/**
 * One public symbol from each of the four modules the 24 floor is promised for.
 *
 * `checkAarMetadata` fails on resolution alone, so compiling is not what proves the floor -- but a
 * source set naming nothing gives AGP no reason to resolve the classpath at all, and the gate
 * would pass by doing nothing. `agui-agent` is reached through `AgentSession`'s own package rather
 * than its constructor, which needs an upstream agent to call.
 */
@Suppress("unused")
internal fun part(): TextPart = TextPart(id = "p", text = "hello", messageId = "m")

@Suppress("unused")
internal fun reducer(): UiTranscriptReducer = UiTranscriptReducer()

@Suppress("unused")
internal fun carrier(): A2uiCarrier? = null

@Suppress("unused")
internal fun session(): dev.ynagai.agui.agent.AgentSession? = null
