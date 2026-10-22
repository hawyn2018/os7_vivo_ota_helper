package net.hawyn.vivootahelper.ui

import org.junit.Assert.*
import org.junit.Test

class PreparedWritePromptTest {
    @Test fun successfulPreparationWaitsUntilIdleThenPromptsExactlyOnce() {
        val running = advancePreparedWritePrompt(PreparedWritePromptState(), screen(status = TaskStatus.RUNNING))
        assertNull(running.showWritePlanId)
        val completed = advancePreparedWritePrompt(running.state, screen())
        assertEquals("plan-1", completed.showWritePlanId)
        assertNull(advancePreparedWritePrompt(completed.state, screen()).showWritePlanId)
    }

    @Test fun incompletePlanDoesNotPromptUntilReady() {
        val incomplete = advancePreparedWritePrompt(PreparedWritePromptState(), screen(ready = false))
        assertNull(incomplete.showWritePlanId)
        assertEquals("plan-1", advancePreparedWritePrompt(incomplete.state, screen()).showWritePlanId)
    }

    @Test fun dismissAndRotationKeepThePromptConsumedAndDialogStateUntouched() {
        val prompted = advancePreparedWritePrompt(PreparedWritePromptState(), screen())
        val restored = PreparedWritePromptState.restore(prompted.state.save())
        val afterRotation = advancePreparedWritePrompt(restored, screen())
        assertFalse(afterRotation.resetConfirmation)
        assertNull(afterRotation.showWritePlanId)
        // Changing tabs does not change the preparation state or consume another prompt.
        assertNull(advancePreparedWritePrompt(afterRotation.state, screen()).showWritePlanId)
    }

    @Test fun rotationWhilePreparingStillPromptsWhenTheSameTaskCompletes() {
        val pending = advancePreparedWritePrompt(PreparedWritePromptState(), screen(status = TaskStatus.RUNNING))
        val restored = PreparedWritePromptState.restore(pending.state.save())
        assertEquals("plan-1", advancePreparedWritePrompt(restored, screen()).showWritePlanId)
    }

    @Test fun failedOrCancelledPreparationNeverPromptsOnALaterInspection() {
        for (status in listOf(TaskStatus.FAILED, TaskStatus.CANCELLED)) {
            val pending = advancePreparedWritePrompt(PreparedWritePromptState(), screen(status = TaskStatus.RUNNING))
            val failed = advancePreparedWritePrompt(pending.state, screen(status = status))
            assertNull(failed.showWritePlanId)
            assertNull(advancePreparedWritePrompt(failed.state, screen(task = "inspect")).showWritePlanId)
        }
    }

    @Test fun inspectionCannotCompleteAPreparationsPendingPrompt() {
        val pending = advancePreparedWritePrompt(PreparedWritePromptState(), screen(status = TaskStatus.RUNNING))
        val inspected = advancePreparedWritePrompt(pending.state, screen(task = "inspect"))
        assertNull(inspected.showWritePlanId)
        assertNull(advancePreparedWritePrompt(PreparedWritePromptState(), OtaUiState(task = TaskUiState("inspect", "Root", "", TaskStatus.SUCCEEDED))).showWritePlanId)
        assertNull(advancePreparedWritePrompt(PreparedWritePromptState(), screen().copy(task = null)).showWritePlanId)
    }

    @Test fun restoreNeverTriggersWriteAndInvalidatesAPendingWritePrompt() {
        val pending = advancePreparedWritePrompt(PreparedWritePromptState(), screen(status = TaskStatus.RUNNING))
        val restore = RestoreSummary("restore-1", "backup", "_b", emptyList(), readyToRestore = true)
        val restored = advancePreparedWritePrompt(pending.state, screen().copy(restoreSummary = restore))
        assertTrue(restored.restorePlanChanged)
        assertNull(restored.showWritePlanId)
        assertNull(advancePreparedWritePrompt(restored.state, screen()).showWritePlanId)
        assertNull(advancePreparedWritePrompt(PreparedWritePromptState(), OtaUiState(restoreSummary = restore)).showWritePlanId)
    }

    @Test fun eachNewPlanPromptsOnceAndPreviouslyShownPlansStayConsumed() {
        val first = advancePreparedWritePrompt(PreparedWritePromptState(), screen())
        val second = advancePreparedWritePrompt(first.state, screen(id = "plan-2", task = "prepare-2"))
        assertEquals("plan-2", second.showWritePlanId)
        assertTrue(second.resetConfirmation)
        assertNull(advancePreparedWritePrompt(second.state, screen()).showWritePlanId)
    }

    @Test fun manualReopenRemainsConsumedForAutomaticPrompts() {
        val manual = PreparedWritePromptState(writePlanId = "plan-1").shown("plan-1")
        val reopened = advancePreparedWritePrompt(manual, screen())
        assertNull(reopened.showWritePlanId)
        assertFalse(reopened.resetConfirmation)
        val removed = advancePreparedWritePrompt(reopened.state, OtaUiState())
        assertTrue(removed.resetConfirmation)
        assertNull(advancePreparedWritePrompt(removed.state, screen()).showWritePlanId)
    }

    private fun screen(
        id: String = "plan-1",
        ready: Boolean = true,
        task: String = "prepare-1",
        status: TaskStatus = TaskStatus.SUCCEEDED,
    ) = OtaUiState(
        summary = UpdateSummary(id, "_b", 1, emptyList(), readyToWrite = ready, backupName = "backup"),
        task = TaskUiState(task, "备份并修补对槽镜像", "", status),
    )
}
