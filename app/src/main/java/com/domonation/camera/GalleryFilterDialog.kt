package com.domonation.camera

import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.mudita.mmd.ThemeMMD
import com.mudita.mmd.components.bottom_sheet.ModalBottomSheetMMD
import com.mudita.mmd.components.bottom_sheet.rememberModalBottomSheetMMDState
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD

object GalleryFilterDialog {
    fun interface Listener { fun onFilterChanged(days: Int) }

    @JvmStatic
    fun show(activity: ComponentActivity, currentDays: Int, listener: Listener) {
        val dialog = ComponentDialog(activity, android.R.style.Theme_Material_Light_NoActionBar)
        val compose = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                ThemeMMD(colorScheme = MmdUi.domonationColorScheme) {
                    FilterSheet(
                        currentDays = currentDays,
                        onDismiss = { dialog.dismiss() },
                        onDone = { listener.onFilterChanged(it); dialog.dismiss() },
                    )
                }
            }
        }
        dialog.setContentView(compose)
        dialog.show()
        dialog.window?.apply {
            WindowCompat.setDecorFitsSystemWindows(this, true)
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun FilterSheet(currentDays: Int, onDismiss: () -> Unit, onDone: (Int) -> Unit) {
        val sheetState = rememberModalBottomSheetMMDState(skipPartiallyExpanded = true)
        ModalBottomSheetMMD(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = Color.White,
            contentColor = Color.Black,
            scrimColor = Color.Transparent,
            dragHandle = null,
            contentWindowInsets = { WindowInsets(0) },
        ) {
            Column(Modifier.fillMaxWidth().background(Color.White)) {
                HorizontalDividerMMD(color = Color.Black, thickness = 3.dp)
                TextMMD(
                    "Find photos from:",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 24.dp),
                )
                listOf(
                    "Today" to 1,
                    "Yesterday" to -1,
                    "Last week" to 7,
                    "Last month" to 30,
                ).forEach { (label, days) ->
                    OutlinedButtonMMD(
                        onClick = { onDone(days) },
                        modifier = Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 20.dp, vertical = 5.dp),
                    ) { TextMMD(label, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.height(12.dp))
                ButtonMMD(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 20.dp, vertical = 5.dp),
                ) { TextMMD("Cancel", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
