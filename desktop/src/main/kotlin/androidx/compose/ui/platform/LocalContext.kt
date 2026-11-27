package androidx.compose.ui.platform

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf

/** Desktop-Ersatz: der Datenordner der App als Context; das Hauptfenster setzt ihn. */
val LocalContext = staticCompositionLocalOf<Context> { error("Kein Context gesetzt") }
