package com.domonation.camera

import android.app.Dialog
import android.content.SharedPreferences
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.provider.DocumentsContract
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.core.view.WindowCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import com.mudita.mmd.ThemeMMD
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.radio_button.RadioButtonMMD
import com.mudita.mmd.components.switcher.SwitchMMD
import com.mudita.mmd.components.nav_bar.NavigationBarItemMMD
import com.mudita.mmd.components.nav_bar.NavigationBarMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD

internal class SettingsDialogController(
    private val activity: ComponentActivity,
    private val prefs: SharedPreferences,
    private val host: Host,
) {
    interface Host {
        fun chooseFolder()
        fun onSettingsDismissed()
    }

    fun show() {
        val dialog = ComponentDialog(activity, android.R.style.Theme_Material_Light_NoActionBar)
        val compose = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { ThemeMMD(colorScheme = MmdUi.domonationColorScheme) { SettingsScreen(dialog) } }
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dialog.dismiss(); true
            } else false
        }
        dialog.setOnDismissListener { host.onSettingsDismissed() }
        dialog.setContentView(compose)
        dialog.show()
        dialog.window?.apply {
            WindowCompat.setDecorFitsSystemWindows(this, true)
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.WHITE))
            statusBarColor = android.graphics.Color.WHITE
            navigationBarColor = android.graphics.Color.WHITE
            decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    @Composable
    @OptIn(ExperimentalMaterial3Api::class)
    private fun SettingsScreen(dialog: Dialog) {
        var tab by remember { mutableIntStateOf(0) }
        val destinations = listOf(
            Triple("General", R.drawable.ic_settings, "General settings"),
            Triple("Photo", R.drawable.ic_photo, "Photo settings"),
            Triple("Video", R.drawable.ic_video, "Video settings"),
            Triple("Timelapse", R.drawable.ic_timelapse, "Timelapse settings"),
        )
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.White,
            topBar = {
                Box(Modifier.fillMaxWidth().height(64.dp)) {
                    TopAppBarMMD(
                        title = { TextMMD("Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = { dialog.dismiss() }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_back),
                                    contentDescription = "Close settings",
                                    tint = Color.Black,
                                    modifier = Modifier.size(44.dp),
                                )
                            }
                        },
                        showDivider = false,
                    )
                    HorizontalDividerMMD(color = Color.Black, thickness = 3.dp,
                        modifier = Modifier.align(Alignment.BottomCenter))
                }
            },
            bottomBar = {
                Column(Modifier.fillMaxWidth().height(88.dp).background(Color.White)) {
                    HorizontalDividerMMD(color = Color.Black, thickness = 3.dp)
                    NavigationBarMMD(
                        modifier = Modifier.weight(1f),
                        containerColor = Color.White,
                        contentColor = Color.Black,
                        windowInsets = WindowInsets(0),
                    ) {
                        destinations.forEachIndexed { index, (label, icon, description) ->
                            NavigationBarItemMMD(
                                selected = tab == index,
                                onClick = { tab = index },
                                icon = {
                                    Icon(
                                        painter = painterResource(icon),
                                        contentDescription = description,
                                        modifier = Modifier.size(24.dp),
                                    )
                                },
                                label = { TextMMD(label, fontWeight = if (tab == index) FontWeight.Bold else FontWeight.Normal) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Column(
                Modifier.fillMaxSize().padding(innerPadding).background(Color.White)
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                when (tab) {
                    0 -> GeneralSettings(dialog)
                    1 -> PhotoSettings()
                    2 -> VideoSettings()
                    else -> TimelapseSettings()
                }
            }
        }
    }

    @Composable
    private fun GeneralSettings(dialog: Dialog) {
        Section("General")
        ToggleRow("Review before save", KEY_REVIEW, false)
        ToggleRow("Volume buttons control shutter", KEY_VOLUME_SHUTTER, true)
        ToggleRow("Flip front camera horizontally", KEY_FLIP_FRONT_CAMERA, true)
        Section("Save location")
        ButtonMMD(
            onClick = { dialog.dismiss(); host.chooseFolder() },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            TextMMD(
                selectedFolderLabel(),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }

    private fun selectedFolderLabel(): String {
        val stored = prefs.getString(KEY_TREE, null) ?: return "Pictures and Movies / DomonationCamera"
        return try {
            val uri = Uri.parse(stored)
            val documentId = DocumentsContract.getTreeDocumentId(uri)
            when {
                documentId.startsWith("primary:") -> {
                    val relative = documentId.removePrefix("primary:").replace(':', '/')
                    if (relative.isBlank()) "Internal storage" else "Internal storage/$relative"
                }
                documentId.contains(':') -> documentId.replace(':', '/')
                documentId.isNotBlank() -> documentId
                else -> uri.lastPathSegment ?: stored
            }
        } catch (_: Exception) {
            Uri.parse(stored).lastPathSegment ?: stored
        }
    }

    @Composable
    private fun PhotoSettings() {
        Section("Photo")
        ToggleRow("Embed EXIF thumbnail", KEY_THUMB, true)
        Note("The embedded thumbnail improves browsing on devices such as the Canon SELPHY.")
    }

    @Composable
    private fun VideoSettings() {
        Section("Video")
        ToggleRow("Record audio", KEY_VIDEO_AUDIO, true)
        Note("Turn this off for silent video without microphone permission.")
    }

    @Composable
    private fun TimelapseSettings() {
        Section("Timelapse")
        Note("Timelapse frames stay in temporary app storage and are deleted after the video is created. Only the video is saved.")
        HorizontalChoiceRow(
            "Capture interval (seconds)",
            KEY_LAPSE,
            listOf("1" to 1, "2" to 2, "5" to 5, "10" to 10),
            default = 5,
        )
        HorizontalChoiceRow(
            "Playback frame rate (FPS)",
            KEY_LAPSE_FPS,
            listOf("12" to 12, "15" to 15, "24" to 24, "30" to 30),
            default = 24,
        )
    }

    @Composable
    private fun HorizontalChoiceRow(
        label: String,
        key: String,
        choices: List<Pair<String, Int>>,
        default: Int,
    ) {
        val allowed = choices.map { it.second }
        val stored = prefs.getInt(key, default)
        val initial = if (stored in allowed) stored else default
        if (stored != initial) prefs.edit().putInt(key, initial).apply()
        var selected by remember { mutableIntStateOf(initial) }
        TextMMD(label, fontWeight = FontWeight.Bold,
            modifier = Modifier.height(36.dp).wrapContentHeight())
        Row(Modifier.fillMaxWidth().height(52.dp)) {
            choices.forEach { (text, value) ->
                Row(
                    modifier = Modifier.weight(1f).fillMaxHeight().clickable {
                        selected = value
                        prefs.edit().putInt(key, value).apply()
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    RadioButtonMMD(selected = selected == value, onClick = {
                        selected = value
                        prefs.edit().putInt(key, value).apply()
                    })
                    TextMMD(text, modifier = Modifier.padding(start = 2.dp))
                }
            }
        }
        HorizontalDividerMMD(color = Color.Black, thickness = 1.dp)
    }

    @Composable
    private fun Section(text: String) {
        Spacer(Modifier.height(8.dp))
        TextMMD(text, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.height(40.dp).wrapContentHeight())
        HorizontalDividerMMD(color = Color.Black, thickness = 1.dp)
    }

    @Composable
    private fun Note(text: String) {
        TextMMD(text, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).wrapContentHeight())
    }

    @Composable
    private fun ToggleRow(label: String, key: String, default: Boolean) {
        var checked by remember { mutableStateOf(prefs.getBoolean(key, default)) }
        Row(
            Modifier.fillMaxWidth().height(56.dp).clickable {
                checked = !checked; prefs.edit().putBoolean(key, checked).apply()
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextMMD(label, modifier = Modifier.weight(1f))
            SwitchMMD(checked = checked, onCheckedChange = {
                checked = it; prefs.edit().putBoolean(key, it).apply()
            })
        }
        HorizontalDividerMMD(color = Color.Black, thickness = 1.dp)
    }

    @Composable
    private fun ChoiceRow(
        label: String,
        key: String,
        choices: List<Pair<String, Int>>,
        default: Int = choices.first().second,
        onChange: () -> Unit = {},
    ) {
        val allowed = choices.map { it.second }
        val stored = prefs.getInt(key, default)
        val initial = if (stored in allowed) stored else default
        if (stored != initial) prefs.edit().putInt(key, initial).apply()
        var selected by remember { mutableIntStateOf(initial) }
        TextMMD(label, fontWeight = FontWeight.Bold, modifier = Modifier.height(40.dp).wrapContentHeight())
        choices.forEach { (text, value) ->
            Row(
                Modifier.fillMaxWidth().height(52.dp).clickable {
                    selected = value; prefs.edit().putInt(key, value).apply(); onChange()
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButtonMMD(selected = selected == value, onClick = {
                    selected = value; prefs.edit().putInt(key, value).apply(); onChange()
                })
                TextMMD(text, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }

    @Composable
    private fun NumberChoice(key: String, customKey: String, presets: List<Int>, customDefault: Int, min: Int, max: Int) {
        var selected by remember { mutableIntStateOf(prefs.getInt(key, presets.first())) }
        var custom by remember { mutableStateOf(prefs.getInt(customKey, customDefault).toString()) }
        val isCustom = selected !in presets
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            presets.forEach { value ->
                Row(
                    Modifier.height(52.dp).clickable { selected = value; prefs.edit().putInt(key, value).apply() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButtonMMD(selected = selected == value, onClick = {
                        selected = value; prefs.edit().putInt(key, value).apply()
                    })
                    TextMMD(value.toString())
                }
            }
            Row(
                Modifier.height(52.dp).clickable {
                    val value = custom.toIntOrNull()?.coerceIn(min, max) ?: customDefault
                    selected = value; prefs.edit().putInt(key, value).putInt(customKey, value).apply()
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButtonMMD(selected = isCustom, onClick = {
                    val value = custom.toIntOrNull()?.coerceIn(min, max) ?: customDefault
                    selected = value; prefs.edit().putInt(key, value).putInt(customKey, value).apply()
                })
                TextMMD("Custom")
            }
        }
        if (isCustom) {
            TextFieldMMD(
                value = custom,
                onValueChange = { input ->
                    if (input.all(Char::isDigit)) {
                        custom = input
                        input.toIntOrNull()?.takeIf { it in min..max }?.let {
                            selected = it; prefs.edit().putInt(key, it).putInt(customKey, it).apply()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { TextMMD("$min–$max") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
        }
    }

    companion object {
        private const val KEY_TREE = "save_tree"
        private const val KEY_REVIEW = "review_before_save"
        private const val KEY_THUMB = "exif_thumbnail"
        private const val KEY_VIDEO_AUDIO = "video_audio"
        private const val KEY_VOLUME_SHUTTER = "volume_shutter"
        private const val KEY_FLIP_FRONT_CAMERA = "flip_front_camera"
        private const val KEY_LAPSE = "timelapse_seconds"
        private const val KEY_LAPSE_FPS = "timelapse_fps"
    }
}
