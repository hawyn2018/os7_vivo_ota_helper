package net.hawyn.vivootahelper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The root application supplies real state and actions; this UI never performs root I/O. */
@Composable
fun OtaApp(
    state: OtaUiState,
    callbacks: OtaCallbacks,
    preferences: UiPreferencesState,
    onGlassChange: (Boolean) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    appearanceCallbacks: UiAppearanceCallbacks,
) {
    val dark = isOtaDarkTheme(preferences.theme)
    OtaTheme(dark, preferences) {
        var selected by rememberSaveable { mutableStateOf(AppTab.HOME) }
        var confirmation by rememberSaveable { mutableStateOf<Confirmation?>(null) }
        var confirmedId by rememberSaveable { mutableStateOf("") }
        var acknowledged by rememberSaveable { mutableStateOf(false) }
        var promptState by rememberSaveable(stateSaver = PreparedWritePromptSaver) {
            mutableStateOf(PreparedWritePromptState())
        }
        val backdrop = rememberLayerBackdrop()

        LaunchedEffect(state.task?.id) {
            if (state.task?.status == TaskStatus.RUNNING && !state.refreshing) selected = AppTab.TASKS
        }
        LaunchedEffect(state.summary?.id, state.summary?.readyToWrite, state.restoreSummary?.id, state.task?.id, state.task?.status) {
            val update = advancePreparedWritePrompt(promptState, state)
            if (update.resetConfirmation) {
                if (update.writePlanChanged && state.summary != null) selected = AppTab.HOME
                if (update.restorePlanChanged && state.restoreSummary != null) selected = AppTab.BACKUPS
                confirmation = null
                acknowledged = false
            }
            promptState = update.state
            update.showWritePlanId?.let {
                selected = AppTab.HOME
                confirmedId = it
                acknowledged = false
                confirmation = Confirmation.WRITE
            }
        }
        BackHandler(enabled = selected != AppTab.HOME && confirmation == null) { selected = AppTab.HOME }

        Scaffold(containerColor = MiuixTheme.colorScheme.background, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { _ ->
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                    AmbientBackground(dark)
                    AnimatedContent(
                        targetState = selected,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "page",
                    ) { page ->
                        when (page) {
                            AppTab.HOME -> HomeScreen(state, callbacks) {
                                promptState = promptState.copy(writePlanId = it, restorePlanId = state.restoreSummary?.id.orEmpty()).shown(it)
                                confirmedId = it
                                acknowledged = false
                                confirmation = Confirmation.WRITE
                            }
                            AppTab.TASKS -> TasksScreen(state, callbacks)
                            AppTab.BACKUPS -> BackupsScreen(state, callbacks) {
                                confirmedId = it
                                acknowledged = false
                                confirmation = Confirmation.RESTORE
                            }
                            AppTab.SETTINGS -> SettingsScreen(preferences, onGlassChange, onThemeChange, appearanceCallbacks)
                        }
                    }
                }
                GlassNavigation(
                    selected = selected,
                    onSelect = { selected = it },
                    backdrop = backdrop,
                    glassEnabled = preferences.liquidGlass,
                    dark = dark,
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .navigationBarsPadding().padding(horizontal = 22.dp, vertical = 14.dp)
                        .widthIn(max = 520.dp).fillMaxWidth(),
                )
            }

            val writePlan = state.summary?.takeIf { confirmation == Confirmation.WRITE && it.id == confirmedId }
            val restorePlan = state.restoreSummary?.takeIf { confirmation == Confirmation.RESTORE && it.id == confirmedId }
            val canCommit = !state.busy && (writePlan?.readyToWrite == true || restorePlan?.readyToRestore == true)
            OverlayDialog(
                show = confirmation != null && (writePlan != null || restorePlan != null),
                title = if (confirmation == Confirmation.RESTORE) "确认恢复备份" else "准备完成，是否开始刷写？",
                summary = "请最后核对目标槽位与备份。开始后请保持应用在前台并等待完成。",
                onDismissRequest = { confirmation = null },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    LabelValue("目标槽位", slotLabel(writePlan?.targetSlot ?: restorePlan?.targetSlot.orEmpty()))
                    LabelValue("备份", writePlan?.backupName ?: restorePlan?.backupName.orEmpty())
                    val names = writePlan?.images ?: restorePlan?.images.orEmpty()
                    Text(
                        "写入：" + names.filter { it.action == ImageAction.WRITE }.joinToString("、") { it.name },
                        fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    ConfirmationAcknowledgement(acknowledged, { acknowledged = it })
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { confirmation = null }, modifier = Modifier.weight(1f)) { Text("返回核对") }
                        Button(
                            onClick = commit@ {
                                val kind = confirmation ?: return@commit
                                if (!acknowledged || !canCommit) return@commit
                                confirmation = null
                                acknowledged = false
                                if (kind == Confirmation.RESTORE) callbacks.onConfirmRestore(confirmedId)
                                else callbacks.onConfirmWrite(confirmedId)
                                selected = AppTab.TASKS
                            },
                            enabled = acknowledged && canCommit,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) { Text(if (confirmation == Confirmation.RESTORE) "开始恢复" else "开始写入") }
                    }
                }
            }

            OverlayDialog(
                show = state.notice != null,
                title = state.notice?.title,
                summary = state.notice?.message,
                onDismissRequest = callbacks.onDismissNotice,
            ) {
                Button(onClick = callbacks.onDismissNotice, modifier = Modifier.fillMaxWidth()) { Text("知道了") }
            }
        }
    }
}

