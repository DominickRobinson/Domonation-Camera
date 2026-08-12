package com.domonation.camera

import android.content.Context
import android.util.AttributeSet
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.unit.dp
import com.mudita.mmd.ThemeMMD

class MmdProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AbstractComposeView(context, attrs) {
    private var position by mutableIntStateOf(0)
    private var count by mutableIntStateOf(1)
    private var showThumb by mutableStateOf(false)

    fun setPosition(index: Int, total: Int) {
        count = total.coerceAtLeast(1)
        position = index.coerceIn(0, count - 1)
        showThumb = true
    }

    fun clearPosition() { showThumb = false }

    @Composable
    override fun Content() {
        ThemeMMD(colorScheme = MmdUi.domonationColorScheme) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 2.dp.toPx()
                val barHeight = 14.dp.toPx().coerceAtMost(size.height)
                val top = (size.height - barHeight) / 2f
                val radius = barHeight / 2f
                drawRoundRect(
                    color = Color.White,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, top),
                    size = androidx.compose.ui.geometry.Size(size.width, barHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius),
                )
                drawRoundRect(
                    color = Color.Black,
                    topLeft = androidx.compose.ui.geometry.Offset(stroke / 2f, top + stroke / 2f),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, barHeight - stroke),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius),
                    style = Stroke(stroke),
                )
                if (!showThumb) return@Canvas
                val inset = stroke
                val available = (size.width - inset * 2f).coerceAtLeast(0f)
                val thumbWidth = if (count == 1) available else
                    (available / count).coerceAtLeast(24.dp.toPx()).coerceAtMost(available)
                val travel = available - thumbWidth
                val thumbLeft = inset + if (count == 1) 0f else travel * position / (count - 1f)
                drawRoundRect(
                    color = Color.Black,
                    topLeft = androidx.compose.ui.geometry.Offset(thumbLeft, top + stroke),
                    size = androidx.compose.ui.geometry.Size(thumbWidth, barHeight - stroke * 2f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius),
                )
            }
        }
    }
}
