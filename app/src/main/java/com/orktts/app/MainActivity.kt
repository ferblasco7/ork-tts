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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_URI = "open_uri"
    }

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

        intent?.getStringExtra(EXTRA_OPEN_URI)?.let { loadBook(Uri.parse(it)) }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ReaderScreen(
                        onOpenEpub = { openEpub.launch(arrayOf("application/epub+zip")) },
                        onOpenBook = { uri -> loadBook(Uri.parse(uri)) },
                        onPlay = { sendAction(ReaderService.ACTION_PLAY) },
                        onPause = { sendAction(ReaderService.ACTION_PAUSE) },
                        onSkipBack = { sendAction(ReaderService.ACTION_SKIP_BACK) },
                        onSkipForward = { sendAction(ReaderService.ACTION_SKIP_FORWARD) },
                        onJumpChapter = { index -> sendChapterJump(index) }
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

    private fun sendChapterJump(index: Int) {
        startForegroundService(
            Intent(this, ReaderService::class.java)
                .setAction(ReaderService.ACTION_JUMP_CHAPTER)
                .putExtra(ReaderService.EXTRA_CHAPTER, index)
        )
    }
}

/** Greedily packs sentences into screen-sized "pages" so the reader sees a full page, not one line. */
private data class Page(val sentenceRange: IntRange, val text: String)

private fun buildPages(sentences: List<String>, maxChars: Int = 1200): List<Page> {
    if (sentences.isEmpty()) return emptyList()
    val pages = ArrayList<Page>()
    var start = 0
    val sb = StringBuilder()
    for (i in sentences.indices) {
        val s = sentences[i]
        if (sb.isNotEmpty() && sb.length + s.length > maxChars) {
            pages.add(Page(start until i, sb.toString().trim()))
            sb.clear()
            start = i
        }
        sb.append(s).append(' ')
    }
    pages.add(Page(start until sentences.size, sb.toString().trim()))
    return pages
}

@Composable
fun ReaderScreen(
    onOpenEpub: () -> Unit,
    onOpenBook: (String) -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onJumpChapter: (Int) -> Unit
) {
    val state by ReaderState.state.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    var showLibrary by remember { mutableStateOf(true) }
    var showChapterMenu by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        val coverBytes = state.book?.coverBytes
        if (coverBytes != null && !showLibrary) {
            val bitmap = remember(coverBytes) {
                BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size).asImageBitmap()
            }
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().alpha(0.55f),
                contentScale = ContentScale.Crop
            )
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
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
                if (state.book != null) {
                    Button(onClick = { showLibrary = !showLibrary }) {
                        Text(if (showLibrary) "Volver a la lectura" else "Mis libros")
                    }
                }
                if (!showLibrary && state.book != null) {
                    Button(onClick = { showSettings = true }) { Text("Ajustes de voz") }
                }
            }

            if (showLibrary) {
                LibraryList(onOpenBook = { uri ->
                    onOpenBook(uri)
                    showLibrary = false
                })
            } else when {
                state.loading -> Text("Cargando libro…")
                state.error != null -> Text("Error: ${state.error}")
                state.book != null -> {
                    val book = state.book!!
                    Text(book.title, style = MaterialTheme.typography.titleLarge)

                    Box {
                        Text(
                            book.chapters.getOrNull(state.chapterIndex)?.title ?: "",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.clickable { showChapterMenu = true }
                        )
                        DropdownMenu(expanded = showChapterMenu, onDismissRequest = { showChapterMenu = false }) {
                            book.chapters.forEachIndexed { index, chapter ->
                                DropdownMenuItem(
                                    text = { Text(chapter.title) },
                                    onClick = {
                                        showChapterMenu = false
                                        onJumpChapter(index)
                                    }
                                )
                            }
                        }
                    }

                    val chapter = book.chapters.getOrNull(state.chapterIndex)
                    val pages = remember(chapter) { buildPages(chapter?.sentences ?: emptyList()) }
                    val currentPage = pages.find { state.sentenceIndex in it.sentenceRange } ?: pages.lastOrNull()
                    Text(
                        currentPage?.text ?: "",
                        style = MaterialTheme.typography.bodySmall
                    )

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
fun LibraryList(onOpenBook: (String) -> Unit) {
    val context = LocalContext.current
    val entries by LibraryStore.flow(context).collectAsState(initial = emptyList())

    if (entries.isEmpty()) {
        Text("Ningún libro abierto todavía. Pulsa \"Abrir EPUB\" para añadir uno.")
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(entries) { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenBook(entry.bookUri) },
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val cover = entry.coverPath?.let { BitmapFactory.decodeFile(it) }
                if (cover != null) {
                    Image(
                        bitmap = cover.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
                Column {
                    Text(entry.title, style = MaterialTheme.typography.titleSmall)
                    Text("Capítulo ${entry.chapterIndex + 1}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun VoiceSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by VoiceSettingsStore.flow(context).collectAsState(initial = VoiceSettings())

    var pitch by remember(settings) { mutableStateOf(settings.pitch) }
    var speed by remember(settings) { mutableStateOf(settings.speed) }
    var pauseMs by remember(settings) { mutableStateOf(settings.pauseMs.toFloat()) }
    var eqCutBass by remember(settings) { mutableStateOf(settings.eqCutBass) }

    fun persist() {
        scope.launch {
            VoiceSettingsStore.save(context, VoiceSettings(pitch, speed, pauseMs.toInt(), eqCutBass))
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
                Text("Velocidad: ${"%.2f".format(speed)}x")
                Slider(
                    value = speed,
                    onValueChange = { speed = it },
                    onValueChangeFinished = { persist() },
                    valueRange = 0.5f..5f
                )
                Text("Pausa entre frases: ${pauseMs.toInt()} ms")
                Slider(
                    value = pauseMs,
                    onValueChange = { pauseMs = it },
                    onValueChangeFinished = { persist() },
                    valueRange = 0f..1000f
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Quitar graves (<700Hz, para cascos)")
                    Switch(
                        checked = eqCutBass,
                        onCheckedChange = {
                            eqCutBass = it
                            persist()
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        }
    )
}
