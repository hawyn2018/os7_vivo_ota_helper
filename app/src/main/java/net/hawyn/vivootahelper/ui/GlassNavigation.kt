package net.hawyn.vivootahelper.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.colorControls
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.LightPosition
import top.yukonga.miuix.kmp.blur.highlight.LightSource
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.runtimeShaderEffect
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class AppTab(val label: String) { HOME("概览"), TASKS("任务"), BACKUPS("备份"), SETTINGS("设置") }

/** Live backdrop sampling; refraction and light move only during selection changes. */
@Composable
internal fun GlassNavigation(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    backdrop: LayerBackdrop,
    glassEnabled: Boolean,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(34.dp)
    val glass = glassEnabled && isRuntimeShaderSupported()
    val selection by animateFloatAsState(selected.ordinal.toFloat(), spring(dampingRatio = .82f, stiffness = 380f), label = "navigation selection")
    val surface = MiuixTheme.colorScheme.surfaceContainer
    val tint = surface.copy(alpha = if (dark) .64f else .56f)
    val active = MiuixTheme.colorScheme.primary.copy(alpha = if (dark) .15f else .09f)
    val effect = if (glass) Modifier.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            // Native blur is downsampled by Miuix. This shader uses its recorded scale.
            blur(18.dp.toPx())
            colorControls(saturation = 1.18f, contrast = 1.03f)
            val scale = downscaleFactor.toFloat()
            val halfW = size.width / 2f / scale
            val halfH = size.height / 2f / scale
            val inset = padding / scale
            runtimeShaderEffect("ota_glass_lens_v1", GlassLensShader, "content") {
                setFloatUniform("center", halfW + inset, halfH + inset)
                setFloatUniform("halfSize", halfW, halfH)
                setFloatUniform("corner", 34.dp.toPx() / scale)
                setFloatUniform("band", 11.dp.toPx() / scale)
                setFloatUniform("bend", 3.dp.toPx() / scale)
            }
        },
        highlight = {
            Highlight(
                width = .9.dp,
                style = BloomStroke(
                    color = Color.White.copy(alpha = if (dark) .12f else .22f),
                    innerBlurRadius = 2.6.dp,
                    primaryLight = LightSource(
                        LightPosition(.12f + selection * .23f, .18f, -.48f),
                        intensity = if (dark) .7f else .5f,
                    ),
                    secondaryLight = LightSource(LightPosition(.88f, .94f, -.45f), intensity = .28f),
                ),
            )
        },
        onDrawSurface = { drawRect(tint) },
    ) else Modifier.background(surface, shape)

    Box(
        modifier = modifier
            .shadow(18.dp, shape, ambientColor = Color.Black.copy(alpha = .12f), spotColor = Color.Black.copy(alpha = .12f))
            .then(effect)
            .clip(shape)
            .padding(6.dp)
            .drawBehind {
                val cell = size.width / AppTab.entries.size
                drawRoundRect(
                    active,
                    topLeft = Offset(cell * selection + 2.dp.toPx(), 1.dp.toPx()),
                    size = Size(cell - 4.dp.toPx(), size.height - 2.dp.toPx()),
                    cornerRadius = CornerRadius(28.dp.toPx()),
                )
            },
    ) {
        NavigationBar(
            modifier = Modifier.fillMaxWidth(),
            color = Color.Transparent,
            showDivider = false,
            defaultWindowInsetsPadding = false,
        ) {
            AppTab.entries.forEach { tab ->
                NavigationBarItem(
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    label = tab.label,
                    icon = when (tab) {
                        AppTab.HOME -> UiIcons.Home
                        AppTab.TASKS -> UiIcons.Tasks
                        AppTab.BACKUPS -> UiIcons.Backup
                        AppTab.SETTINGS -> UiIcons.Settings
                    },
                )
            }
        }
    }
}

// Rounded-rectangle lens confined to the glass rim. The centre remains readable.
// A cached, static shader avoids an idle animation/render loop.
private const val GlassLensShader = """
uniform shader content;
uniform float2 center;
uniform float2 halfSize;
uniform float corner;
uniform float band;
uniform float bend;
float distanceToRim(float2 p) {
    float r = min(corner, min(halfSize.x, halfSize.y));
    float2 q = abs(p) - halfSize + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}
half4 main(float2 coord) {
    float2 p = coord - center;
    float d = distanceToRim(p);
    float2 normal = normalize(float2(
        distanceToRim(p + float2(0.5, 0.0)) - distanceToRim(p - float2(0.5, 0.0)),
        distanceToRim(p + float2(0.0, 0.5)) - distanceToRim(p - float2(0.0, 0.5))) + 0.0001);
    float edge = 1.0 - smoothstep(0.0, max(band, 0.1), max(-d, 0.0));
    float2 sampleAt = coord - normal * bend * edge * edge;
    return content.eval(sampleAt);
}
"""
