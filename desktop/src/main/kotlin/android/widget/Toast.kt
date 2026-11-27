package android.widget

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/** Desktop-Ersatz: kurze Meldung, die das Hauptfenster unten einblendet. */
class Toast private constructor(private val text: CharSequence) {
    fun show() {
        messages.value = text.toString() to System.nanoTime()
    }

    companion object {
        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 1

        /** Letzte Meldung mit Zeitstempel, damit gleiche Texte erneut erscheinen. */
        val messages = MutableStateFlow<Pair<String, Long>?>(null)

        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun makeText(context: Context, text: CharSequence, duration: Int) = Toast(text)
    }
}