private enum class Confirmation { WRITE, RESTORE }

/** Saved with the dialog so dismissing or rotating never repeats a plan's automatic prompt. */
internal data class PreparedWritePromptState(
    val writePlanId: String = "",
    val restorePlanId: String = "",
    val pendingPlanId: String = "",
    val pendingTaskId: String = "",
    val shownPlanIds: List<String> = emptyList(),
) {
    fun shown(id: String) = copy(
        pendingPlanId = "", pendingTaskId = "",
        shownPlanIds = if (id in shownPlanIds) shownPlanIds else shownPlanIds + id,
    )

    fun save() = listOf(writePlanId, restorePlanId, pendingPlanId, pendingTaskId) + shownPlanIds

    companion object {
        fun restore(values: List<String>) = PreparedWritePromptState(values[0], values[1], values[2], values[3], values.drop(4))
    }
}

internal data class PreparedWritePromptUpdate(
    val state: PreparedWritePromptState,
    val writePlanChanged: Boolean,
    val restorePlanChanged: Boolean,
    val showWritePlanId: String? = null,
) {
    val resetConfirmation get() = writePlanChanged || restorePlanChanged
}

/** Only completion of the task that supplied a new plan can open its confirmation. */
internal fun advancePreparedWritePrompt(previous: PreparedWritePromptState, ui: OtaUiState): PreparedWritePromptUpdate {
    val id = ui.summary?.id.orEmpty()
    val restoreId = ui.restoreSummary?.id.orEmpty()
    val writeChanged = id != previous.writePlanId
    val restoreChanged = restoreId != previous.restorePlanId
    var next = previous.copy(writePlanId = id, restorePlanId = restoreId)
    if (writeChanged) {
        val pending = id.takeIf { it.isNotEmpty() && it !in next.shownPlanIds }.orEmpty()
        next = next.copy(pendingPlanId = pending, pendingTaskId = if (pending.isNotEmpty()) ui.task?.id.orEmpty() else "")
    }
    if (next.pendingPlanId.isNotEmpty() && (
            restoreId.isNotEmpty() || next.pendingTaskId.isEmpty() || ui.task?.id != next.pendingTaskId ||
                ui.task?.status == TaskStatus.FAILED || ui.task?.status == TaskStatus.CANCELLED
            )) {
        next = next.copy(pendingPlanId = "", pendingTaskId = "")
    }
    val show = next.pendingPlanId.takeIf {
        it.isNotEmpty() && it == id && ui.summary?.readyToWrite == true && !ui.busy && ui.task?.status == TaskStatus.SUCCEEDED
    }
    if (show != null) next = next.shown(show)
    return PreparedWritePromptUpdate(next, writeChanged, restoreChanged, show)
}

private val PreparedWritePromptSaver = listSaver<PreparedWritePromptState, String>(
    save = { it.save() }, restore = { PreparedWritePromptState.restore(it) },
)

@Composable
private fun AmbientBackground(dark: Boolean) {
    val accent = MiuixTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
        drawCircle(
            brush = Brush.radialGradient(listOf(accent.copy(alpha = if (dark) .09f else .075f), Color.Transparent)),
            radius = size.width * .7f,
            center = Offset(size.width * 1.02f, size.height * .02f),
        )
        drawCircle(
            brush = Brush.radialGradient(listOf(Color(0xFF58B6B1).copy(alpha = if (dark) .05f else .045f), Color.Transparent)),
            radius = size.width * .8f,
            center = Offset(-size.width * .18f, size.height * .76f),
        )
    }
}

@Composable
private fun ConfirmationAcknowledgement(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val primary = MiuixTheme.colorScheme.primary
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).background(
                if (checked) primary else primary.copy(alpha = .10f), RoundedCornerShape(8.dp),
            ), Alignment.Center,
        ) {
            if (checked) Icon(UiIcons.Check, null, Modifier.size(19.dp), tint = MiuixTheme.colorScheme.onPrimary)
        }
        Text("我已核对目标槽位和备份，确认开始。", modifier = Modifier.weight(1f), fontSize = 14.sp, lineHeight = 21.sp)
    }
}

@Composable
internal fun LabelValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 14.sp)
        Text(value.ifBlank { "—" }, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}
