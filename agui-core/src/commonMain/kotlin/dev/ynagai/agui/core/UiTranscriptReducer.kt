package dev.ynagai.agui.core

import com.agui.core.types.ActivityDeltaEvent
import com.agui.core.types.ActivityMessage
import com.agui.core.types.ActivitySnapshotEvent
import com.agui.core.types.AssistantMessage
import com.agui.core.types.BaseEvent
import com.agui.core.types.BinaryInputContent
import com.agui.core.types.CustomEvent
import com.agui.core.types.DeveloperMessage
import com.agui.core.types.Message
import com.agui.core.types.MessagesSnapshotEvent
import com.agui.core.types.RawEvent
import com.agui.core.types.ReasoningEncryptedValueEvent
import com.agui.core.types.ReasoningEndEvent
import com.agui.core.types.ReasoningMessageChunkEvent
import com.agui.core.types.ReasoningMessageContentEvent
import com.agui.core.types.ReasoningMessageEndEvent
import com.agui.core.types.ReasoningMessageStartEvent
import com.agui.core.types.ReasoningStartEvent
import com.agui.core.types.Role
import com.agui.core.types.RunErrorEvent
import com.agui.core.types.RunFinishedEvent
import com.agui.core.types.RunFinishedInterruptOutcome
import com.agui.core.types.RunStartedEvent
import com.agui.core.types.StateDeltaEvent
import com.agui.core.types.StateSnapshotEvent
import com.agui.core.types.StepFinishedEvent
import com.agui.core.types.StepStartedEvent
import com.agui.core.types.SystemMessage
import com.agui.core.types.TextInputContent
import com.agui.core.types.TextMessageChunkEvent
import com.agui.core.types.TextMessageContentEvent
import com.agui.core.types.TextMessageEndEvent
import com.agui.core.types.TextMessageStartEvent
import com.agui.core.types.ThinkingEndEvent
import com.agui.core.types.ThinkingStartEvent
import com.agui.core.types.ThinkingTextMessageContentEvent
import com.agui.core.types.ThinkingTextMessageEndEvent
import com.agui.core.types.ThinkingTextMessageStartEvent
import com.agui.core.types.ToolCallArgsEvent
import com.agui.core.types.ToolCallChunkEvent
import com.agui.core.types.ToolCallEndEvent
import com.agui.core.types.ToolCallResultEvent
import com.agui.core.types.ToolCallStartEvent
import com.agui.core.types.ToolMessage
import com.agui.core.types.UserMessage
import dev.ynagai.agui.model.ActivityPart
import dev.ynagai.agui.model.EncryptedReasoningValue
import dev.ynagai.agui.model.FilePart
import dev.ynagai.agui.model.ReasoningPart
import dev.ynagai.agui.model.RunState
import dev.ynagai.agui.model.TextPart
import dev.ynagai.agui.model.ToolCallPart
import dev.ynagai.agui.model.ToolCallStatus
import dev.ynagai.agui.model.UiRole
import dev.ynagai.agui.model.UiTranscript
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Folds an AG-UI event stream into a [UiTranscript].
 *
 * Reads the events rather than the upstream SDK's `AgentState`, and that is a deliberate
 * substitution rather than a duplication. `AgentState` is a *transcript* -- an assistant message
 * with one `content` string and a separate `toolCalls` list, and reasoning in a sibling channel
 * keyed by its own message id -- and the relative order of a sentence and the tool call that
 * followed it is not recoverable from it. Ordering is the property this whole model is for, and
 * the event stream is the only place it survives. Everything else this library takes from
 * upstream: the event types are upstream's, and so are the transport, the SSE parser and the event
 * verifier that produce them. See `docs/decisions/0001-riding-on-the-upstream-kotlin-sdk.md`.
 *
 * Not thread-safe. Feed it from one coroutine -- [foldToTranscript] does.
 *
 * @param onWarning called for the two failures the specification requires a consumer to survive
 *   rather than fail a run on: an `ACTIVITY_DELTA` naming a message that does not exist, and a
 *   well-formed patch that does not apply. Both are repaired by the producer's next snapshot.
 */
