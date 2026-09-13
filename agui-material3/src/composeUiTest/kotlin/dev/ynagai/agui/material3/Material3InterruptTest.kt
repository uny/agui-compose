package dev.ynagai.agui.material3

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ynagai.agui.compose.AguiInterrupts
import dev.ynagai.agui.model.UiInterrupt
import dev.ynagai.agui.model.UiResumeEntry
import dev.ynagai.agui.model.UiResumeStatus
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The approval card: what it says, and what each button sends.
 *
 * The button contract is the whole point -- a card that looked right and resolved a declined
 * approval with `true` would be worse than no card -- so every test here clicks and reads the
 * entry back.
 */
@OptIn(ExperimentalTestApi::class)
class Material3InterruptTest {

    @Test
    fun approvingAnApprovalSchemaResolvesWithApprovedTrue() = runComposeUiTest {
        val answers = mutableListOf<UiResumeEntry>()
        setContent { Material3TestSurface { AguiInterrupts(listOf(STRANDS_APPROVAL), onResume = { answers += it }) } }

        onNodeWithText("Approve call to transfer?").assertIsDisplayed()
        onNodeWithText(AguiStrings.APPROVE).performClick()

        assertEquals(
            listOf(UiResumeEntry("i1", UiResumeStatus.RESOLVED, buildJsonObject { put("approved", JsonPrimitive(true)) })),
            answers,
        )
    }

    @Test
    fun decliningAbandonsTheInterrupt() = runComposeUiTest {
        val answers = mutableListOf<UiResumeEntry>()
        setContent { Material3TestSurface { AguiInterrupts(listOf(STRANDS_APPROVAL), onResume = { answers += it }) } }

        onNodeWithText(AguiStrings.DECLINE).performClick()

        assertEquals(listOf(UiResumeEntry("i1", UiResumeStatus.CANCELLED)), answers)
    }

    /** A producer that asked for a bare go-ahead -- no schema -- gets one: resolved, no payload. */
    @Test
    fun approvingWithoutASchemaResolvesWithNoPayload() = runComposeUiTest {
        val answers = mutableListOf<UiResumeEntry>()
        val bare = UiInterrupt(id = "i2", reason = "schedule_meeting", message = "Pick a time?")
        setContent { Material3TestSurface { AguiInterrupts(listOf(bare), onResume = { answers += it }) } }

        onNodeWithText("Pick a time?").assertIsDisplayed()
        onNodeWithText(AguiStrings.APPROVE).performClick()

        assertEquals(UiResumeStatus.RESOLVED, answers.single().status)
        assertNull(answers.single().payload)
    }

    @Test
    fun thePromptFallsBackToTheToolNameAndThenTheReason() = runComposeUiTest {
        val named = UiInterrupt(
            id = "i3",
            reason = "tool_call",
            metadata = buildJsonObject { put("tool_name", JsonPrimitive("send_email")) },
        )
        val bare = UiInterrupt(id = "i4", reason = "confirm_delete")
        setContent { Material3TestSurface { AguiInterrupts(listOf(named, bare), onResume = {}) } }

        onNodeWithText("Approve call to send_email?").assertIsDisplayed()
        onNodeWithText("confirm_delete").assertIsDisplayed()
    }

    private companion object {
        val STRANDS_APPROVAL = UiInterrupt(
            id = "i1",
            reason = "tool_call",
            message = "Approve call to transfer?",
            toolCallId = "c1",
            responseSchema = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", buildJsonObject { put("approved", buildJsonObject { put("type", JsonPrimitive("boolean")) }) })
            },
        )
    }
}
