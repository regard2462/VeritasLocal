package com.veritas.localresearch.export

import com.veritas.localresearch.domain.SourceType
import com.veritas.localresearch.i18n.tr
import com.veritas.localresearch.domain.VerifiedClaim
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Zitierstile fuer den Export. [numeric]: Nummern im Text, Verzeichnis nach Reihenfolge; sonst Autor-Jahr, alphabetisch. */
enum class CitationStyle(private val key: String, val numeric: Boolean) {
    APA7("APA 7", false),
    HARVARD("Harvard", false),
    CHICAGO("cs_chicago", false),
    IEEE("IEEE", true),
    DIN_ISO_690("cs_din", true);

    /** Name in der Sprache der Oberflaeche; Eigennamen ohne Uebersetzung bleiben wie sie sind. */
    val label: String get() = tr(key)
}

/** Textstueck mit Auszeichnung; [link] ist eine Webadresse. */
data class Seg(val text: String, val italic: Boolean = false, val link: String? = null)

/** Eine Quelle des Literaturverzeichnisses mit den Angaben, die die Seite preisgibt. */
data class BibEntry(
    val number: Int,
    val authors: List<String>,
    /** Kein Personenautor: Organisation bzw. Seitenname steht an Autorstelle. */
    val corporateAuthor: String,
    val date: String,
    val title: String,
    val site: String,
    val url: String,
    val accessed: LocalDate,
    val isPdf: Boolean,
    val isWiki: Boolean,
    /** Eigenes Dokument des Nutzers; [site] ist dann der Dateiname. */
    val isLocal: Boolean = false
) {
    val year: String get() = date.take(4)
    val anchor: String get() = "src$number"
}

/** Deutsche oder englische Woerter im Verzeichnis, je nach Sprache der Frage. */
class CiteLang(val german: Boolean) {
    val noDate = if (german) "o. J." else "n.d."
    val etAl = if (german) "u. a." else "et al."
    val and = if (german) "und" else "and"
    val online = if (german) "online" else "Online"
    val available = if (german) "Verfügbar unter" else "Available at"
    val accessed = if (german) "Zugriff am" else "Accessed"
    val retrieved = if (german) "Abgerufen am" else "Retrieved"
    val from = if (german) "von" else "from"
    val months = if (german)
        listOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")
    else listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    fun longDate(d: LocalDate): String =
        if (german) "${d.dayOfMonth}. ${months[d.monthValue - 1]} ${d.year}" else "${months[d.monthValue - 1]} ${d.dayOfMonth}, ${d.year}"

    fun numericDate(d: LocalDate): String = "%02d.%02d.%d".format(d.dayOfMonth, d.monthValue, d.year)
}

object Citations {

    private val WIKI_SUFFIX = Regex("\\s+[–—-]\\s+Wikipedia\\s*$")

    /** Eine Quelle je Nummer, Angaben aus dem ersten Fakt dieser Nummer. */
    fun entries(claims: List<VerifiedClaim>, createdAt: Long): List<BibEntry> {
        val accessed = Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
        return claims.distinctBy { it.citationIndex }.sortedBy { it.citationIndex }.map { vc ->
            val c = vc.chunk
            val m = c.meta
            // Auch ueber die Suche gefundene Wikipedia-Seiten (de.m.wikipedia.org) sind Wikipedia
            val isWiki = c.sourceType == SourceType.WIKIPEDIA || c.domain.endsWith("wikipedia.org")
            val site = if (isWiki) "Wikipedia" else m.siteName.ifBlank { m.publisher }.ifBlank { c.domain.removePrefix("www.") }
            val title = when {
                isWiki -> c.title.removePrefix("Wikipedia: ").replace(WIKI_SUFFIX, "")
                c.sourceType == SourceType.ARXIV -> c.title.substringAfter("): ").ifBlank { c.title }
                else -> c.title
            }.trim()
            BibEntry(
                number = vc.citationIndex,
                authors = m.authors,
                corporateAuthor = if (isWiki) "Wikipedia" else m.publisher.ifBlank { site },
                date = m.date,
                title = title,
                site = site,
                url = c.url,
                accessed = accessed,
                isPdf = m.isPdf,
                isWiki = isWiki,
                isLocal = c.sourceType == SourceType.LOCAL
            )
        }
    }

