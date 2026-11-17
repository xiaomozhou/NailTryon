package com.example.nailtryon

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun HandFramingGuide(modifier: Modifier = Modifier) {
    Canvas(modifier.testTag("hand-framing-guide").semantics { contentDescription = "手部取景参考框" }) {
        val inset = size.minDimension * .10f
        val length = size.minDimension * .12f
        for (x in listOf(inset, size.width - inset)) {
            for (y in listOf(inset, size.height - inset)) {
                val corner = Offset(x, y)
                val horizontal = Offset(x + if (x == inset) length else -length, y)
                val vertical = Offset(x, y + if (y == inset) length else -length)
                for (end in listOf(horizontal, vertical)) {
                    drawLine(Color.Black.copy(alpha = .55f), corner, end, 5.dp.toPx(), StrokeCap.Round)
                    drawLine(Color.White.copy(alpha = .9f), corner, end, 2.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    }
}
