package com.veritas.localresearch.domain

/**
 * Erkennt die Sprache eines Textes: Schriften ohne Lateinbuchstaben ueber ihren Unicode-Block, Sprachen mit
 * Lateinschrift ueber haeufige Funktionswoerter. Reicht, um Suche, Quellen und Antwort in der Sprache der Frage
 * zu halten. Liefert null, wenn der Text zu kurz oder nicht eindeutig ist.
 */
object LanguageDetector {

    /** [name]: deutscher Sprachname fuer die Prompts; [wiki]: Kennung der Wikipedia-Ausgabe. */
    data class Language(val code: String, val name: String, val ddgRegion: String, val bingCountry: String, val wiki: String = code)

    val GERMAN = Language("de", "Deutsch", "de-de", "DE")
    val ENGLISH = Language("en", "Englisch", "us-en", "US")
    private val FRENCH = Language("fr", "Französisch", "fr-fr", "FR")
    private val SPANISH = Language("es", "Spanisch", "es-es", "ES")
    private val ITALIAN = Language("it", "Italienisch", "it-it", "IT")
    private val DUTCH = Language("nl", "Niederländisch", "nl-nl", "NL")
    private val PORTUGUESE = Language("pt", "Portugiesisch", "br-pt", "BR")
    private val POLISH = Language("pl", "Polnisch", "pl-pl", "PL")
    private val CZECH = Language("cs", "Tschechisch", "cz-cs", "CZ")
    private val SWEDISH = Language("sv", "Schwedisch", "se-sv", "SE")
    private val DANISH = Language("da", "Dänisch", "dk-da", "DK")
    private val NORWEGIAN = Language("nb", "Norwegisch", "no-no", "NO", wiki = "no")
    private val FINNISH = Language("fi", "Finnisch", "fi-fi", "FI")
    private val ROMANIAN = Language("ro", "Rumänisch", "ro-ro", "RO")
    private val HUNGARIAN = Language("hu", "Ungarisch", "hu-hu", "HU")
    private val TURKISH = Language("tr", "Türkisch", "tr-tr", "TR")
    private val INDONESIAN = Language("id", "Indonesisch", "id-en", "ID")
    private val VIETNAMESE = Language("vi", "Vietnamesisch", "vn-vi", "VN")
    private val GREEK = Language("el", "Griechisch", "gr-el", "GR")
    private val RUSSIAN = Language("ru", "Russisch", "ru-ru", "RU")
    private val UKRAINIAN = Language("uk", "Ukrainisch", "ua-uk", "UA")
    private val ARABIC = Language("ar", "Arabisch", "xa-ar", "SA")
    private val HEBREW = Language("he", "Hebräisch", "il-he", "IL")
    private val HINDI = Language("hi", "Hindi", "in-en", "IN")
    private val THAI = Language("th", "Thailändisch", "th-th", "TH")
    private val CHINESE = Language("zh", "Chinesisch", "cn-zh", "CN")
    private val JAPANESE = Language("ja", "Japanisch", "jp-jp", "JP")
    private val KOREAN = Language("ko", "Koreanisch", "kr-kr", "KR")

