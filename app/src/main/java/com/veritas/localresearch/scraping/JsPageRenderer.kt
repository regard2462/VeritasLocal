package com.veritas.localresearch.scraping

/**
 * Laedt eine Seite mit JavaScript und fuehrt ein Skript darin aus (Ausweichweg fuer Seiten, die ihren Text erst
 * per JavaScript erzeugen). Auf Android eine unsichtbare WebView; auf dem Desktop gibt es keinen solchen Weg.
 */
interface JsPageRenderer {
    /** Ergebnis des Skripts als JSON-Text, oder null bei Fehler, Sperre oder Zeitueberschreitung. */
    suspend fun loadAndEval(url: String, jsScript: String, timeoutMs: Long): String?
    fun destroy()
}
