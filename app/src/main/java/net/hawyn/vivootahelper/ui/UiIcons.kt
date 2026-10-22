package net.hawyn.vivootahelper.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Compact original vectors: no icon-font or extended-icons dependency. */
object UiIcons {
    private fun icon(name: String, draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                pathFillType = PathFillType.NonZero, pathBuilder = draw)
        }.build()

    val Home = icon("概览") {
        moveTo(4f, 10f); lineTo(12f, 3.8f); lineTo(20f, 10f); verticalLineTo(20f)
        horizontalLineTo(14.7f); verticalLineTo(14f); horizontalLineTo(9.3f); verticalLineTo(20f)
        horizontalLineTo(4f); close()
    }
    val Tasks = icon("任务") {
        moveTo(9f, 5f); horizontalLineTo(20f); moveTo(9f, 12f); horizontalLineTo(20f)
        moveTo(9f, 19f); horizontalLineTo(20f); moveTo(3.5f, 5f); lineTo(4.5f, 6f); lineTo(6f, 4f)
        moveTo(3.5f, 12f); lineTo(4.5f, 13f); lineTo(6f, 11f); moveTo(4.5f, 19f); lineTo(4.6f, 19f)
    }
    val Backup = icon("备份") {
        moveTo(4f, 8f); horizontalLineTo(20f); verticalLineTo(20f); horizontalLineTo(4f); close()
        moveTo(3f, 4f); horizontalLineTo(21f); verticalLineTo(8f); horizontalLineTo(3f); close()
        moveTo(9f, 12f); horizontalLineTo(15f)
    }
    val Settings = icon("设置") {
        moveTo(4f, 6f); horizontalLineTo(9f); moveTo(14f, 6f); horizontalLineTo(20f)
        moveTo(4f, 12f); horizontalLineTo(14f); moveTo(19f, 12f); horizontalLineTo(20f)
        moveTo(4f, 18f); horizontalLineTo(6f); moveTo(11f, 18f); horizontalLineTo(20f)
        moveTo(9f, 3.5f); lineTo(14f, 3.5f); verticalLineTo(8.5f); horizontalLineTo(9f); close()
        moveTo(14f, 9.5f); horizontalLineTo(19f); verticalLineTo(14.5f); horizontalLineTo(14f); close()
        moveTo(6f, 15.5f); horizontalLineTo(11f); verticalLineTo(20.5f); horizontalLineTo(6f); close()
    }
    val Refresh = icon("重新检测") {
        moveTo(19.5f, 8.5f); curveTo(17.5f, 3f, 9.5f, 2.5f, 5.5f, 7f)
        moveTo(19.5f, 4f); verticalLineTo(8.5f); horizontalLineTo(15f)
        moveTo(4.5f, 15.5f); curveTo(6.5f, 21f, 14.5f, 21.5f, 18.5f, 17f)
        moveTo(4.5f, 20f); verticalLineTo(15.5f); horizontalLineTo(9f)
    }
    val Arrow = icon("继续") { moveTo(5f, 12f); horizontalLineTo(19f); moveTo(14f, 7f); lineTo(19f, 12f); lineTo(14f, 17f) }
    val Check = icon("通过") { moveTo(5f, 12f); lineTo(10f, 17f); lineTo(19f, 7f) }
    val Shield = icon("Root 状态") {
        moveTo(12f, 3f); lineTo(20f, 6f); verticalLineTo(11f); curveTo(20f, 16f, 16f, 20f, 12f, 22f)
        curveTo(8f, 20f, 4f, 16f, 4f, 11f); verticalLineTo(6f); close()
        moveTo(8f, 12f); lineTo(11f, 15f); lineTo(16f, 9f)
    }
    val Storage = icon("存储") {
        moveTo(4f, 5f); horizontalLineTo(20f); verticalLineTo(19f); horizontalLineTo(4f); close()
        moveTo(4f, 14f); horizontalLineTo(20f); moveTo(16f, 16.5f); lineTo(17f, 16.5f)
    }
    val Package = icon("更新包") {
        moveTo(4f, 7f); lineTo(12f, 3f); lineTo(20f, 7f); verticalLineTo(17f); lineTo(12f, 21f); lineTo(4f, 17f); close()
        moveTo(4f, 7f); lineTo(12f, 11f); lineTo(20f, 7f); moveTo(12f, 11f); verticalLineTo(21f)
        moveTo(8f, 5f); lineTo(16f, 9f)
    }
}