    /** "Max Mustermann" oder "Mustermann, Max" -> ("Mustermann", "Max"). */
    private fun split(name: String): Pair<String, String> {
        val n = name.trim()
        if (',' in n) return n.substringBefore(',').trim() to n.substringAfter(',').trim()
        val parts = n.split(Regex("\\s+"))
        return if (parts.size == 1) n to "" else parts.last() to parts.dropLast(1).joinToString(" ")
    }

    private fun initials(first: String): String =
        first.split(Regex("[\\s-]+")).filter { it.isNotEmpty() }.joinToString(" ") { "${it.first().uppercaseChar()}." }

    /** Kurzform fuer den Text: Nachname(n) oder Organisation. */
    fun shortAuthor(e: BibEntry, lang: CiteLang, style: CitationStyle): String {
        val last = e.authors.map { split(it).first }
        val amp = if (style == CitationStyle.APA7) "&" else lang.and
        return when {
            last.isEmpty() -> e.corporateAuthor
            last.size == 1 -> last[0]
            last.size == 2 -> "${last[0]} $amp ${last[1]}"
            else -> "${last[0]} ${lang.etAl}"
        }
    }

    /** Verweis im Text fuer eine oder mehrere Quellen (Reihenfolge wie im Satz). */
    fun inText(entries: List<BibEntry>, style: CitationStyle, lang: CiteLang): String {
        if (entries.isEmpty()) return ""
        fun yr(e: BibEntry) = e.year.ifBlank { lang.noDate }
        return when (style) {
            CitationStyle.IEEE -> entries.joinToString(", ") { "[${it.number}]" }
            CitationStyle.DIN_ISO_690 -> "[" + entries.joinToString(", ") { it.number.toString() } + "]"
            CitationStyle.APA7 -> "(" + entries.joinToString("; ") { "${shortAuthor(it, lang, style)}, ${yr(it)}" } + ")"
            CitationStyle.HARVARD, CitationStyle.CHICAGO -> "(" + entries.joinToString("; ") { "${shortAuthor(it, lang, style)} ${yr(it)}" } + ")"
        }
    }

    /** Reihenfolge im Verzeichnis: numerisch nach Nummer, sonst alphabetisch nach Autor, dann Jahr. */
    fun ordered(entries: List<BibEntry>, style: CitationStyle, lang: CiteLang): List<BibEntry> =
        if (style.numeric) entries.sortedBy { it.number }
        else entries.sortedWith(compareBy<BibEntry>({ shortAuthor(it, lang, style).lowercase() }, { it.year }, { it.title.lowercase() }))

