package com.fucksh.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 应用内自绘图标，路径取自设计稿的 24×24 线性图标，
 * 与 Miuix 图标保持同样的描边粗细和圆角端点。
 */
object AppIcons {

    val ShieldCheck: ImageVector by lazy {
        stroke(
            "ShieldCheck",
            "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z",
            "M9 12l2 2 4-4",
        )
    }

    val Globe: ImageVector by lazy {
        stroke(
            "Globe",
            "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20z",
            "M2 12h20",
            "M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z",
        )
    }

    val AlertCircle: ImageVector by lazy {
        stroke(
            "AlertCircle",
            "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20z",
            "M12 8v4",
            "M12 16h.01",
        )
    }

    val MapPin: ImageVector by lazy {
        stroke(
            "MapPin",
            "M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z",
            "M12 13a3 3 0 1 0 0-6 3 3 0 0 0 0 6z",
        )
    }

    val Archive: ImageVector by lazy {
        stroke(
            "Archive",
            "M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z",
            "M17 21v-8H7v8",
        )
    }

    val Activity: ImageVector by lazy {
        stroke("Activity", "M22 12h-4l-3 9L9 3l-3 9H2")
    }

    val Refresh: ImageVector by lazy {
        stroke(
            "Refresh",
            "M21.5 2v6h-6",
            "M21.34 15.57a10 10 0 1 1-.57-8.38l5.67-5.67",
        )
    }

    val User: ImageVector by lazy {
        stroke(
            "User",
            "M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2",
            "M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z",
        )
    }

    val Document: ImageVector by lazy {
        stroke(
            "Document",
            "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z",
            "M14 2v6h6",
            "M16 13H8",
            "M16 17H8",
            "M10 9H8",
        )
    }

    val ChevronRight: ImageVector by lazy {
        stroke("ChevronRight", "M9 18l6-6-6-6")
    }

    val Plus: ImageVector by lazy {
        stroke("Plus", "M12 5v14", "M5 12h14")
    }

    val Trash: ImageVector by lazy {
        stroke(
            "Trash",
            "M3 6h18",
            "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2",
            "M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6",
        )
    }

    val Export: ImageVector by lazy {
        stroke(
            "Export",
            "M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8",
            "M16 6l-4-4-4 4",
            "M12 2v13",
        )
    }

    val Lock: ImageVector by lazy {
        stroke(
            "Lock",
            "M19 11H5a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7a2 2 0 0 0-2-2z",
            "M7 11V7a5 5 0 0 1 10 0v4",
        )
    }

    val Info: ImageVector by lazy {
        stroke(
            "Info",
            "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20z",
            "M12 16v-4",
            "M12 8h.01",
        )
    }

    val Home: ImageVector by lazy {
        stroke(
            "Home",
            "M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
            "M9 22V12h6v10",
        )
    }

    val Settings: ImageVector by lazy {
        stroke(
            "Settings",
            "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z",
            "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z",
        )
    }

    val List: ImageVector by lazy {
        stroke(
            "List",
            "M8 6h13",
            "M8 12h13",
            "M8 18h13",
            "M3 6h.01",
            "M3 12h.01",
            "M3 18h.01",
        )
    }

    val Github: ImageVector by lazy {
        filled(
            "Github",
            "M12 .5C5.73.5.5 5.73.5 12c0 5.08 3.29 9.39 7.86 10.91.58.11.79-.25.79-.56 0-.28-.01-1.02-.02-2-3.2.7-3.88-1.54-3.88-1.54-.52-1.33-1.28-1.68-1.28-1.68-1.05-.72.08-.71.08-.71 1.16.08 1.77 1.19 1.77 1.19 1.03 1.77 2.7 1.26 3.36.96.1-.75.4-1.26.73-1.55-2.55-.29-5.23-1.28-5.23-5.7 0-1.26.45-2.29 1.19-3.1-.12-.29-.52-1.46.11-3.05 0 0 .97-.31 3.18 1.18a11.1 11.1 0 0 1 5.8 0c2.2-1.49 3.17-1.18 3.17-1.18.63 1.59.23 2.76.11 3.05.74.81 1.19 1.84 1.19 3.1 0 4.43-2.69 5.41-5.25 5.69.41.35.78 1.05.78 2.12 0 1.53-.01 2.76-.01 3.14 0 .31.21.67.8.56A10.52 10.52 0 0 0 23.5 12C23.5 5.73 18.27.5 12 .5z",
        )
    }

    val Heart: ImageVector by lazy {
        stroke(
            "Heart",
            "M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z",
        )
    }

    private fun stroke(name: String, vararg paths: String): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        paths.forEach { path ->
            builder.addPath(
                pathData = PathParser().parsePathString(path).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }

    private fun filled(name: String, path: String): ImageVector {
        return ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            fill = SolidColor(Color.Black),
        ).build()
    }
}
