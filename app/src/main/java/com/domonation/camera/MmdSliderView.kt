package com.domonation.camera

import android.content.Context
import android.util.AttributeSet
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import com.mudita.mmd.ThemeMMD
import com.mudita.mmd.components.slider.SliderMMD

class MmdSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AbstractComposeView(context, attrs) {
    fun interface Listener { fun onProgressChanged(progress: Int, fromUser: Boolean) }

    private var maximum by mutableIntStateOf(attrs?.getAttributeIntValue(ANDROID_NS, "max", 100) ?: 100)
    private var current by mutableIntStateOf(attrs?.getAttributeIntValue(ANDROID_NS, "progress", 0) ?: 0)
    private var listener: Listener? = null

    fun setMax(value: Int) { maximum = value.coerceAtLeast(1); current = current.coerceAtMost(maximum) }
    fun getMax(): Int = maximum
    fun setProgress(value: Int) { current = value.coerceIn(0, maximum) }
    fun getProgress(): Int = current
    fun setOnProgressChangedListener(value: Listener?) { listener = value }

    @androidx.compose.runtime.Composable
    override fun Content() {
        ThemeMMD(colorScheme = MmdUi.domonationColorScheme) {
            SliderMMD(
                value = current.toFloat(),
                onValueChange = {
                    current = it.toInt().coerceIn(0, maximum)
                    listener?.onProgressChanged(current, true)
                },
                valueRange = 0f..maximum.toFloat(),
                enabled = isEnabled,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    companion object { private const val ANDROID_NS = "http://schemas.android.com/apk/res/android" }
}
