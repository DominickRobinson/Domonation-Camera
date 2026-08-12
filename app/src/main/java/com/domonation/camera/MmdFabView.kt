package com.domonation.camera

import android.content.Context
import android.util.AttributeSet
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.mudita.mmd.ThemeMMD
import com.mudita.mmd.components.buttons.FloatingActionButtonMMD

class MmdFabView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    AbstractComposeView(context, attrs) {
    private var iconRes by mutableIntStateOf(attrs?.getAttributeResourceValue(ANDROID_NS, "src", 0) ?: 0)
    private var composeEnabled by mutableStateOf(true)
    private var activeState by mutableStateOf(false)
    private var primaryState by mutableStateOf(false)
    private var roundedSquareState by mutableStateOf(false)

    fun setImageResource(resource: Int) { iconRes = resource }
    fun setActive(value: Boolean) { activeState = value }
    fun setPrimary(value: Boolean) { primaryState = value }
    fun setRoundedSquare(value: Boolean) { roundedSquareState = value }
    fun setColorFilter(color: Int) { }
    fun setImageTintList(tint: android.content.res.ColorStateList?) { }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        composeEnabled = enabled
    }

    @Composable override fun Content() {
        ThemeMMD(colorScheme = MmdUi.domonationColorScheme) {
            FloatingActionButtonMMD(
                onClick = { if (composeEnabled) performClick() },
                shape = if (roundedSquareState) RoundedCornerShape(16.dp) else CircleShape,
                containerColor = if (primaryState.xor(activeState)) Color.Black else Color.White,
                contentColor = if (primaryState.xor(activeState)) Color.White else Color.Black,
                modifier = Modifier,
            ) {
                if (iconRes != 0) Icon(
                    painter = painterResource(iconRes),
                    contentDescription = contentDescription?.toString(),
                    tint = if (primaryState.xor(activeState)) Color.White else Color.Black,
                )
            }
        }
    }

    companion object { private const val ANDROID_NS = "http://schemas.android.com/apk/res/android" }
}
