package com.veritas.localresearch.desktop

import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.ViewModelStore
import com.veritas.localresearch.settings.AnswerLength
import com.veritas.localresearch.ui.ResearchScreen
import com.veritas.localresearch.ui.ResearchViewModel
import com.veritas.localresearch.ui.VeritasTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.system.exitProcess

/** Datenordner: -Dveritas.data, sonst %LOCALAPPDATA%\VeritasLocal bzw. ~/.local/share/VeritasLocal. */
private fun dataDir(): File {
    System.getProperty("veritas.data")?.let { return File(it) }
    val os = System.getProperty("os.name").orEmpty()
    return if (os.startsWith("Windows")) {
        File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "VeritasLocal")
    } else {
        val xdg = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.local/share")
        File(xdg, "VeritasLocal")
    }
}

/** Ordner mit llama.cpp: -Dveritas.nativeDir, sonst die mitgelieferten Ressourcen des Pakets. */
private fun nativeDir(): String {
    System.getProperty("veritas.nativeDir")?.takeIf { it.isNotBlank() }?.let { return it }
    return System.getProperty("compose.application.resources.dir").orEmpty()
}

/**
 * Start ohne Argumente: Fenster. Pruefstart ohne Fenster:
 * --autorun "<Frage>" --model <datei.gguf> [--depth 2] [--len LONG] [--tag name] [--shot bild.png] [--data ordner] [--log datei]
 *   [--doc datei.pdf] [--sources WEB|DOCS|BOTH]
 */
fun main(args: Array<String>) {
    val opts = args.toList().windowed(2, 1, partialWindows = false).filter { it[0].startsWith("--") }.associate { it[0] to it[1] }
    // Pruefstarts des fertigen Programms: Datenordner und Protokoll per Argument statt -D
    opts["--data"]?.let { System.setProperty("veritas.data", it) }
    opts["--log"]?.let { System.setProperty("veritas.log", it) }
    System.setProperty("veritas.nativeDir", nativeDir())
    val app = Application(dataDir())
    com.veritas.localresearch.docs.Ocr.engine = com.veritas.localresearch.docs.DesktopOcr()
    val autorunQ = opts["--autorun"]
    if (autorunQ != null) {
        exitProcess(runAutorun(app, autorunQ, opts))
    }

    val store = ViewModelStore()
    val vm = ResearchViewModel(app).also { store.put("research", it) }
    application {
        val state = rememberWindowState(size = DpSize(1000.dp, 860.dp))
        Window(
            onCloseRequest = { store.clear(); exitApplication() },
            state = state,
            title = "VeritasLocal",
            icon = appIcon()
        ) {
            CompositionLocalProvider(LocalContext provides app) {
                VeritasTheme {
                    Box(Modifier.fillMaxSize()) {
                        ResearchScreen(viewModel = vm)
                        ToastOverlay(Modifier.align(Alignment.BottomCenter).padding(24.dp))
                    }
                }
            }
        }
    }
}

/** Fensterbild aus den Ressourcen (dasselbe Bild wie das Programmsymbol). */
private fun appIcon(): BitmapPainter? = runCatching {
    val bytes = Main::class.java.getResourceAsStream("/veritas.png")!!.use { it.readBytes() }
    BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
}.getOrNull()

private object Main

/** Meldungen wie „PDF gespeichert“ drei Sekunden lang unten einblenden. */
@Composable
private fun ToastOverlay(modifier: Modifier) {
    val msg by Toast.messages.collectAsState()
    var visible by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(msg) {
        val m = msg ?: return@LaunchedEffect
        visible = m.first
        delay(3000)
        visible = null
    }
    visible?.let { text ->
        Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.inverseSurface, tonalElevation = 6.dp) {
            Text(text, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.inverseOnSurface)
        }
    }
}

/** Testlauf ohne Fenster; schreibt <daten>/autorun/<tag>.json und auf Wunsch ein Bild des Ergebnisses. */
private fun runAutorun(app: Application, question: String, opts: Map<String, String>): Int = runBlocking {
    System.setProperty("java.awt.headless", "true")
    val tag = opts["--tag"] ?: "run"
    val store = ViewModelStore()
    val vm = withContext(Dispatchers.Main) { ResearchViewModel(app).also { store.put("research", it) } }
    withContext(Dispatchers.Main) {
        opts["--len"]?.let { name ->
            AnswerLength.entries.firstOrNull { it.name == name }?.let { len -> vm.updateSettings { it.copy(answerLength = len) } }
        }
        vm.autorun(question, opts["--model"].orEmpty(), opts["--depth"]?.toIntOrNull() ?: 2, tag, opts["--doc"], opts["--sources"])
    }
    val out = File(app.filesDir, "autorun/$tag.json")
    while (!(out.exists() && out.length() > 0)) delay(1000)
    delay(1500)
    opts["--shot"]?.let { path -> withContext(Dispatchers.Main) { screenshot(app, vm, File(path)) } }
    withContext(Dispatchers.Main) { store.clear() }
    0
}

/** Bild des Hauptfensters ohne sichtbares Fenster (Pruefung der Darstellung). */
private fun screenshot(app: Application, vm: ResearchViewModel, file: File) {
    val scene = ImageComposeScene(width = 1000, height = 2400, density = Density(1f)) {
        CompositionLocalProvider(LocalContext provides app) {
            VeritasTheme { ResearchScreen(viewModel = vm) }
        }
    }
    try {
        scene.render(0)
        val img = scene.render(1_000_000_000L)
        img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let { file.writeBytes(it) }
    } finally {
        scene.close()
    }
}
