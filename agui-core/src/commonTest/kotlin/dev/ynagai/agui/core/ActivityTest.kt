package dev.ynagai.agui.core

import com.agui.core.types.ActivityDeltaEvent
import com.agui.core.types.ActivitySnapshotEvent
import com.agui.core.types.RunStartedEvent
import com.agui.core.types.TextMessageContentEvent
import com.agui.core.types.TextMessageEndEvent
import com.agui.core.types.TextMessageStartEvent
import dev.ynagai.agui.model.ActivityPart
import dev.ynagai.agui.model.TextPart
import dev.ynagai.agui.model.UiRole
import dev.ynagai.agui.model.UiTranscript
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `ACTIVITY_SNAPSHOT` and `ACTIVITY_DELTA`, which the upstream reducer has no branch for at all.
 *
 * The expectations here are the specification's, not this library's preference: see
 * `docs/spec/draft/events/activity.mdx` in `ag-ui-protocol/ag-ui`, quoted where it decides a case.
 */
class ActivityTest {

    @Test
    fun a_snapshot_for_an_unseen_id_creates_an_activity_message_where_it_arrived() {
        val transcript = reduce(
            TextMessageStartEvent(messageId = "m1"),
            TextMessageContentEvent(messageId = "m1", delta = "Searching"),
            TextMessageEndEvent(messageId = "m1"),
            ActivitySnapshotEvent(
                messageId = "act-1",
                activityType = "web_search",
                content = obj("""{"query":"kmp","found":0}"""),
            ),
        )

        assertContentEquals(
            listOf(UiRole.ASSISTANT, UiRole.ACTIVITY),
            transcript.messages.map { it.role },
        )
        val part = transcript.activity("act-1")
        assertEquals("web_search", part.activityType)
        assertEquals(obj("""{"query":"kmp","found":0}"""), part.content)
    }

    /** "A snapshot for an existing activity replaces its content and its `activityType`." */
    @Test
    fun a_second_snapshot_replaces_both_content_and_type() {
        val transcript = reduce(
            ActivitySnapshotEvent("act-1", "web_search", obj("""{"found":0}""")),
            ActivitySnapshotEvent("act-1", "checklist", obj("""{"done":["a"]}""")),
        )

        val part = transcript.activity("act-1")
        assertEquals("checklist", part.activityType)
        assertEquals(obj("""{"done":["a"]}"""), part.content)
    }

    /**
     * "An explicit `replace: false` asks the consumer to leave the existing message as it stands --
     * content *and* `activityType`; the snapshot's own values apply only when it creates the
     * message. It is not a merge."
     */
    @Test
    fun replace_false_leaves_an_existing_activity_exactly_as_it_stands() {
        val transcript = reduce(
            ActivitySnapshotEvent("act-1", "web_search", obj("""{"found":0}""")),
            ActivitySnapshotEvent("act-1", "checklist", obj("""{"done":["a"]}"""), replace = false),
        )

        val part = transcript.activity("act-1")
        assertEquals("web_search", part.activityType)
        assertEquals(obj("""{"found":0}"""), part.content)
    }

    @Test
    fun replace_false_still_creates_the_message_when_it_is_the_first_snapshot() {
        val transcript = reduce(
            ActivitySnapshotEvent("act-1", "web_search", obj("""{"found":0}"""), replace = false),
        )

        assertEquals("web_search", transcript.activity("act-1").activityType)
    }

    @Test
    fun a_delta_patches_the_content_in_place() {
        val transcript = reduce(
            ActivitySnapshotEvent("act-1", "web_search", obj("""{"query":"kmp","found":0}""")),
            ActivityDeltaEvent(
                messageId = "act-1",
                activityType = "web_search",
                patch = arr("""[{"op":"replace","path":"/found","value":3}]"""),
            ),
            ActivityDeltaEvent(
                messageId = "act-1",
                activityType = "web_search",
                patch = arr("""[{"op":"add","path":"/results","value":["a"]}]"""),
            ),
        )

        assertEquals(
            obj("""{"query":"kmp","found":3,"results":["a"]}"""),
            transcript.activity("act-1").content,
        )
    }

    /** "The delta's `activityType` replaces the message's -- a delta MAY retype the activity." */
    @Test
    fun a_delta_may_retype_the_activity_it_amends() {
        val transcript = reduce(
            ActivitySnapshotEvent("act-1", "web_search", obj("""{"found":0}""")),
            ActivityDeltaEvent(
                messageId = "act-1",
                activityType = "search_results",
                patch = arr("""[{"op":"replace","path":"/found","value":3}]"""),
            ),
        )

        assertEquals("search_results", transcript.activity("act-1").activityType)
    }

