package net.hawyn.vivootahelper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** A settings section that uses the surrounding Miuix theme, never a second Material UI. */
@Composable
fun MonetSettings(
    preferences: UiPreferencesState,
    callbacks: UiAppearanceCallbacks,
    modifier: Modifier = Modifier,
) {
    var picker by rememberSaveable { mutableStateOf<ThemePicker?>(null) }
    var hex by rememberSaveable(preferences.customAccentArgb) { mutableStateOf(formatAccentHex(preferences.customAccentArgb)) }
    val parsed = parseAccentHex(hex)
    val seed = rememberOtaAccentArgb(preferences)
    val keyboard = LocalSoftwareKeyboardController.current

    Column(modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        ThemeCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    ThemeTitle("启用 Monet")
                    ThemeDescription(if (preferences.monetEnabled) "让背景、卡片和控件一起融入主题色。" else "当前只应用强调色，页面保留基础配色。")
                }
                Switch(checked = preferences.monetEnabled, onCheckedChange = callbacks.onMonetChange)
            }
            Spacer(Modifier.height(18.dp))
            ThemeColorPreview()
        }

        ThemeCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    ThemeTitle("强调色")
                    ThemeDescription(if (preferences.accentSource == AccentSource.SYSTEM) "使用系统主题的颜色。" else "以所选颜色生成清晰、协调的配色。")
                }
                Box(Modifier.size(38.dp).border(1.dp, MiuixTheme.colorScheme.outline, CircleShape)
                    .padding(4.dp).background(Color(seed), CircleShape))
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { callbacks.onAccentSourceChange(AccentSource.SYSTEM) },
                    modifier = Modifier.weight(1f), minWidth = 0.dp,
                    colors = if (preferences.accentSource == AccentSource.SYSTEM) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                ) { Text("系统默认", fontSize = 14.sp) }
                Button(
                    onClick = { callbacks.onAccentSourceChange(AccentSource.CUSTOM) },
                    modifier = Modifier.weight(1f), minWidth = 0.dp,
                    colors = if (preferences.accentSource == AccentSource.CUSTOM) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                ) { Text("自选颜色", fontSize = 14.sp) }
            }
            Spacer(Modifier.height(15.dp))
            Column(Modifier.selectableGroup()) {
                AccentPresets.chunked(5).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { argb ->
                            val selected = preferences.accentSource == AccentSource.CUSTOM && preferences.customAccentArgb == argb
                            Box(
                                Modifier.weight(1f).height(56.dp)
                                    .selectable(selected, role = Role.RadioButton, onClick = { callbacks.onCustomAccentChange(argb) })
                                    .semantics { contentDescription = formatAccentHex(argb) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    Modifier.size(44.dp)
                                        .border(if (selected) 2.dp else 1.dp, if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.outline.copy(alpha = .35f), CircleShape)
                                        .padding(5.dp).background(Color(argb), CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (selected) Icon(UiIcons.Check, null, Modifier.size(20.dp), tint = if (Color(argb).luminance() > .4f) Color.Black else Color.White)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { hex = formatAccentHex(preferences.customAccentArgb); picker = ThemePicker.HEX },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("输入 HEX", fontSize = 14.sp); Spacer(Modifier.size(10.dp)); Text(formatAccentHex(preferences.customAccentArgb), fontSize = 13.sp, fontFamily = FontFamily.Monospace) }
        }

        ThemeCard {
            ThemeTitle("Monet 风格")
            Spacer(Modifier.height(7.dp))
            ThemeDescription(if (preferences.monetEnabled) preferences.monetStyle.description else "开启 Monet 后，可以调整整套配色。")
            Spacer(Modifier.height(16.dp))
            Button(onClick = { picker = ThemePicker.STYLE }, enabled = preferences.monetEnabled, modifier = Modifier.fillMaxWidth()) {
                Text(preferences.monetStyle.label, Modifier.weight(1f), fontSize = 14.sp)
                Text("9 种风格", fontSize = 12.sp)
                Spacer(Modifier.size(10.dp))
                Icon(UiIcons.Arrow, null, Modifier.size(18.dp))
            }
            Spacer(Modifier.height(22.dp))
            ThemeTitle("色彩标准")
            Spacer(Modifier.height(7.dp))
            ThemeDescription("选择配色规则，改变色阶与颜色的搭配。")
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MonetSpec.entries.forEach { spec ->
                    val supported = spec == MonetSpec.SPEC_2021 || preferences.monetStyle.supports2025
                    Button(
                        onClick = { callbacks.onMonetSpecChange(spec) },
                        enabled = preferences.monetEnabled && supported, modifier = Modifier.weight(1f), minWidth = 0.dp,
                        colors = if (preferences.monetSpec == spec) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                    ) { Text(spec.label, fontSize = 14.sp) }
                }
            }
            Spacer(Modifier.height(12.dp))
            ThemeDescription("2025 适用于柔和、中性、鲜艳和表现；其他风格使用 2021。")
        }
    }

    OverlayDialog(
        show = picker == ThemePicker.STYLE,
        title = "Monet 风格",
        summary = if (preferences.monetSpec == MonetSpec.SPEC_2025) "选择其他风格时，色彩标准会切换为 2021。" else "选一种与你合拍的配色。",
        onDismissRequest = { picker = null },
    ) {
        Column(Modifier.heightIn(max = 390.dp).verticalScroll(rememberScrollState()).selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MonetStyle.entries.forEach { style ->
                StyleChoice(style, preferences.monetStyle == style) {
                    callbacks.onMonetStyleChange(style)
                    picker = null
                }
            }
        }
    }

    OverlayDialog(
        show = picker == ThemePicker.HEX,
        title = "自选强调色",
        summary = "输入 #RRGGBB。文字与按钮会按可读性自动配色。",
        onDismissRequest = { keyboard?.hide(); picker = null },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextField(
                value = hex, onValueChange = { if (it.length <= 9) hex = it },
                modifier = Modifier.fillMaxWidth(), label = "HEX 颜色", singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    parsed?.let { callbacks.onCustomAccentChange(it); keyboard?.hide(); picker = null }
                }),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (parsed != null) {
                    Box(Modifier.size(32.dp).border(1.dp, MiuixTheme.colorScheme.outline, CircleShape).padding(3.dp).background(Color(parsed), CircleShape))
                    Text(formatAccentHex(parsed), fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                } else Text("请输入六位十六进制颜色，例如 #6F58A8。", color = MiuixTheme.colorScheme.error, fontSize = 13.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { keyboard?.hide(); picker = null }, modifier = Modifier.weight(1f)) { Text("返回") }
                Button(
                    onClick = { parsed?.let { callbacks.onCustomAccentChange(it); keyboard?.hide(); picker = null } },
                    enabled = parsed != null, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text("应用颜色") }
            }
        }
    }
}