public class UiTranscriptReducer(
    private val onWarning: (String) -> Unit = {},
) {
    private val messages = mutableListOf<MessageBuilder>()

    /**
     * The assistant turn currently being built, if any.
     *
     * A turn rather than a protocol message: text, reasoning and tool calls arrive under three
     * unrelated id spaces, so grouping by message id would scatter one visible answer across three
     * bubbles. The turn opens on the first assistant-produced event and closes when the run ends
     * or a user message interrupts it.
     */
    private var openAssistant: MessageBuilder? = null

    private val textParts = mutableMapOf<String, PartRef>()
    private val reasoningParts = mutableMapOf<String, PartRef>()
    private val toolParts = mutableMapOf<String, PartRef>()
    private val activityMessages = mutableMapOf<String, MessageBuilder>()

    private var lastTextMessageId: String? = null
    private var lastToolCallId: String? = null
    private var lastReasoningMessageId: String? = null

    private var run: RunState = RunState.Idle
    private var sharedState: JsonElement = JsonObject(emptyMap())
    private val steps = mutableListOf<String>()

    /** The transcript as of the last [accept]. */
    public var transcript: UiTranscript = UiTranscript()
        private set

    /** Applies one event and returns the transcript it produced. */
    public fun accept(event: BaseEvent): UiTranscript {
        apply(event)
        transcript = UiTranscript(
            messages = messages.map { it.build() },
            run = run,
            sharedState = sharedState,
            steps = steps.toList(),
        )
        return transcript
    }

    // The THINKING_* branches below are deliberate. Upstream deprecated those events in favour of
    // REASONING_*, but a server that has not moved yet still puts them on the wire, and a client
    // that stopped reading them would show an agent that appears not to reason at all.
    @Suppress("DEPRECATION")
    private fun apply(event: BaseEvent) {
        when (event) {
            // ---- Run lifecycle -------------------------------------------------------------

            is RunStartedEvent -> {
                finalizeTurn()
                run = RunState.Running(threadId = event.threadId, runId = event.runId)
            }

            is RunFinishedEvent -> {
                finalizeTurn()
                run = RunState.Finished(
                    threadId = event.threadId,
                    runId = event.runId,
                    result = event.result,
                    interrupted = event.outcome is RunFinishedInterruptOutcome,
                )
            }

            is RunErrorEvent -> {
                finalizeTurn(reason = event.message)
                run = RunState.Failed(message = event.message, code = event.code)
            }

            is StepStartedEvent -> steps += event.stepName
            is StepFinishedEvent -> steps.remove(event.stepName)

            // ---- Text ----------------------------------------------------------------------

            is TextMessageStartEvent -> openText(event.messageId, event.role)
            is TextMessageContentEvent -> appendText(event.messageId, event.delta)
            is TextMessageEndEvent -> endText(event.messageId)

            // A chunk is start, content and -- implicitly -- end in one event. Its id may be
            // absent, which the protocol reads as "the stream already running".
            is TextMessageChunkEvent -> {
                val messageId = event.messageId ?: lastTextMessageId
                if (messageId == null) {
                    onWarning("TEXT_MESSAGE_CHUNK with no messageId and no open text stream; skipped.")
                } else {
                    if (messageId !in textParts) openText(messageId, event.role ?: Role.ASSISTANT)
                    event.delta?.let { appendText(messageId, it) }
                }
            }

            // ---- Tool calls ----------------------------------------------------------------

            is ToolCallStartEvent -> openToolCall(
                toolCallId = event.toolCallId,
                name = event.toolCallName,
                parentMessageId = event.parentMessageId,
            )

            is ToolCallArgsEvent -> appendToolArgs(event.toolCallId, event.delta)
            is ToolCallEndEvent -> endToolCall(event.toolCallId)

            is ToolCallResultEvent -> {
                val ref = toolParts[event.toolCallId]
                if (ref == null) {
                    onWarning("TOOL_CALL_RESULT for unknown toolCallId ${event.toolCallId}; skipped.")
                } else {
                    val part = ref.part<ToolCallPart>()
                    ref.message.replace(
                        ref.index,
                        part.copy(result = event.content, status = ToolCallStatus.COMPLETE),
                    )
                }
            }

            is ToolCallChunkEvent -> {
                val toolCallId = event.toolCallId ?: lastToolCallId
                if (toolCallId == null) {
                    onWarning("TOOL_CALL_CHUNK with no toolCallId and no open tool call; skipped.")
                } else {
                    if (toolCallId !in toolParts) {
                        openToolCall(
                            toolCallId = toolCallId,
                            name = event.toolCallName.orEmpty(),
                            parentMessageId = event.parentMessageId,
                        )
                    }
                    event.delta?.let { appendToolArgs(toolCallId, it) }
                }
            }

            // ---- Reasoning -----------------------------------------------------------------

            is ReasoningStartEvent -> openReasoning(event.messageId, title = null)
            is ReasoningMessageStartEvent -> openReasoning(event.messageId, title = null)
            is ReasoningMessageContentEvent -> appendReasoning(event.messageId, event.delta)

            // Per-message end only. The stream stays open until REASONING_END, because a single
            // reasoning stream may span several reasoning messages.
            is ReasoningMessageEndEvent -> Unit

            is ReasoningMessageChunkEvent -> {
                val messageId = event.messageId ?: lastReasoningMessageId
                if (messageId == null) {
                    onWarning("REASONING_MESSAGE_CHUNK with no messageId and no open stream; skipped.")
                } else {
                    if (messageId !in reasoningParts) openReasoning(messageId, title = null)
                    event.delta?.let { appendReasoning(messageId, it) }
                }
            }

            is ReasoningEndEvent -> endReasoning(event.messageId)

            is ReasoningEncryptedValueEvent -> {
                val messageId = lastReasoningMessageId
                val ref = messageId?.let { reasoningParts[it] }
                if (ref == null) {
                    onWarning("REASONING_ENCRYPTED_VALUE with no reasoning stream to attach to; skipped.")
                } else {
                    val part = ref.part<ReasoningPart>()
                    ref.message.replace(
                        ref.index,
                        part.copy(
                            encryptedValues = part.encryptedValues + EncryptedReasoningValue(
                                subtype = event.subtype,
                                entityId = event.entityId,
                                value = event.encryptedValue,
                            ),
                        ),
                    )
                }
            }

            // ---- Thinking: the deprecated spelling of reasoning ----------------------------
            //
            // These carry no messageId at all, so one is synthesised and reused for the whole
            // stream. Mapped onto the same ReasoningPart rather than a parallel model: a consumer
            // should not have to render an agent's deliberation twice depending on which spelling
            // its server happens to emit.

            is ThinkingStartEvent -> openReasoning(THINKING_MESSAGE_ID, title = event.title)
            is ThinkingTextMessageStartEvent -> openReasoning(THINKING_MESSAGE_ID, title = null)
            is ThinkingTextMessageContentEvent -> appendReasoning(THINKING_MESSAGE_ID, event.delta)
            is ThinkingTextMessageEndEvent -> Unit
            is ThinkingEndEvent -> endReasoning(THINKING_MESSAGE_ID)

            // ---- Shared state --------------------------------------------------------------

            is StateSnapshotEvent -> sharedState = event.snapshot

            is StateDeltaEvent -> {
                sharedState = patch(sharedState, event.delta, "STATE_DELTA") ?: sharedState
            }

            is MessagesSnapshotEvent -> replaceMessages(event.messages)

            // ---- Activity ------------------------------------------------------------------

            is ActivitySnapshotEvent -> applyActivitySnapshot(event)
            is ActivityDeltaEvent -> applyActivityDelta(event)

            // ---- Out of band ---------------------------------------------------------------
            //
            // RAW and CUSTOM carry no rendering obligation: RAW is the provider's own event
            // passed through, CUSTOM is an agreement between one agent and one client. A consumer
            // that wants them reads them off the stream, which it still holds. Folding them into
            // the transcript would put payloads with no defined shape into a model whose whole
            // job is to have one.

            is RawEvent -> Unit
            is CustomEvent -> Unit
        }
    }

    // ---- Turn management ---------------------------------------------------------------------

    /** The assistant turn to append to, opening one keyed by [fallbackId] if none is open. */
    private fun assistantTurn(fallbackId: String): MessageBuilder =
        openAssistant ?: MessageBuilder(id = fallbackId, role = UiRole.ASSISTANT)
            .also {
                openAssistant = it
                messages += it
            }

    /**
     * Stops appending to the open assistant turn, without declaring anything in it finished.
     *
     * The two are separate on purpose. A user message or an activity message means the *next*
     * assistant part belongs in a new bubble, because the new message now sits between them in the
     * sequence -- it does not mean the agent stopped talking. An `ACTIVITY_SNAPSHOT` landing under
     * streaming text is the ordinary case, not an interruption, and a reducer that finished the
     * text there would drop the caret while the words were still arriving.
     */
    private fun detachTurn() {
        openAssistant = null
    }

    /**
     * Ends the run's assistant turn and settles everything still open in it.
     *
     * A run that ends mid-stream -- finished, failed, or replaced by the next one -- would
     * otherwise leave a caret blinking under text that will never grow, and a tool call spinning
     * on arguments that will never arrive.
     *
     * @param reason the failure to record on a tool call that never finished streaming its
     *   arguments, when there is one to record.
     */
    private fun finalizeTurn(reason: String? = null) {
        // The deprecated THINKING_* events carry no message id, so the synthetic one is all that
        // scopes them -- and a run is the only scope they can have. Dropped here, or a second
        // run's deliberation would append to the first run's part, in a message already settled.
        reasoningParts.remove(THINKING_MESSAGE_ID)
        if (lastReasoningMessageId == THINKING_MESSAGE_ID) lastReasoningMessageId = null

        val turn = openAssistant
        if (turn == null) {
            return
        }
        turn.parts.forEachIndexed { index, part ->
            when (part) {
                is TextPart -> if (part.streaming) turn.replace(index, part.copy(streaming = false))
                is ReasoningPart -> if (part.streaming) turn.replace(index, part.copy(streaming = false))
                is ToolCallPart -> if (part.status == ToolCallStatus.STREAMING_ARGUMENTS) {
                    turn.replace(index, part.copy(status = ToolCallStatus.FAILED, error = reason))
                }

                else -> Unit
            }
        }
        detachTurn()
    }

    // ---- Text --------------------------------------------------------------------------------

    private fun openText(messageId: String, role: Role) {
        lastTextMessageId = messageId
        if (textParts.containsKey(messageId)) return

        val part = TextPart(
            id = "text:$messageId",
            text = "",
            messageId = messageId,
            streaming = true,
        )
        val owner = when (role) {
            Role.ASSISTANT -> assistantTurn(fallbackId = messageId)
            // A user, system or developer message is its own message and ends the assistant's
            // turn: whatever the agent was saying, it was saying it before this arrived.
            else -> {
                detachTurn()
                MessageBuilder(id = messageId, role = role.toUiRole()).also { messages += it }
            }
        }
        textParts[messageId] = PartRef(owner, owner.append(part))
    }

    private fun appendText(messageId: String, delta: String) {
        val ref = textParts[messageId]
        if (ref == null) {
            onWarning("TEXT_MESSAGE_CONTENT for unknown messageId $messageId; skipped.")
            return
        }
        lastTextMessageId = messageId
        val part = ref.part<TextPart>()
        ref.message.replace(ref.index, part.copy(text = part.text + delta))
    }

    private fun endText(messageId: String) {
        val ref = textParts[messageId] ?: return
        val part = ref.part<TextPart>()
        ref.message.replace(ref.index, part.copy(streaming = false))
    }

    // ---- Tool calls --------------------------------------------------------------------------

    private fun openToolCall(toolCallId: String, name: String, parentMessageId: String?) {
        lastToolCallId = toolCallId
        if (toolParts.containsKey(toolCallId)) return

        // `parentMessageId` names the assistant message the call belongs to. Honoured when it
        // names a text run this reducer has seen, because a call can arrive after the turn it
        // belongs to was closed; otherwise the open turn is the right home.
        val owner = parentMessageId
            ?.let { textParts[it]?.message }
            ?: assistantTurn(fallbackId = toolCallId)

        val part = ToolCallPart(
            id = "tool:$toolCallId",
            toolCallId = toolCallId,
            name = name,
            parentMessageId = parentMessageId,
        )
        toolParts[toolCallId] = PartRef(owner, owner.append(part))
    }

    private fun appendToolArgs(toolCallId: String, delta: String) {
        val ref = toolParts[toolCallId]
        if (ref == null) {
            onWarning("TOOL_CALL_ARGS for unknown toolCallId $toolCallId; skipped.")
            return
        }
        lastToolCallId = toolCallId
        val part = ref.part<ToolCallPart>()
        ref.message.replace(ref.index, part.copy(arguments = part.arguments + delta))
    }

    private fun endToolCall(toolCallId: String) {
        val ref = toolParts[toolCallId] ?: return
        val part = ref.part<ToolCallPart>()
        ref.message.replace(
            ref.index,
            part.copy(
                status = ToolCallStatus.AWAITING_RESULT,
                parsedArguments = parseJsonOrNull(part.arguments),
            ),
        )
    }

    // ---- Reasoning ---------------------------------------------------------------------------

    private fun openReasoning(messageId: String, title: String?) {
        lastReasoningMessageId = messageId
        val existing = reasoningParts[messageId]
        if (existing != null) {
            if (title != null) {
                val part = existing.part<ReasoningPart>()
                existing.message.replace(existing.index, part.copy(title = title))
            }
            return
        }
        val owner = assistantTurn(fallbackId = messageId)
        val part = ReasoningPart(
            id = "reasoning:$messageId",
            text = "",
            messageId = messageId,
            title = title,
            streaming = true,
        )
        reasoningParts[messageId] = PartRef(owner, owner.append(part))
    }

    private fun appendReasoning(messageId: String, delta: String) {
        val ref = reasoningParts[messageId]
        if (ref == null) {
            onWarning("Reasoning content for unknown messageId $messageId; skipped.")
            return
        }
        lastReasoningMessageId = messageId
        val part = ref.part<ReasoningPart>()
        ref.message.replace(ref.index, part.copy(text = part.text + delta))
    }

    private fun endReasoning(messageId: String) {
        val ref = reasoningParts[messageId] ?: return
        val part = ref.part<ReasoningPart>()
        ref.message.replace(ref.index, part.copy(streaming = false))
        if (lastReasoningMessageId == messageId) lastReasoningMessageId = null
    }

    // ---- Activity ----------------------------------------------------------------------------

    /**
     * `ACTIVITY_SNAPSHOT`, per the specification's normative reading of `replace`.
     *
     * An absent `replace` means the snapshot replaces both content and `activityType`; upstream's
     * type defaults the field to `true`, which is the same thing. An explicit `replace: false`
     * asks for the existing message to stand as it is -- content *and* type. It is not a merge,
     * and the snapshot's own values apply only when it is the snapshot that creates the message.
     */
    private fun applyActivitySnapshot(event: ActivitySnapshotEvent) {
        val existing = activityMessages[event.messageId]
        if (existing == null) {
            // Created at the point of arrival, so it keeps its place in the sequence. Detached
            // rather than finalised: text streaming underneath keeps streaming.
            detachTurn()
            val message = MessageBuilder(id = event.messageId, role = UiRole.ACTIVITY)
            message.append(
                ActivityPart(
                    id = "activity:${event.messageId}",
                    messageId = event.messageId,
                    activityType = event.activityType,
                    content = event.content,
                ),
            )
            messages += message
            activityMessages[event.messageId] = message
            return
        }
        if (!event.replace) return

        val part = existing.parts[0] as ActivityPart
        existing.replace(0, part.copy(activityType = event.activityType, content = event.content))
    }

    /**
     * `ACTIVITY_DELTA`: an RFC 6902 patch against one activity's content.
     *
     * The delta's `activityType` replaces the message's -- the field is required, so a delta that
     * does not mean to retype the activity repeats the current type, and applying it either way is
     * correct.
     *
     * A delta for an activity that does not exist, and a patch that does not apply, both warn and
     * skip. The specification requires exactly that: neither may fail the run, and the producer's
     * next snapshot resynchronises.
     */
    private fun applyActivityDelta(event: ActivityDeltaEvent) {
        val message = activityMessages[event.messageId]
        if (message == null) {
            onWarning("ACTIVITY_DELTA for unknown activity message ${event.messageId}; skipped.")
            return
        }
        val part = message.parts[0] as ActivityPart
        val patched = patch(part.content, event.patch, "ACTIVITY_DELTA") ?: return
        message.replace(0, part.copy(activityType = event.activityType, content = patched))
    }

    // ---- Snapshots ---------------------------------------------------------------------------

    /**
     * `MESSAGES_SNAPSHOT` replaces the transcript.
     *
     * Ordering *within* a rebuilt assistant message is not recoverable, and this is where that
     * shows: the snapshot carries one `content` string and a separate `toolCalls` list, so the
     * text is placed before the calls. A streamed turn does not lose the order and a restored one
     * does -- an asymmetry of the wire format, not of this model, and the reason a client that
     * cares about it should prefer replaying events over restoring a snapshot.
     */
    private fun replaceMessages(snapshot: List<Message>) {
        messages.clear()
        textParts.clear()
        reasoningParts.clear()
        toolParts.clear()
        activityMessages.clear()
        openAssistant = null
        lastTextMessageId = null
        lastToolCallId = null
        lastReasoningMessageId = null

        // Applied after the assistant messages are built, because a tool result names a call that
        // may live in a message later in the list than the ToolMessage's own position suggests.
        val toolResults = mutableListOf<ToolMessage>()

        for (message in snapshot) {
            when (message) {
                is ToolMessage -> {
                    toolResults += message
                    continue
                }

                is ActivityMessage -> {
                    val builder = MessageBuilder(id = message.id, role = UiRole.ACTIVITY)
                    builder.append(
                        ActivityPart(
                            id = "activity:${message.id}",
                            messageId = message.id,
                            activityType = message.activityType,
                            content = message.activityContent,
                        ),
                    )
                    messages += builder
                    activityMessages[message.id] = builder
                }

                is AssistantMessage -> {
                    val builder = MessageBuilder(message.id, UiRole.ASSISTANT, message.name)
                    message.content?.let {
                        val index = builder.append(
                            TextPart(id = "text:${message.id}", text = it, messageId = message.id),
                        )
                        textParts[message.id] = PartRef(builder, index)
                    }
                    message.toolCalls?.forEach { call ->
                        val index = builder.append(
                            ToolCallPart(
                                id = "tool:${call.id}",
                                toolCallId = call.id,
                                name = call.function.name,
                                arguments = call.function.arguments,
                                parsedArguments = parseJsonOrNull(call.function.arguments),
                                status = ToolCallStatus.AWAITING_RESULT,
                                parentMessageId = message.id,
                            ),
                        )
                        toolParts[call.id] = PartRef(builder, index)
                    }
                    messages += builder
                }

                is UserMessage -> {
                    val builder = MessageBuilder(message.id, UiRole.USER, message.name)
                    val contentParts = message.contentParts
                    if (contentParts != null) {
                        contentParts.forEachIndexed { index, content ->
                            when (content) {
                                is TextInputContent -> builder.append(
                                    TextPart(
                                        id = "text:${message.id}#$index",
                                        text = content.text,
                                        messageId = message.id,
                                    ),
                                )

                                is BinaryInputContent -> builder.append(
                                    FilePart(
                                        id = "file:${message.id}#$index",
                                        mimeType = content.mimeType,
                                        remoteId = content.id,
                                        url = content.url,
                                        data = content.data,
                                        filename = content.filename,
                                    ),
                                )
                            }
                        }
                    } else {
                        builder.append(
                            TextPart(
                                id = "text:${message.id}",
                                text = message.content,
                                messageId = message.id,
                            ),
                        )
                    }
                    messages += builder
                }

                is SystemMessage, is DeveloperMessage -> {
                    val role = if (message is SystemMessage) UiRole.SYSTEM else UiRole.DEVELOPER
                    val builder = MessageBuilder(message.id, role, message.name)
                    message.content?.let {
                        builder.append(
                            TextPart(id = "text:${message.id}", text = it, messageId = message.id),
                        )
                    }
                    messages += builder
                }
            }
        }

        for (result in toolResults) {
            val ref = toolParts[result.toolCallId]
            if (ref == null) {
                onWarning(
                    "MESSAGES_SNAPSHOT holds a tool result for unknown toolCallId " +
                        "${result.toolCallId}; skipped.",
                )
                continue
            }
            val part = ref.part<ToolCallPart>()
            ref.message.replace(
                ref.index,
                part.copy(
                    result = result.content,
                    error = result.error,
                    status = if (result.error != null) ToolCallStatus.FAILED else ToolCallStatus.COMPLETE,
                ),
            )
        }
    }

    private fun patch(target: JsonElement, operations: kotlinx.serialization.json.JsonArray, what: String): JsonElement? =
        try {
            com.reidsync.kxjsonpatch.JsonPatch.apply(operations, target)
        } catch (error: Exception) {
            onWarning("$what did not apply: ${error.message}")
            null
        }

    private companion object {
        /**
         * The id the deprecated `THINKING_*` events are folded under.
         *
         * They carry none of their own, and a reasoning part needs one. Namespaced so it cannot
         * collide with a real message id.
         */
        const val THINKING_MESSAGE_ID = "agui-compose:thinking"
    }
}

private inline fun <reified T> PartRef.part(): T = message.parts[index] as T

private fun Role.toUiRole(): UiRole = when (this) {
    Role.USER -> UiRole.USER
    Role.ASSISTANT -> UiRole.ASSISTANT
    Role.SYSTEM -> UiRole.SYSTEM
    Role.DEVELOPER -> UiRole.DEVELOPER
    else -> UiRole.ASSISTANT
}

private fun parseJsonOrNull(text: String): JsonElement? =
    if (text.isBlank()) {
        null
    } else {
        try {
            kotlinx.serialization.json.Json.parseToJsonElement(text)
        } catch (_: Exception) {
            null
        }
    }
