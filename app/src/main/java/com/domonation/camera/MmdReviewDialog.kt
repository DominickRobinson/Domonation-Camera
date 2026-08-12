package com.domonation.camera

import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.core.view.WindowCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mudita.mmd.ThemeMMD
import com.mudita.mmd.components.bottom_sheet.ModalBottomSheetMMD
import com.mudita.mmd.components.bottom_sheet.rememberModalBottomSheetMMDState
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD

object MmdReviewDialog {
    @JvmStatic fun show(
        activity: ComponentActivity,
        title: String,
        media: View,
        save: Runnable,
        discard: Runnable,
        closed: Runnable,
    ) {
        val dialog = ComponentDialog(activity, android.R.style.Theme_Material_Light_NoActionBar)
        var resolved = false
        fun resolve(action: Runnable) {
            if (!resolved) { resolved = true; action.run() }
            dialog.dismiss()
        }
        val compose = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                ThemeMMD(colorScheme = MmdUi.domonationColorScheme) {
                    ReviewScreen(
                        title = title,
                        media = media,
                        onClose = { resolve(discard) },
                        onSave = { resolve(save) },
                        onRetake = { resolve(discard) },
                    )
                }
            }
        }
        dialog.setOnCancelListener { resolve(discard) }
        dialog.setOnDismissListener {
            if (!resolved) { resolved = true; discard.run() }
            closed.run()
        }
        dialog.setContentView(compose)
        dialog.show()
        dialog.window?.apply {
            WindowCompat.setDecorFitsSystemWindows(this, true)
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.WHITE))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun ReviewScreen(
        title: String,
        media: View,
        onClose: () -> Unit,
        onSave: () -> Unit,
        onRetake: () -> Unit,
    ) {
        val sheetState = rememberModalBottomSheetMMDState(skipPartiallyExpanded = true)
        Column(Modifier.fillMaxSize().background(Color.White)) {
            Box(Modifier.fillMaxWidth().height(64.dp)) {
                TopAppBarMMD(
                    title = { TextMMD(title, fontSize = 28.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                    showDivider = false,
                )
                com.mudita.mmd.components.divider.HorizontalDividerMMD(
                    color = Color.Black,
                    thickness = 3.dp,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            AndroidView(factory = { media }, modifier = Modifier.fillMaxWidth().weight(1f))
            Spacer(Modifier.height(88.dp))
        }
        ModalBottomSheetMMD(
            onDismissRequest = onRetake,
            sheetState = sheetState,
            containerColor = Color.White,
            contentColor = Color.Black,
            scrimColor = Color.Transparent,
            dragHandle = null,
            shape = RectangleShape,
            contentWindowInsets = { WindowInsets(0) },
        ) {
            Column(Modifier.fillMaxWidth().height(88.dp).background(Color.White)) {
                com.mudita.mmd.components.divider.HorizontalDividerMMD(color = Color.Black, thickness = 3.dp)
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButtonMMD(
                        onClick = onRetake,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) {
                        TextMMD("Discard", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                    ButtonMMD(
                        onClick = onSave,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) {
                        TextMMD("Save", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                }
            }
        }
    }
}