private enum class ThemePicker { STYLE, HEX }

private val AccentPresets = listOf(
    0xFF2867DB, 0xFF6F58A8, 0xFFAD477C, 0xFFBA4747, 0xFFBC7133,
    0xFF8E8B31, 0xFF43815B, 0xFF2D8288, 0xFF57727F, 0xFF707078,
).map { it.toInt() }

@Composable
private fun ThemeCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), cornerRadius = 25.dp, insideMargin = PaddingValues(20.dp), content = content)
}

@Composable
private fun ThemeTitle(text: String) { Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }

@Composable
private fun ThemeDescription(text: String) { Text(text, fontSize = 13.sp, lineHeight = 21.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }

@Composable
private fun StyleChoice(style: MonetStyle, selected: Boolean, onClick: () -> Unit) {
    val primary = MiuixTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().background(if (selected) primary.copy(alpha = .08f) else Color.Transparent, RoundedCornerShape(16.dp))
            .selectable(selected, role = Role.RadioButton, onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(style.label, fontSize = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            ThemeDescription(style.description)
        }
        if (selected) Icon(UiIcons.Check, null, Modifier.size(22.dp), tint = primary)
    }
}

@Composable
private fun ThemeColorPreview() {
    val colors = MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(colors.primary to colors.onPrimary, colors.primaryContainer to colors.onPrimaryContainer, colors.surfaceContainerHigh to colors.onSurfaceContainerHigh).forEachIndexed { index, pair ->
            Box(Modifier.weight(1f).height(43.dp).background(pair.first, RoundedCornerShape(13.dp)), Alignment.Center) {
                Text(listOf("主题", "柔和", "表面")[index], color = pair.second, fontSize = 12.sp)
            }
        }
    }
}
