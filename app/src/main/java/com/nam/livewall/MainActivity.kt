package com.nam.livewall

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.core.content.IntentCompat
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF8F7BFF), surface = Color(0xFF1C1B2E))) {
                Screen()
            }
        }
        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    /** Gallery "Set as" / Share -> this app. Import the file, then open the live wallpaper confirm screen. */
    private fun handleIncoming(i: Intent?) {
        val uri: Uri = when (i?.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_ATTACH_DATA, Intent.ACTION_VIEW -> i.data
            else -> null
        } ?: return
        i?.action = null
        Thread {
            val ok = Importer.import(this, uri)
            runOnUiThread {
                if (!ok) {
                    Toast.makeText(this, R.string.copy_error, Toast.LENGTH_SHORT).show()
                } else {
                    val active = WallpaperManager.getInstance(this).wallpaperInfo?.component?.className ==
                        LiveWallpaperService::class.java.name
                    if (active) Toast.makeText(this, R.string.updated, Toast.LENGTH_SHORT).show()
                    else openChooser(this)
                }
            }
        }.start()
    }
}

fun openChooser(ctx: Context) {
    ctx.startActivity(
        Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(ctx, LiveWallpaperService::class.java)
        )
    )
}

@Composable
fun Screen() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var mode by remember { mutableIntStateOf(prefs.mode) }
    var parallax by remember { mutableStateOf(prefs.parallax) }
    var crisp by remember { mutableStateOf(prefs.crisp) }
    var name by remember { mutableStateOf(if (prefs.file() != null) prefs.name else "") }
    // Read once: getApplicationLocales() is a binder call on Android 13+, too costly to run on every recomposition.
    val lang = remember { AppCompatDelegate.getApplicationLocales()[0]?.language ?: Locale.getDefault().language }
    val errorMsg = stringResource(R.string.copy_error)
    val cardColor = Color(0xFF26244A)
    val soft = Color(0xFFB9B3E6)

    DisposableEffect(Unit) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            name = if (prefs.file() != null) prefs.name else ""
        }
        prefs.sp.registerOnSharedPreferenceChangeListener(l)
        onDispose { prefs.sp.unregisterOnSharedPreferenceChangeListener(l) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        Thread {
            val ok = Importer.import(ctx, uri)
            if (!ok) Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, errorMsg, Toast.LENGTH_SHORT).show() }
        }.start()
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2A1B5E), Color(0xFF12111F))))) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.title), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(stringResource(R.string.subtitle), color = soft, fontSize = 14.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("en" to "EN", "vi" to "VI").forEach { (tag, label) ->
                        FilterChip(
                            selected = lang == tag,
                            onClick = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) },
                            label = { Text(label) }
                        )
                    }
                }
            }

            ElevatedCard(shape = RoundedCornerShape(24.dp), colors = CardDefaults.elevatedCardColors(containerColor = cardColor)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        if (name.isEmpty()) stringResource(R.string.none) else stringResource(R.string.selected, name),
                        color = Color.White, fontSize = 16.sp
                    )
                    Button(
                        onClick = { picker.launch(arrayOf("image/*", "video/*")) },
                        modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)
                    ) { Text(stringResource(R.string.pick), fontSize = 16.sp) }
                }
            }

            ElevatedCard(shape = RoundedCornerShape(24.dp), colors = CardDefaults.elevatedCardColors(containerColor = cardColor)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.mode_title), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    listOf(
                        listOf(Prefs.AUTO to R.string.auto, Prefs.FILL to R.string.fill, Prefs.FIT to R.string.fit),
                        listOf(Prefs.STRETCH to R.string.stretch, Prefs.ORIGINAL to R.string.original)
                    ).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowItems.forEach { (m, res) ->
                                FilterChip(
                                    selected = mode == m,
                                    onClick = { mode = m; prefs.mode = m },
                                    label = { Text(stringResource(res)) }
                                )
                            }
                        }
                    }
                    Text(stringResource(R.string.mode_note), color = soft, fontSize = 12.sp)
                    HorizontalDivider(color = Color(0x33FFFFFF))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.parallax), color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.parallax_desc), color = soft, fontSize = 12.sp)
                        }
                        Switch(checked = parallax, onCheckedChange = { parallax = it; prefs.parallax = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.crisp), color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.crisp_desc), color = soft, fontSize = 12.sp)
                        }
                        Switch(checked = crisp, onCheckedChange = { crisp = it; prefs.crisp = it })
                    }
                }
            }

            Button(
                onClick = { openChooser(ctx) },
                enabled = name.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)
            ) { Text(stringResource(R.string.set_wall), fontSize = 17.sp, fontWeight = FontWeight.Bold) }
        }
    }
}
