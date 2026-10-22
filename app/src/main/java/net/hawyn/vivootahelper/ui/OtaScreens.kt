package net.hawyn.vivootahelper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

private val Good = Color(0xFF2AA483)
private val Amber = Color(0xFFC58A31)

@Composable
private fun pagePadding() = PaddingValues(
    start = 22.dp, end = 22.dp,
    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 18.dp,
    bottom = 150.dp,
)

@Composable
internal fun HomeScreen(state: OtaUiState, callbacks: OtaCallbacks, onConfirm: (String) -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
            contentPadding = pagePadding(), verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                PageHeader("origionos7lk升级助手", "系统更新后的下一步") {
                    Button(
                        onClick = callbacks.onPrepare,
                        enabled = !state.busy && !state.refreshing && state.device.root == RootAccess.GRANTED,
                        minWidth = 64.dp, minHeight = 48.dp,
                        insideMargin = PaddingValues(12.dp), cornerRadius = 18.dp,
                    ) {
                        Text("准备", fontSize = 14.sp)
                    }
                }
            }
            item { SlotHero(state.device) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusTile("当前运行", slotLabel(state.device.currentSlot), "正在使用的槽位", UiIcons.Home, Modifier.weight(1f))
                        StatusTile(
                            "待处理", slotLabel(state.device.otherSlot), "作为写入目标",
                            UiIcons.Tasks, Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusTile(
                            "Root 权限", when (state.device.root) {
                                RootAccess.CHECKING -> "检查中"
                                RootAccess.GRANTED -> "已授予"
                                RootAccess.DENIED -> "待授权"
                                RootAccess.UNAVAILABLE -> "未就绪"
                            },
                            if (state.device.root == RootAccess.GRANTED) "点击可重新检测" else "点击此卡重试检测",
                            UiIcons.Shield, Modifier.weight(1f).clickable(enabled = !state.busy, onClick = callbacks.onRefresh),
                        )
                        StatusTile("可用空间", state.device.freeSpaceBytes?.let(::formatBytes) ?: "—", "用于备份与准备", UiIcons.Storage, Modifier.weight(1f))
                    }
                }
            }
            if (state.summary != null) {
                item { UpdateReview(state.summary, state.busy, callbacks.onDiscardPlan, onConfirm) }
            } else {
                item {
                    SectionCard {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(UiIcons.Package)
                            Column(Modifier.weight(1f)) {
                                Text("准备对槽", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                                BodyText("系统 OTA 写入完成后，从这里继续。")
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        BodyText("读取对槽内容并自动保存备份，准备完成后再核对写入摘要。")
                        Spacer(Modifier.height(20.dp))
                        PrimaryButton(
                            "读取对槽并准备", callbacks.onPrepare,
                            enabled = !state.busy && !state.refreshing && state.device.root == RootAccess.GRANTED,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("准备阶段不会写入启动分区。", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
            if (state.device.checks.isNotEmpty()) item {
                SectionCard {
                    SectionTitle("检查结果")
                    Spacer(Modifier.height(14.dp))
                    Checks(state.device.checks)
                    if (state.device.checkedAt.isNotBlank()) {
                        Spacer(Modifier.height(14.dp))
                        Text("检查于 ${state.device.checkedAt}", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
            state.task?.takeIf { it.status != TaskStatus.RUNNING }?.let { task ->
                item {
                    SectionCard {
                        SectionTitle(if (task.status == TaskStatus.SUCCEEDED) "最近任务已完成" else "最近任务")
                        Spacer(Modifier.height(8.dp))
                        BodyText(task.phase)
                        if (task.detail.isNotBlank()) BodyText(task.detail)
                    }
                }
            }
            item { FooterNote("init_boot请使用kernelsu自行刷写另一槽位") }
        }
    }
}

@Composable
private fun SlotHero(device: DeviceOverview) {
    val top = MiuixTheme.colorScheme.primary
    val foreground = MiuixTheme.colorScheme.onPrimary
    val contrastDirection = if (foreground.luminanceForUi() > .5f) Color.Black else Color.White
    val bottom = lerp(top, contrastDirection, .12f)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(top, bottom))).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("检查 · 备份 · 修补", color = foreground, fontSize = 12.sp, letterSpacing = 2.sp)
                Text("ota完成后\n重启前请执行", color = foreground, fontSize = 27.sp, fontWeight = FontWeight.SemiBold, lineHeight = 37.sp)
            }
            Box(Modifier.size(76.dp).background(contrastDirection.copy(alpha = .12f), RoundedCornerShape(25.dp)), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(device.currentSlot.uppercase().removePrefix("_").ifBlank { "—" }, color = foreground, fontSize = 37.sp, fontWeight = FontWeight.Light)
                    Text("当前槽位", color = foreground, fontSize = 10.sp)
                }
            }
        }
        Text("将当前槽位的lk刷写至对槽，提取并修补对槽vendor_boot以修复对旧版lk的兼容性并移除vivo的反root机制后刷回对槽。", color = foreground, fontSize = 13.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun StatusTile(title: String, value: String, detail: String, icon: ImageVector, modifier: Modifier) {
    Card(modifier = modifier.heightIn(min = 148.dp), cornerRadius = 24.dp, insideMargin = PaddingValues(17.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Icon(icon, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.primary.copy(alpha = .7f))
        }
        Spacer(Modifier.height(16.dp))
        Text(value, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(7.dp))
        Text(detail, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, lineHeight = 17.sp)
    }
}

@Composable
private fun UpdateReview(plan: UpdateSummary, busy: Boolean, onDiscard: () -> Unit, onConfirm: (String) -> Unit) {
    SectionCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("写入摘要")
            StatusPill(if (plan.readyToWrite) "等待确认" else "请先检查", if (plan.readyToWrite) Good else Amber)
        }
        Spacer(Modifier.height(18.dp))
        LabelValue("目标槽位", slotLabel(plan.targetSlot))
        Spacer(Modifier.height(12.dp))
        LabelValue("准备容量", formatBytes(plan.requiredBytes))
        Spacer(Modifier.height(12.dp))
        LabelValue("完整备份", plan.backupName)
        Spacer(Modifier.height(20.dp))
        ImageChanges(plan.images)
        if (plan.checks.isNotEmpty()) { Spacer(Modifier.height(18.dp)); Checks(plan.checks) }
        if (plan.warnings.isNotEmpty()) { Spacer(Modifier.height(18.dp)); WarningNotes(plan.warnings) }
        Spacer(Modifier.height(22.dp))
        PrimaryButton("核对并确认写入", { onConfirm(plan.id) }, enabled = !busy && plan.readyToWrite)
        Spacer(Modifier.height(10.dp))
        Button(onClick = onDiscard, modifier = Modifier.fillMaxWidth(), enabled = !busy) { Text("放弃本次准备") }
    }
}

@Composable
internal fun TasksScreen(state: OtaUiState, callbacks: OtaCallbacks) {
    val logs = state.logs.takeLast(1200)
    val scroll = rememberLazyListState()
    var follow by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(logs.lastOrNull()?.id, follow) {
        if (follow && logs.isNotEmpty()) scroll.scrollToItem(logs.lastIndex)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize().padding(top = pagePadding().calculateTopPadding())) {
            Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                PageHeader("任务", "进度与本次操作记录")
                SectionCard {
                    val task = state.task
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle(task?.title ?: "尚未开始任务")
                        if (task != null) StatusPill(when (task.status) {
                            TaskStatus.RUNNING -> "进行中"
                            TaskStatus.SUCCEEDED -> "已完成"
                            TaskStatus.FAILED -> "未完成"
                            TaskStatus.CANCELLED -> "已结束"
                        }, when (task.status) {
                            TaskStatus.SUCCEEDED -> Good
                            TaskStatus.FAILED -> MiuixTheme.colorScheme.error
                            else -> MiuixTheme.colorScheme.primary
                        })
                    }
                    Spacer(Modifier.height(12.dp))
                    BodyText(task?.phase ?: "在概览中读取对槽并准备，进度会显示在这里。")
                    if (task != null) {
                        Spacer(Modifier.height(18.dp))
                        LinearProgressIndicator(progress = task.progress?.coerceIn(0f, 1f) ?: when (task.status) {
                            TaskStatus.RUNNING -> null
                            TaskStatus.SUCCEEDED -> 1f
                            else -> 0f
                        })
                        Spacer(Modifier.height(10.dp))
                        if (task.detail.isNotBlank()) BodyText(task.detail)
                        if (task.status == TaskStatus.RUNNING) FooterNote("操作进行中，请保持应用在前台。")
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("记录", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text("跟随", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Switch(checked = follow, onCheckedChange = { follow = it })
                    Button(onClick = callbacks.onExportLogs, enabled = state.logs.isNotEmpty() && !state.busy, minHeight = 40.dp,
                        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) { Text("导出", fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                state = scroll, modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 150.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (logs.isEmpty()) item { EmptyState("记录会保存在这里", "准备与写入过程都可以在完成后导出。", UiIcons.Tasks) }
                items(logs, key = { it.id }) { line ->
                    SelectionContainer {
                        Row(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = .76f), RoundedCornerShape(12.dp)).padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(line.time, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            Text(line.message, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 18.sp,
                                color = when (line.level) {
                                    LogLevel.INFO -> MiuixTheme.colorScheme.onSurface
                                    LogLevel.SUCCESS -> Good
                                    LogLevel.WARNING -> Amber
                                    LogLevel.ERROR -> MiuixTheme.colorScheme.error
                                })
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BackupsScreen(state: OtaUiState, callbacks: OtaCallbacks, onConfirm: (String) -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize(), contentPadding = pagePadding(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item { PageHeader("备份", "保留每次操作前的原始内容") }
            item {
                SectionCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(UiIcons.Backup)
                        Column(Modifier.weight(1f)) {
                            SectionTitle("先备份，再处理")
                            Spacer(Modifier.height(5.dp))
                            BodyText("准备对槽时自动创建完整备份，可在这里导出或选择恢复。")
                        }
                    }
                }
            }
            state.restoreSummary?.let { plan -> item {
                SectionCard {
                    SectionTitle("恢复摘要")
                    Spacer(Modifier.height(16.dp))
                    LabelValue("备份", plan.backupName)
                    Spacer(Modifier.height(12.dp))
                    LabelValue("恢复到", slotLabel(plan.targetSlot))
                    Spacer(Modifier.height(18.dp))
                    ImageChanges(plan.images)
                    if (plan.checks.isNotEmpty()) { Spacer(Modifier.height(16.dp)); Checks(plan.checks) }
                    if (plan.warnings.isNotEmpty()) { Spacer(Modifier.height(16.dp)); WarningNotes(plan.warnings) }
                    Spacer(Modifier.height(20.dp))
                    PrimaryButton("核对并确认恢复", { onConfirm(plan.id) }, !state.busy && plan.readyToRestore)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = callbacks.onDiscardRestore, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("返回备份列表") }
                }
            } }
            if (state.backups.isEmpty()) item { EmptyState("还没有备份", "完成一次对槽准备后，备份会出现在这里。", UiIcons.Backup) }
            items(state.backups, key = { it.id }) { backup ->
                SectionCard {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(backup.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(7.dp))
                            BodyText("${backup.createdAt} · ${slotLabel(backup.slot)} · ${formatBytes(backup.sizeBytes)}")
                        }
                        StatusPill("可预览", MiuixTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { callbacks.onExportBackup(backup.id) }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("导出备份") }
                        Button(onClick = { callbacks.onSelectRestore(backup.id) }, enabled = !state.busy,
                            modifier = Modifier.weight(1f)) { Text("查看恢复") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsScreen(preferences: UiPreferencesState, onGlassChange: (Boolean) -> Unit, onThemeChange: (ThemeMode) -> Unit, appearanceCallbacks: UiAppearanceCallbacks) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize(), contentPadding = pagePadding(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item { PageHeader("设置", "让操作体验更合心意") }
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Column(Modifier.weight(1f)) {
                            SectionTitle("液态玻璃")
                            Spacer(Modifier.height(7.dp))
                            BodyText(if (isRuntimeShaderSupported()) "悬浮底栏随内容呈现柔和光影与玻璃边缘。" else "当前系统使用实色外观，偏好仍会保存。")
                        }
                        Switch(checked = preferences.liquidGlass, onCheckedChange = onGlassChange)
                    }
                }
            }
            item {
                SectionCard {
                    SectionTitle("外观")
                    Spacer(Modifier.height(7.dp))
                    BodyText("浅色与深色，都保持清晰舒适。")
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            Button(
                                onClick = { onThemeChange(mode) }, modifier = Modifier.weight(1f),
                                minWidth = 0.dp, insideMargin = PaddingValues(horizontal = 5.dp, vertical = 13.dp),
                                colors = if (preferences.theme == mode) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                            ) { Text(mode.label, fontSize = 13.sp) }
                        }
                    }
                }
            }
            item { MonetSettings(preferences, appearanceCallbacks) }
            item {
                SectionCard {
                    SectionTitle("使用顺序")
                    Spacer(Modifier.height(16.dp))
                    StepRow("01", "系统 OTA 写入完成", "由系统完成更新包的写入。")
                    StepRow("02", "准备与核对", "读取对槽、保存备份，检查准备结果。")
                    StepRow("03", "确认写入", "核对目标槽位后明确确认，等待任务完成。")
                    StepRow("04", "自行刷写 init_boot", "init_boot请使用kernelsu自行刷写另一槽位")
                }
            }
            item { FooterNote("origionos7lk升级助手\n检查、备份与对槽修补。") }
        }
    }
}

@Composable
private fun StepRow(number: String, title: String, detail: String) {
    Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(number, fontSize = 13.sp, color = MiuixTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
        Column { Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium); Spacer(Modifier.height(5.dp)); BodyText(detail) }
    }
}

@Composable
private fun ImageChanges(images: List<ImageChange>) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        images.forEach { image ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (image.action == ImageAction.PRESERVE) UiIcons.Shield else UiIcons.Package, null,
                    Modifier.size(19.dp), tint = MiuixTheme.colorScheme.primary.copy(alpha = .7f))
                Column(Modifier.weight(1f)) {
                    Text(image.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    if (image.detail.isNotBlank()) { Spacer(Modifier.height(3.dp)); BodyText(image.detail) }
                }
                Text(if (image.action == ImageAction.PRESERVE) "保留" else formatBytes(image.sizeBytes), fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

@Composable
private fun Checks(items: List<CheckItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        items.forEach { item ->
            val color = when (item.status) {
                CheckStatus.PASS -> Good
                CheckStatus.WARNING -> Amber
                CheckStatus.FAIL -> MiuixTheme.colorScheme.error
                CheckStatus.PENDING -> MiuixTheme.colorScheme.onSurfaceVariantSummary
            }
            Row(horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(22.dp).background(color.copy(alpha = .11f), RoundedCornerShape(7.dp)), Alignment.Center) {
                    if (item.status == CheckStatus.PASS) Icon(UiIcons.Check, "通过", Modifier.size(15.dp), tint = color)
                    else Text(when (item.status) { CheckStatus.FAIL -> "×"; CheckStatus.WARNING -> "!"; else -> "·" }, color = color, fontSize = 14.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text(item.title, fontSize = 14.sp, lineHeight = 21.sp)
                    if (item.detail.isNotBlank()) { Spacer(Modifier.height(2.dp)); BodyText(item.detail) }
                }
            }
        }
    }
}

@Composable
private fun WarningNotes(messages: List<String>) {
    Column(Modifier.fillMaxWidth().background(Amber.copy(alpha = .1f), RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("请留意", fontWeight = FontWeight.SemiBold, color = Amber, fontSize = 13.sp)
        messages.forEach { Text(it, fontSize = 13.sp, lineHeight = 20.sp, color = MiuixTheme.colorScheme.onSurface) }
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 25.dp, insideMargin = PaddingValues(20.dp),
        colors = CardDefaults.defaultColors(), content = content)
}

@Composable
private fun PageHeader(title: String, subtitle: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = if (title.length > 10) 23.sp else 31.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.6).sp)
            Spacer(Modifier.height(7.dp))
            Text(subtitle, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
        }
        action?.invoke()
    }
}

@Composable
private fun IconBadge(icon: ImageVector) {
    Box(Modifier.size(46.dp).background(MiuixTheme.colorScheme.primary.copy(alpha = .10f), RoundedCornerShape(16.dp)), Alignment.Center) {
        Icon(icon, null, Modifier.size(25.dp), tint = MiuixTheme.colorScheme.primary)
    }
}

@Composable
private fun EmptyState(title: String, detail: String, icon: ImageVector) {
    Column(Modifier.fillMaxWidth().padding(vertical = 34.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(13.dp)) {
        IconBadge(icon)
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Text(detail, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun StatusPill(label: String, color: Color) {
    Text(label, Modifier.background(color.copy(alpha = .10f), RoundedCornerShape(100.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
        color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
}

@Composable private fun SectionTitle(title: String) { Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
@Composable private fun BodyText(text: String) { Text(text, fontSize = 13.sp, lineHeight = 21.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
@Composable private fun FooterNote(text: String) { Text(text, Modifier.fillMaxWidth().padding(vertical = 7.dp), fontSize = 12.sp,
    lineHeight = 20.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), minHeight = 52.dp,
        colors = ButtonDefaults.buttonColorsPrimary(), cornerRadius = 18.dp) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(10.dp))
        Icon(UiIcons.Arrow, null, Modifier.size(19.dp))
    }
}

internal fun slotLabel(slot: String): String = slot.uppercase(Locale.ROOT).removePrefix("_").let {
    if (it in listOf("A", "B")) "$it 槽" else "—"
}

internal fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes == 0L) return "0 B"
    val gib = 1024.0 * 1024 * 1024
    return when {
        bytes >= gib -> String.format(Locale.ROOT, "%.1f GB", bytes / gib)
        bytes >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

private fun Color.luminanceForUi(): Float = .2126f * red + .7152f * green + .0722f * blue
