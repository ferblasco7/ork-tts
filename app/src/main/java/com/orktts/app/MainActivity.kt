package com.orktts.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
            MaterialTheme {
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

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(onClick = onOpenEpub) { Text("Abrir EPUB") }

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