    private val STOPWORDS: Map<Language, Set<String>> = mapOf(
        GERMAN to setOf("der", "die", "das", "und", "ist", "nicht", "ein", "eine", "mit", "von", "zu", "den", "dem", "des",
            "auf", "für", "wie", "wird", "werden", "sind", "auch", "sich", "im", "in", "bei", "oder", "welche", "was",
            "wer", "wann", "warum", "wo", "über", "nach", "aus", "einer", "eines", "durch", "zum", "zur", "kann", "es"),
        ENGLISH to setOf("the", "and", "is", "are", "of", "to", "in", "for", "with", "on", "what", "how", "which", "when",
            "why", "who", "where", "does", "do", "this", "that", "be", "by", "from", "an", "a", "it", "as", "was",
            "were", "or", "can", "their", "has", "have", "about"),
        FRENCH to setOf("le", "la", "les", "et", "est", "des", "du", "un", "une", "pour", "dans", "avec", "que", "qui",
            "sur", "pas", "sont", "comment", "quel", "quelle", "au", "aux", "ce", "il", "elle"),
        SPANISH to setOf("el", "la", "los", "las", "y", "es", "de", "del", "un", "una", "para", "con", "que", "por",
            "en", "como", "cuál", "qué", "son", "se", "al", "lo"),
        ITALIAN to setOf("il", "lo", "la", "gli", "le", "e", "è", "di", "del", "della", "un", "una", "per", "con", "che",
            "come", "sono", "nel", "nella", "non", "si", "al"),
        DUTCH to setOf("de", "het", "een", "en", "is", "van", "voor", "met", "dat", "niet", "zijn", "hoe", "wat", "welke",
            "op", "ook", "worden", "wordt", "bij", "naar"),
        PORTUGUESE to setOf("o", "os", "as", "e", "é", "do", "da", "dos", "das", "um", "uma", "para", "com", "não",
            "que", "como", "são", "em", "no", "na", "qual", "quais", "pelo", "pela", "mais"),
        POLISH to setOf("i", "w", "z", "na", "się", "jest", "nie", "do", "że", "to", "jak", "co", "czy", "dla", "są",
            "od", "przez", "jaki", "jakie", "który", "która", "oraz", "ich", "po"),
        CZECH to setOf("a", "v", "se", "na", "je", "že", "to", "s", "z", "do", "jak", "co", "jsou", "pro", "které",
            "který", "není", "nebo", "od", "při", "jaké", "také", "by"),
        SWEDISH to setOf("och", "är", "att", "det", "som", "en", "ett", "på", "för", "med", "av", "inte", "har", "hur",
            "vad", "vilka", "vilken", "till", "om", "kan", "de", "den", "sig"),
        DANISH to setOf("og", "er", "at", "det", "som", "en", "et", "på", "for", "med", "af", "ikke", "har", "hvordan",
            "hvad", "hvilke", "hvilken", "til", "om", "kan", "de", "den", "sig", "jeg"),
        NORWEGIAN to setOf("og", "er", "at", "det", "som", "en", "et", "på", "for", "med", "av", "ikke", "har", "hvordan",
            "hva", "hvilke", "hvilken", "til", "om", "kan", "de", "den", "seg", "jeg"),
        FINNISH to setOf("ja", "on", "ei", "se", "että", "ovat", "kun", "mikä", "mitkä", "miten", "kuinka", "tai",
            "joka", "jotka", "myös", "sen", "ole", "oli", "mutta", "niin", "vai"),
        ROMANIAN to setOf("și", "este", "în", "de", "la", "cu", "pe", "un", "o", "nu", "care", "ce", "cum", "sunt",
            "pentru", "din", "mai", "lui", "fi", "sau", "acest"),
        HUNGARIAN to setOf("a", "az", "és", "hogy", "nem", "is", "van", "egy", "mi", "mit", "milyen", "hogyan", "vagy",
            "meg", "már", "csak", "mint", "ez", "azt", "kell", "mely", "melyek"),
        TURKISH to setOf("ve", "bir", "bu", "da", "de", "için", "ile", "ne", "nasıl", "nedir", "mi", "mı", "daha",
            "olan", "çok", "gibi", "hangi", "neden", "değil", "veya", "olarak"),
        INDONESIAN to setOf("dan", "yang", "di", "ke", "dari", "ini", "itu", "untuk", "dengan", "adalah", "tidak",
            "apa", "bagaimana", "ada", "pada", "juga", "atau", "akan", "mengapa", "dalam"),
        VIETNAMESE to setOf("và", "là", "của", "có", "không", "những", "các", "cho", "với", "trong", "được", "một",
            "này", "như", "thế", "nào", "gì", "tại", "sao", "người")
    )

    private val WORD = Regex("[\\p{L}]+")

    fun detect(text: String): Language? {
        val sample = text.take(4000)
        byScript(sample)?.let { return it }
        val words = WORD.findAll(sample.lowercase()).map { it.value }.toList()
        if (words.size < 3) return null
        val scores = STOPWORDS.mapValues { (_, set) -> words.count { it in set } }
        // Umlaute und ß sprechen deutlich fuer Deutsch, auch in kurzen Fragen
        val umlautBonus = words.count { w -> w.any { it in "äöüß" } }
        val withBonus = scores.mapValues { (lang, s) -> if (lang == GERMAN) s + umlautBonus else s }
        val sorted = withBonus.entries.sortedByDescending { it.value }
        val best = sorted[0]
        val second = sorted.getOrNull(1)?.value ?: 0
        if (best.value < 2) return null
        // Nur bei klarem Vorsprung entscheiden
        return if (best.value >= second * 1.5 + 1) best.key else null
    }

    /** Sprache aus der Schrift, wenn die Buchstaben ueberwiegend nicht lateinisch sind. */
    private fun byScript(text: String): Language? {
        val counts = HashMap<Character.UnicodeScript, Int>()
        var letters = 0
        for (ch in text) {
            if (!Character.isLetter(ch)) continue
            letters++
            val sc = runCatching { Character.UnicodeScript.of(ch.code) }.getOrNull() ?: continue
            counts[sc] = (counts[sc] ?: 0) + 1
        }
        if (letters < 2) return null
        val latin = counts[Character.UnicodeScript.LATIN] ?: 0
        if (latin * 2 >= letters) return null
        fun n(s: Character.UnicodeScript) = counts[s] ?: 0
        val kana = n(Character.UnicodeScript.HIRAGANA) + n(Character.UnicodeScript.KATAKANA)
        val han = n(Character.UnicodeScript.HAN)
        return when {
            kana > 0 -> JAPANESE
            n(Character.UnicodeScript.HANGUL) > 0 -> KOREAN
            han > 0 -> CHINESE
            n(Character.UnicodeScript.CYRILLIC) > 0 ->
                if (text.any { it in "іїєґІЇЄҐ" }) UKRAINIAN else RUSSIAN
            n(Character.UnicodeScript.GREEK) > 0 -> GREEK
            n(Character.UnicodeScript.ARABIC) > 0 -> ARABIC
            n(Character.UnicodeScript.HEBREW) > 0 -> HEBREW
            n(Character.UnicodeScript.DEVANAGARI) > 0 -> HINDI
            n(Character.UnicodeScript.THAI) > 0 -> THAI
            else -> null
        }
    }

    /** true, wenn der Text erkennbar in einer anderen Sprache als [lang] geschrieben ist. */
    fun isOtherLanguage(text: String, lang: Language): Boolean {
        val d = detect(text) ?: return false
        return d.code != lang.code
    }
}
