package com.example.nailtryon

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.graphics.PathParser

/** Normalized dorsal LEFT hand: the thumb is on the viewer's right. No detection is performed. */
internal object LeftHandGuide {
    const val WIDTH = 1000f
    const val HEIGHT = 1400f
    // Five independent closed contours, with visible space between every adjacent finger.
    val fingers = linkedMapOf(
        "小指" to "M230,820 C210,740 130,555 150,505 C170,465 210,480 230,530 L305,790 Q320,845 275,850 Q245,850 230,820 Z",
        "无名指" to "M355,765 L295,350 C285,300 310,270 340,270 C370,270 390,295 395,335 L445,750 Q450,805 405,810 Q365,810 355,765 Z",
        "中指" to "M490,755 L490,215 C490,170 515,150 545,150 C578,150 598,175 598,215 L600,755 Q600,805 545,805 Q490,805 490,755 Z",
        "食指" to "M645,800 L685,365 C690,315 715,290 745,295 C780,300 792,330 785,370 L735,815 Q725,860 682,850 Q642,840 645,800 Z",
        "拇指" to "M765,1060 L860,830 C880,785 912,775 940,800 C965,825 965,850 947,885 L850,1105 Q825,1145 790,1110 Q765,1090 765,1060 Z",
    )
    private const val PALM = "M370,1300 L350,1190 C285,1150 250,1080 240,1000 L225,900 Q235,875 265,895 Q330,940 345,855 Q350,830 380,835 Q440,885 480,835 Q495,815 525,835 Q590,890 630,870 Q665,850 695,890 L740,1000 Q765,1035 750,1090 L690,1300"

    fun draw(canvas: Canvas, width: Float, height: Float) {
        val scale = minOf(width / WIDTH, height / HEIGHT)
        val save = canvas.save()
        canvas.translate((width - WIDTH * scale) / 2, (height - HEIGHT * scale) / 2)
        canvas.scale(scale, scale)
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 13f; color = android.graphics.Color.argb(130, 0, 0, 0)
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        val line = Paint(shadow).apply { strokeWidth = 7f; color = android.graphics.Color.WHITE }
        (fingers.values + PALM).forEach { data ->
            val path = PathParser.createPathFromPathData(data)!!
            canvas.drawPath(path, shadow)
            canvas.drawPath(path, line)
        }
        canvas.restoreToCount(save)
    }
}

@Composable
internal fun LeftHandGuideOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier.testTag("left-hand-guide").semantics {
        contentDescription = "左手手背对齐轮廓，五根手指分开，拇指位于右侧"
    }) { LeftHandGuide.draw(drawContext.canvas.nativeCanvas, size.width, size.height) }
}
