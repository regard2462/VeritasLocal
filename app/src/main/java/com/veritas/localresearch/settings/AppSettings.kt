package com.veritas.localresearch.settings

import android.content.Context
import com.veritas.localresearch.i18n.I18n
import com.veritas.localresearch.i18n.tr
import com.veritas.localresearch.scraping.SearchEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Laenge und Ausfuehrlichkeit der Antwort. [tokenFactor] skaliert das Schreibbudget, [extraFacts] die Zahl der
 * gesuchten Fakten, [sentences] die Satzzahl je Abschnitt; [outline] = Gliederung in Abschnitte.
 */
enum class AnswerLength(private val key: String, val tokenFactor: Float, val extraFacts: Int, val sentences: String, val outline: Boolean) {
    SHORT("len_short", 0.45f, -2, "2 bis 4", false),
    MEDIUM("len_medium", 1.0f, 0, "3 bis 6", true),
    LONG("len_long", 1.8f, 4, "5 bis 9", true);

    val label: String get() = tr(key)
}

/** Woraus die Antwort kommt: Websuche, eigene Dokumente oder beides. */
enum class SourceMode(private val key: String) {
    WEB("src_web"), DOCS("src_docs"), BOTH("src_both");

    val label: String get() = tr(key)
}

data class AppSettings(
    val enabledEngines: Set<String> = SearchEngine.entries.filter { it.enabledByDefault }.map { it.id }.toSet(),
    val searxngUrl: String = "",
    val useWikipedia: Boolean = true,
    val useArxiv: Boolean = true,
    val embeddingPresetId: String = "e5-small-onnx",
    val defaultDepth: Int = 2,
    val showTimings: Boolean = true,
    val useReranker: Boolean = true,
    val answerLength: AnswerLength = AnswerLength.MEDIUM,
    /** Selbst festgelegte Laenge der Antwort in Woertern; 0 = Stufe aus [answerLength]. */
    val answerWords: Int = 0,
    val citationStyle: com.veritas.localresearch.export.CitationStyle = com.veritas.localresearch.export.CitationStyle.APA7,
    val sourceMode: SourceMode = SourceMode.WEB,
    /** Desktop: Modell auf der Grafikkarte rechnen lassen (Vulkan), wenn eine da ist. */
    val useGpu: Boolean = true,
    /** Sprache der Oberflaeche als Code (de, en, ...); leer = Systemsprache. */
    val uiLanguage: String = ""
) {
    fun engines(): List<SearchEngine> = SearchEngine.entries.filter { it.id in enabledEngines }
}

class SettingsStore(context: Context) {
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    /** Eigene Datei fuer Budgets und Pausen der Suchmaschinen. */
    val enginePrefs = context.getSharedPreferences("search_engines", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val current: AppSettings get() = _state.value

    private companion object {
        /** Suchmaschinen bis 1.5.0; gespeicherte Einstellungen ohne "engines_known" kennen nur diese. */
        val LEGACY_ENGINES = setOf("ddg", "bing", "startpage", "mojeek", "marginalia", "searxng")
    }

    init {
        I18n.setPreference(_state.value.uiLanguage)
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            enabledEngines = prefs.getStringSet("engines", null)?.toSet()?.let { saved ->
                // Suchmaschinen, die nach dem letzten Speichern dazukamen, mit ihrer Voreinstellung aufnehmen
                val known = prefs.getStringSet("engines_known", null) ?: LEGACY_ENGINES
                saved + SearchEngine.entries.filter { it.enabledByDefault && it.id !in known }.map { it.id }
            } ?: d.enabledEngines,
            searxngUrl = prefs.getString("searxng", d.searxngUrl).orEmpty(),
            useWikipedia = prefs.getBoolean("wikipedia", d.useWikipedia),
            useArxiv = prefs.getBoolean("arxiv", d.useArxiv),
            embeddingPresetId = prefs.getString("embedding", d.embeddingPresetId) ?: d.embeddingPresetId,
            defaultDepth = prefs.getInt("depth", d.defaultDepth).coerceIn(1, 3),
            showTimings = prefs.getBoolean("timings", d.showTimings),
            useReranker = prefs.getBoolean("reranker", d.useReranker),
            answerLength = AnswerLength.entries.firstOrNull { it.name == prefs.getString("length", null) } ?: d.answerLength,
            answerWords = prefs.getInt("words", d.answerWords),
            citationStyle = com.veritas.localresearch.export.CitationStyle.entries.firstOrNull { it.name == prefs.getString("citation", null) } ?: d.citationStyle,
            sourceMode = SourceMode.entries.firstOrNull { it.name == prefs.getString("sources", null) } ?: d.sourceMode,
            useGpu = prefs.getBoolean("gpu", d.useGpu),
            uiLanguage = prefs.getString("ui_lang", d.uiLanguage).orEmpty()
        )
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        _state.value = next
        I18n.setPreference(next.uiLanguage)
        prefs.edit()
            .putStringSet("engines", next.enabledEngines)
            .putStringSet("engines_known", SearchEngine.entries.map { it.id }.toSet())
            .putString("searxng", next.searxngUrl)
            .putBoolean("wikipedia", next.useWikipedia)
            .putBoolean("arxiv", next.useArxiv)
            .putString("embedding", next.embeddingPresetId)
            .putInt("depth", next.defaultDepth)
            .putBoolean("timings", next.showTimings)
            .putBoolean("reranker", next.useReranker)
            .putString("length", next.answerLength.name)
            .putInt("words", next.answerWords)
            .putString("citation", next.citationStyle.name)
            .putString("sources", next.sourceMode.name)
            .putBoolean("gpu", next.useGpu)
            .putString("ui_lang", next.uiLanguage)
            .apply()
    }
}
