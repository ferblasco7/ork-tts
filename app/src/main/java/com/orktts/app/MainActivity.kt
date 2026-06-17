package com.orktts.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val openEpub = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadBook(uri)
    }

    private val requestNotifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        lifecycleScope.launch {
            ReadingPosition.load(this@MainActivity)?.let { saved ->
                loadBook(Uri.parse(saved.bookUri))
            }
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ReaderScreen(
                        onOpenEpub = { openEpub.launch(arrayOf("application/epub+zip")) },
                        onPlay = { sendAction(ReaderService.ACTION_PLAY) },
                        onPause = { sendAction(ReaderService.ACTION_PAUSE) },
                        onSkipBack = { sendAction(ReaderService.ACTION_SKIP_BACK) },
                        onSkipForward = { sendAction(ReaderService.ACTION_SKIP_FORWARD) }
                    )
                }
            }
        }
    }

    private fun loadBook(uri: Uri) {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val intent = Intent(this, ReaderService::class.java)
            .setAction(ReaderService.ACTION_LOAD)
            .putExtra(ReaderService.EXTRA_URI, uri.toString())
        startForegroundService(intent)
    }

    private fun sendAction(action: String) {
        startForegroundService(Intent(this, ReaderService::class.java).setAction(action))
    }
}

@Composable
fun ReaderScreen(
    onOpenEpub: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit
) {
    val state by ReaderState.state.collectAsState()
    var showSettings by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        val coverBytes = state.book?.coverBytes
        if (coverBytes != null) {
            val bitmap = remember(coverBytes) {
                BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size).asImageBitmap()
            }
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().alpha(0.35f),
                contentScale = ContentScale.Crop
            )
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onOpenEpub) { Text("Abrir EPUB") }
                Button(onClick = { showSettings = true }) { Text("Ajustes de voz") }
            }

            when {
                state.loading -> Text("Cargando libro…")
                state.error != null -> Text("Error: ${state.error}")
                state.book != null -> {
                    val book = state.book!!
                    Text(book.title, style = MaterialTheme.typography.titleLarge)
                    val chapter = book.chapters.getOrNull(state.chapterIndex)
                    Text(chapter?.title ?: "")
                    Text(chapter?.sentences?.getOrNull(state.sentenceIndex) ?: "")

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = onSkipBack) { Text("⏪") }
                        if (state.isPlaying) {
                            Button(onClick = onPause) { Text("Pausa") }
                        } else {
                            Button(onClick = onPlay) { Text("Reproducir") }
                        }
                        Button(onClick = onSkipForward) { Text("⏩") }
                    }
                }
                else -> Text("Ningún libro abierto")
            }
        }
    }

    if (showSettings) {
        VoiceSettingsDialog(onDismiss = { showSettings = false })
    }
}

@Composable
fun VoiceSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val settings by VoiceSettingsStore.flow(context).collectAsState(initial = VoiceSettings())

    var pitch by remember(settings) { mutableStateOf(settings.pitch) }
    var speed by remember(settings) { mutableStateOf(settings.speed) }
    var pauseMs by remember(settings) { mutableStateOf(settings.pauseMs.toFloat()) }

    fun persist() {
        scope.launch {
            VoiceSettingsStore.save(context, VoiceSettings(pitch, speed, pauseMs.toInt()))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajustes de voz") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tono: ${"%.2f".format(pitch)}")
                Slider(
                    value = pitch,
                    onValueChange = { pitch = it },
                    onValueChangeFinished = { persist() },
                    valueRange = 0.5f..2f
                )
                Text("Velocidad: ${"%.2f".format(speed)}")
                Slider(
                    value = speed,
                    onValueChange = { speed = it },
                    onValueChangeFinished = { persist() },
                    valueRange = 0.5f..2f
                )
                Text("Pausa entre frases: ${pauseMs.toInt()} ms")
                Slider(
                    value = pauseMs,
                    onValueChange = { pauseMs = it },
                    onValueChangeFinished = { persist() },
                    valueRange = 0f..1000f
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        }
    )
}