    /** Verzeichniseintrag als Textstuecke (kursiv, Links). Numerische Stile beginnen mit der Nummer. */
    fun reference(e: BibEntry, style: CitationStyle, lang: CiteLang): List<Seg> {
        val out = ArrayList<Seg>()
        fun t(s: String) { if (s.isNotEmpty()) out.add(Seg(s)) }
        fun i(s: String) { if (s.isNotEmpty()) out.add(Seg(s, italic = true)) }
        fun u() { out.add(Seg(e.url, link = e.url)) }
        val names = e.authors.map { split(it) }
        val accessedLong = lang.longDate(e.accessed)
        val pubDate = runCatching { LocalDate.parse(e.date) }.getOrNull()
        // Seitenname nennen, ausser er steht schon als Organisation an Autorstelle
        val siteDiffers = names.isNotEmpty() || !e.site.equals(e.corporateAuthor, ignoreCase = true)
        val title = e.title.trimEnd('.')

        // Eigenes Dokument: keine Adresse und kein Abrufdatum, dafuer der Dateiname
        if (e.isLocal) {
            if (style.numeric) t("[${e.number}] ")
            val author = names.joinToString(if (style == CitationStyle.DIN_ISO_690) "; " else ", ") {
                if (style == CitationStyle.DIN_ISO_690) "${it.first.uppercase()}, ${it.second}".trimEnd(',', ' ')
                else "${it.first}, ${initials(it.second)}".trimEnd(',', ' ')
            }
            if (author.isNotEmpty()) t("${author.trimEnd('.')} (${e.year.ifBlank { lang.noDate }}). ")
            else if (e.year.isNotEmpty()) t("(${e.year}). ")
            i("$title.")
            t(" ${if (lang.german) "Datei" else "File"}: ${e.site}.")
            return out
        }

        when (style) {
            CitationStyle.APA7 -> {
                val author = when {
                    names.isEmpty() -> e.corporateAuthor
                    names.size == 1 -> "${names[0].first}, ${initials(names[0].second)}".trimEnd(',', ' ')
                    else -> names.dropLast(1).joinToString(", ") { "${it.first}, ${initials(it.second)}".trimEnd(',', ' ') } +
                        ", & " + "${names.last().first}, ${initials(names.last().second)}".trimEnd(',', ' ')
                }
                val dateText = when {
                    pubDate != null -> if (lang.german) "${pubDate.year}, ${pubDate.dayOfMonth}. ${lang.months[pubDate.monthValue - 1]}"
                        else "${pubDate.year}, ${lang.months[pubDate.monthValue - 1]} ${pubDate.dayOfMonth}"
                    e.year.isNotEmpty() -> e.year
                    else -> lang.noDate
                }
                t("${author.trimEnd('.')}. ($dateText). ")
                i("$title.")
                t(" ")
                if (siteDiffers) t("${e.site}. ")
                // Seiten, die sich aendern (Wikipedia, ohne Datum): Abrufdatum angeben
                if (e.isWiki || e.year.isEmpty()) t("${lang.retrieved} $accessedLong, ${lang.from} ")
                u()
            }
            CitationStyle.HARVARD -> {
                val f = names.map { "${it.first}, ${initials(it.second)}".trimEnd(',', ' ') }
                val author = when {
                    f.isEmpty() -> e.corporateAuthor
                    f.size == 1 -> f[0]
                    else -> f.dropLast(1).joinToString(", ") + " ${lang.and} " + f.last()
                }
                t("${author.trimEnd('.')} (${e.year.ifBlank { lang.noDate }}) ")
                i("$title.")
                t(" ")
                if (siteDiffers) t("${e.site}. ")
                t("${lang.available}: ")
                u()
                t(" (${lang.accessed}: ${if (lang.german) lang.numericDate(e.accessed) else accessedLong}).")
            }
            CitationStyle.CHICAGO -> {
                val author = when {
                    names.isEmpty() -> e.corporateAuthor
                    names.size == 1 -> "${names[0].first}, ${names[0].second}".trimEnd(',', ' ')
                    else -> "${names[0].first}, ${names[0].second}".trimEnd(',', ' ') + ", " +
                        names.drop(1).dropLast(1).joinToString("") { "${it.second} ${it.first}".trim() + ", " } +
                        "${lang.and} ${names.last().second} ${names.last().first}".replace("  ", " ")
                }
                t("${author.trimEnd('.')}. ${e.year.ifBlank { lang.noDate }}. ")
                t(if (lang.german) "„$title.“ " else "“$title.” ")
                if (siteDiffers) t("${e.site}. ")
                if (pubDate == null) t("${lang.accessed} $accessedLong. ")
                u()
                t(".")
            }
            CitationStyle.IEEE -> {
                t("[${e.number}] ")
                val author = when {
                    names.isEmpty() -> e.corporateAuthor
                    names.size > 6 -> "${initials(names[0].second)} ${names[0].first} ${lang.etAl}".trim()
                    else -> names.map { "${initials(it.second)} ${it.first}".trim() }.let { l ->
                        when (l.size) {
                            1 -> l[0]
                            2 -> "${l[0]} ${lang.and} ${l[1]}"
                            else -> l.dropLast(1).joinToString(", ") + ", ${lang.and} " + l.last()
                        }
                    }
                }
                t("$author, ")
                t("“$title,” ")
                if (siteDiffers || names.isNotEmpty()) i(e.site).also { t(", ") }
                t(if (e.year.isNotEmpty()) "${e.year}. " else "")
                t("[${lang.online}]. ${if (lang.german) "Verfügbar" else "Available"}: ")
                u()
                t(" (${lang.accessed.lowercase().replaceFirstChar { if (lang.german) it.uppercaseChar() else it }} $accessedLong).")
            }
            CitationStyle.DIN_ISO_690 -> {
                t("[${e.number}] ")
                val author = when {
                    names.isEmpty() -> e.corporateAuthor.uppercase()
                    names.size > 3 -> "${names[0].first.uppercase()}, ${names[0].second} ${lang.etAl}".trim()
                    else -> names.joinToString("; ") { "${it.first.uppercase()}, ${it.second}".trimEnd(',', ' ') }
                }
                t("$author: ")
                i(title)
                t(" [${lang.online}]. ")
                if (siteDiffers) t("${e.site}, ")
                t("${e.year.ifBlank { lang.noDate }} [${lang.accessed}: ${lang.numericDate(e.accessed)}]. ")
                t("${lang.available}: ")
                u()
            }
        }
        return out
    }
}
