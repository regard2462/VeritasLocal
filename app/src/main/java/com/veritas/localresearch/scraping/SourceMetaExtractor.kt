package com.veritas.localresearch.scraping

import com.veritas.localresearch.domain.SourceMeta
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.nodes.Document

/**
 * Liest Autor, Datum und Seitenname aus den Meta-Tags einer Seite: zuerst die Highwire-Tags wissenschaftlicher
 * Verlage (citation_*), dann Dublin Core, Open Graph, article:* und schliesslich <time datetime>.
 */
object SourceMetaExtractor {

    private val ISO_DATE = Regex("(\\d{4})(?:-(\\d{2}))?(?:-(\\d{2}))?")
    private val GERMAN_DATE = Regex("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})")

    fun fromHtml(doc: Document): SourceMeta {
        fun metas(vararg keys: String): List<String> = keys.flatMap { k ->
            doc.select("meta[name=$k], meta[property=$k], meta[itemprop=$k]").map { it.attr("content").trim() }
        }.filter { it.isNotBlank() }

        val authors = (metas("citation_author").ifEmpty { metas("dc.creator", "DC.creator", "author", "article:author", "parsely-author") })
            .filter { !it.startsWith("http") && it.length in 3..80 }
            .flatMap { if (it.contains(';')) it.split(';') else listOf(it) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(12)

        val rawDate = metas(
            "citation_publication_date", "citation_date", "citation_online_date", "dc.date", "DC.date",
            "article:published_time", "datePublished", "date", "pubdate", "publish-date", "article:modified_time"
        ).firstOrNull() ?: doc.selectFirst("time[datetime]")?.attr("datetime").orEmpty()

        val siteName = metas("og:site_name", "citation_journal_title", "application-name").firstOrNull()
            ?: jsonLdOrganization(doc) ?: logoName(doc) ?: ""
        val publisher = metas("citation_publisher", "dc.publisher", "DC.publisher").firstOrNull().orEmpty()

        return SourceMeta(
            authors = authors,
            date = normalizeDate(rawDate),
            siteName = siteName.take(80),
            publisher = publisher.take(80)
        )
    }

    /** Name der ersten Organisation in den JSON-LD-Daten der Seite (schema.org Organization und Unterarten). */
    internal fun jsonLdOrganization(doc: Document): String? {
        for (script in doc.select("script[type=application/ld+json]")) {
            val root = runCatching { Json.parseToJsonElement(script.data()) }.getOrNull() ?: continue
            findOrgName(root)?.let { return it }
        }
        return null
    }

    private fun findOrgName(el: JsonElement): String? = when (el) {
        is JsonArray -> el.firstNotNullOfOrNull { findOrgName(it) }
        is JsonObject -> {
            val type = el["@type"]
            val types = when (type) {
                is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                is JsonPrimitive -> listOfNotNull(type.contentOrNull)
                else -> emptyList()
            }
            val name = (el["name"] as? JsonPrimitive)?.contentOrNull?.trim()
            if (types.any { it.endsWith("Organization") || it == "Corporation" } && !name.isNullOrBlank() && name.length in 2..80) name
            else el.values.firstNotNullOfOrNull { findOrgName(it) }
        }
        else -> null
    }

    /**
     * Name aus dem Alternativtext des Seitenlogos ("Logo: Landeswetteramt (Link zur Startseite)" ->
     * "Landeswetteramt"). Nur Logos im Kopfbereich oder in einem Logo-Element zaehlen, keine Partnerlogos.
     */
    internal fun logoName(doc: Document): String? {
        val imgs = doc.select("header img[alt], [class*=logo] img[alt], [id*=logo] img[alt], img[class*=logo][alt], img[id*=logo][alt]")
        for (img in imgs) {
            val alt = img.attr("alt").trim()
            if (!LOGO_WORD.containsMatchIn(alt)) continue
            val name = alt.replace(PARENS, " ")
                .replace(LOGO_NOISE, " ")
                .replace(Regex("\\s+"), " ")
                .trim(' ', ':', '-', '–', '|', ',')
            if (name.length in 3..80 && name.any(Char::isLetter)) return name
        }
        return null
    }

    /**
     * Seitenname aus dem Titel einer Startseite: "LWA - Landeswetteramt" -> "Landeswetteramt". Teile,
     * die nur die Domain wiederholen, fallen weg; uebrig bleibt der laengste Teil.
     */
    fun siteNameFromHomeTitle(title: String, domain: String): String {
        val core = domain.removePrefix("www.").substringBefore('.').lowercase()
        val parts = title.split(" | ", " – ", " - ", " — ", " :: ").map { it.trim() }.filter { it.length >= 2 }
        if (parts.isEmpty()) return ""
        val named = parts.filter { it.lowercase().replace(" ", "") != core && !it.equals("Startseite", true) && !it.equals("Home", true) }
        return named.maxByOrNull { it.length }?.take(80) ?: parts.first().take(80)
    }

    private val LOGO_WORD = Regex("(?i)\\blogo\\b")
    private val PARENS = Regex("\\([^)]*\\)")
    private val LOGO_NOISE = Regex("(?i)\\blogo\\b(?:\\s+(?:der|des|von|of|the))?|\\b(?:link\\s+)?(?:zur|to(?: the)?)\\s+(?:startseite|homepage|home ?page|home)\\b|\\bstartseite\\b")

    /** "2025-05-14T10:00Z" -> "2025-05-14", "14.05.2025" -> "2025-05-14", sonst Jahr oder leer. */
    fun normalizeDate(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        GERMAN_DATE.find(s)?.let { m ->
            val (d, mo, y) = m.destructured
            return "%s-%02d-%02d".format(y, mo.toInt(), d.toInt())
        }
        val m = ISO_DATE.find(s) ?: return ""
        val y = m.groupValues[1].toInt()
        if (y !in 1900..2100) return ""
        val mo = m.groupValues[2]
        val d = m.groupValues[3]
        return buildString {
            append(y)
            if (mo.isNotEmpty() && mo.toInt() in 1..12) {
                append('-').append(mo)
                if (d.isNotEmpty() && d.toInt() in 1..31) append('-').append(d)
            }
        }
    }

    /**
     * Entfernt den Seitennamen aus dem Titel ("LWA - Wetterdaten" -> "Wetterdaten"), aber nur, wenn das
     * abgetrennte Stueck der Seitenname ist oder zum Domainnamen passt ("LWA" zu lwa.example.org).
     */
    fun cleanTitle(title: String, siteName: String, domain: String = ""): String {
        val parts = title.split(" | ", " – ", " - ", " — ").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return title.trim()
        val core = domain.removePrefix("www.").substringBefore('.').lowercase()
        fun isSite(p: String): Boolean {
            val l = p.lowercase().replace(" ", "")
            return (siteName.isNotBlank() && p.equals(siteName, ignoreCase = true)) ||
                (core.length >= 2 && p.length <= 30 && (l == core || l.startsWith(core) || core.startsWith(l)))
        }
        val kept = when {
            isSite(parts.last()) -> parts.dropLast(1)
            isSite(parts.first()) -> parts.drop(1)
            else -> parts
        }
        return kept.joinToString(" – ")
    }
}