    /**
     * "A delta naming a message that does not exist [...] is skipped; the consumer SHOULD surface a
     * warning, and MUST NOT fail the run."
     */
    @Test
    fun a_delta_for_an_unknown_activity_warns_and_does_not_fail_the_run() {
        val warnings = mutableListOf<String>()
        val reducer = UiTranscriptReducer(warnings::add)

        reducer.accept(
            ActivityDeltaEvent("ghost", "web_search", arr("""[{"op":"add","path":"/a","value":1}]""")),
        )

        assertTrue(reducer.transcript.messages.isEmpty())
        assertEquals(1, warnings.size)
        assertTrue("ghost" in warnings.single(), warnings.single())
    }

    /**
     * "Patch failure handling is the pattern's, with resynchronisation by a fresh snapshot of the
     * same `messageId`."
     */
    @Test
    fun a_patch_that_does_not_apply_warns_leaves_the_content_alone_and_resyncs_on_the_next_snapshot() {
        val warnings = mutableListOf<String>()
        val reducer = UiTranscriptReducer(warnings::add)

        reducer.accept(ActivitySnapshotEvent("act-1", "web_search", obj("""{"found":0}""")))
        reducer.accept(
            ActivityDeltaEvent(
                messageId = "act-1",
                activityType = "web_search",
                // `test` against a value that is not there: a well-formed patch that cannot apply.
                patch = arr("""[{"op":"test","path":"/found","value":99}]"""),
            ),
        )

        assertEquals(1, warnings.size)
        assertEquals(obj("""{"found":0}"""), reducer.transcript.activity("act-1").content)

        reducer.accept(ActivitySnapshotEvent("act-1", "web_search", obj("""{"found":7}""")))
        assertEquals(obj("""{"found":7}"""), reducer.transcript.activity("act-1").content)
    }

    /**
     * `a2ui-surface` is one `activityType` among others, and this module does not know it from any
     * other. Interpreting the payload is `agui-a2ui`'s job; carrying it intact is this one's.
     */
    @Test
    fun carries_an_a2ui_surface_payload_through_without_interpreting_it() {
        val surface = """{"surfaceId":"s1","messages":[{"beginRendering":{"root":"root"}}]}"""
        val transcript = reduce(ActivitySnapshotEvent("act-1", "a2ui-surface", obj(surface)))

        val part = transcript.activity("act-1")
        assertEquals("a2ui-surface", part.activityType)
        assertEquals(obj(surface), part.content)
    }

    private fun UiTranscript.activity(messageId: String): ActivityPart =
        messages.flatMap { it.parts }.filterIsInstance<ActivityPart>().single { it.messageId == messageId }
}

internal fun obj(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

internal fun arr(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

/**
 * An activity landing under streaming text is the ordinary case, not an interruption.
 *
 * Separate class so the shared helpers above stay where they are.
 */
class ActivityDuringStreamingTest {

    @Test
    fun an_activity_arriving_mid_stream_does_not_stop_the_text_underneath_it() {
        val reducer = UiTranscriptReducer()
        reducer.accept(RunStartedEvent(threadId = "t", runId = "r"))
        reducer.accept(TextMessageStartEvent(messageId = "m1"))
        reducer.accept(TextMessageContentEvent(messageId = "m1", delta = "Search"))
        reducer.accept(ActivitySnapshotEvent("act-1", "web_search", obj("""{"found":0}""")))
        reducer.accept(TextMessageContentEvent(messageId = "m1", delta = "ing"))

        val text = reducer.transcript.messages
            .flatMap { it.parts }
            .filterIsInstance<TextPart>()
            .single()

        assertEquals("Searching", text.text)
        assertTrue(text.streaming, "the caret went out while the words were still arriving")
    }

    @Test
    fun assistant_text_after_an_activity_opens_a_new_message_so_the_activity_keeps_its_place() {
        val transcript = reduce(
            RunStartedEvent(threadId = "t", runId = "r"),
            TextMessageStartEvent(messageId = "m1"),
            TextMessageContentEvent(messageId = "m1", delta = "Looking."),
            TextMessageEndEvent(messageId = "m1"),
            ActivitySnapshotEvent("act-1", "web_search", obj("""{"found":3}""")),
            TextMessageStartEvent(messageId = "m2"),
            TextMessageContentEvent(messageId = "m2", delta = "Found three."),
            TextMessageEndEvent(messageId = "m2"),
        )

        assertContentEquals(
            listOf(UiRole.ASSISTANT, UiRole.ACTIVITY, UiRole.ASSISTANT),
            transcript.messages.map { it.role },
        )
    }
}
