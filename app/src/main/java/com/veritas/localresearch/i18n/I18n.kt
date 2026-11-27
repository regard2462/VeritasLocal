package com.veritas.localresearch.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Sprache der Oberflaeche. Jede Sprache hat eine Tabelle Schluessel -> Text; fehlt ein Eintrag, gilt Englisch.
 * Platzhalter in den Texten: {0}, {1}, ...
 */
object I18n {
    /** [ocr]: Tesseract-Sprachdaten fuer die Texterkennung. */
    data class UiLanguage(val code: String, val nativeName: String, val ocr: String, val rtl: Boolean = false)

    val LANGUAGES = listOf(
        UiLanguage("de", "Deutsch", "deu"),
        UiLanguage("en", "English", "eng"),
        UiLanguage("fr", "Français", "fra"),
        UiLanguage("es", "Español", "spa"),
        UiLanguage("it", "Italiano", "ita"),
        UiLanguage("pt", "Português", "por"),
        UiLanguage("nl", "Nederlands", "nld"),
        UiLanguage("pl", "Polski", "pol"),
        UiLanguage("cs", "Čeština", "ces"),
        UiLanguage("sv", "Svenska", "swe"),
        UiLanguage("da", "Dansk", "dan"),
        UiLanguage("nb", "Norsk bokmål", "nor"),
        UiLanguage("fi", "Suomi", "fin"),
        UiLanguage("ro", "Română", "ron"),
        UiLanguage("hu", "Magyar", "hun"),
        UiLanguage("el", "Ελληνικά", "ell"),
        UiLanguage("tr", "Türkçe", "tur"),
        UiLanguage("ru", "Русский", "rus"),
        UiLanguage("uk", "Українська", "ukr"),
        UiLanguage("ar", "العربية", "ara", rtl = true),
        UiLanguage("he", "עברית", "heb", rtl = true),
        UiLanguage("hi", "हिन्दी", "hin"),
        UiLanguage("id", "Bahasa Indonesia", "ind"),
        UiLanguage("vi", "Tiếng Việt", "vie"),
        UiLanguage("th", "ไทย", "tha"),
        UiLanguage("zh", "中文（简体）", "chi_sim"),
        UiLanguage("ja", "日本語", "jpn"),
        UiLanguage("ko", "한국어", "kor")
    )

    internal val TABLES: Map<String, Map<String, String>> = mapOf(
        "de" to DE, "en" to EN, "fr" to FR, "es" to ES, "it" to IT, "pt" to PT, "nl" to NL, "pl" to PL, "cs" to CS,
        "sv" to SV, "da" to DA, "nb" to NB, "fi" to FI, "ro" to RO, "hu" to HU, "el" to EL, "tr" to TR, "ru" to RU,
        "uk" to UK, "ar" to AR, "he" to HE, "hi" to HI, "id" to ID, "vi" to VI, "th" to TH, "zh" to ZH, "ja" to JA,
        "ko" to KO
    )

    /** Gewaehlte Sprache; leer = Systemsprache. Compose liest den Wert als Zustand und zeichnet bei Wechsel neu. */
    var code by mutableStateOf(systemCode())
        private set

    fun setPreference(pref: String) {
        code = pref.takeIf { p -> LANGUAGES.any { it.code == p } } ?: systemCode()
    }

    fun systemCode(): String {
        val l = when (val raw = Locale.getDefault().language) {
            "iw" -> "he"
            "in" -> "id"
            "no", "nn" -> "nb"
            else -> raw
        }
        return if (LANGUAGES.any { it.code == l }) l else "en"
    }

    val current: UiLanguage get() = LANGUAGES.first { it.code == code }

    val locale: Locale get() = Locale.forLanguageTag(code)

    fun text(key: String): String = TABLES[code]?.get(key) ?: EN[key] ?: key
}

private val PLACEHOLDER = Regex("\\{(\\d)\\}")

/** Text zu [key] in der Sprache der Oberflaeche, Platzhalter {0}, {1} ... durch [args] ersetzt. */
fun tr(key: String, vararg args: Any?): String {
    val s = I18n.text(key)
    if (args.isEmpty()) return s
    return PLACEHOLDER.replace(s) { m -> args.getOrNull(m.groupValues[1].toInt())?.toString() ?: m.value }
}
