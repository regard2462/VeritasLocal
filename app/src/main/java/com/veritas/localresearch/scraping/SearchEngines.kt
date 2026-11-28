package com.veritas.localresearch.scraping

import android.content.SharedPreferences
import com.veritas.localresearch.i18n.tr
import android.util.Base64
import com.veritas.localresearch.domain.SearchResultItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Websuchen ohne Konto. Jede Suchmaschine hat ein eigenes kleines Budget pro Minute und pausiert lange,
 * sobald sie drosselt oder einen Menschen sehen will (429, 403, 503, Captcha-Seite). Die App fragt eine gesperrte
 * Suchmaschine nicht selbststaendig ueber einen Browser erneut: die eigene Adresse soll nie gesperrt werden. Auf
 * Android kann der Nutzer den Test selbst in einem sichtbaren Browserfenster loesen; danach sucht die App bei dieser
 * Suchmaschine ueber den eingebauten Browser mit denselben Cookies weiter.
 * Suchmaschinen, die schon eine einfache Anfrage mit einem Captcha oder einer Sperre beantworten (Brave, Ecosia,
 * Yahoo, Yandex) oder nur per JavaScript Treffer zeigen (Qwant), gibt es hier nicht.
 */
enum class SearchEngine(
    val id: String,
    private val baseName: String,
    /** Hoechstens so viele Anfragen pro Minute. */
    val perMinute: Int,
    val enabledByDefault: Boolean,
    private val noteKey: String
) {
    DUCKDUCKGO("ddg", "DuckDuckGo", 4, true, "eng_ddg"),
    BING("bing", "Bing", 8, true, "eng_bing"),
    STARTPAGE("startpage", "Startpage", 4, true, "eng_startpage"),
    MOJEEK("mojeek", "Mojeek", 4, true, "eng_mojeek"),
    SEZNAM("seznam", "Seznam", 4, true, "eng_seznam"),
    NAVER("naver", "Naver", 4, true, "eng_naver"),
    MWMBL("mwmbl", "Mwmbl", 4, true, "eng_mwmbl"),
    MARGINALIA("marginalia", "Marginalia", 3, false, "eng_marginalia"),
    SEARXNG("searxng", "SearXNG", 20, false, "eng_searxng");

    val displayName: String get() = if (this == SEARXNG) tr("eng_searxng_name") else baseName

    /** Antwortet mit HTML (und kann deshalb nach einem selbst geloesten Test ueber den Browser gefragt werden). */
    val isHtml: Boolean get() = this != MARGINALIA && this != SEARXNG && this != MWMBL
    val note: String get() = tr(noteKey)

    /** [lang]: Sprache der Frage; Suchmaschinen mit Regions- oder Sprachparameter liefern dann Treffer in dieser Sprache. */
    fun buildUrl(query: String, searxngBase: String, lang: com.veritas.localresearch.domain.LanguageDetector.Language? = null): String? {
        val q = URLEncoder.encode(query, "UTF-8")
        return when (this) {
            DUCKDUCKGO -> "https://html.duckduckgo.com/html/?q=$q" + (lang?.let { "&kl=${it.ddgRegion}" } ?: "")
            BING -> "https://www.bing.com/search?q=$q" + (lang?.let { "&setlang=${it.code}&cc=${it.bingCountry}" } ?: "")
            STARTPAGE -> "https://www.startpage.com/do/search?q=$q"
            MOJEEK -> "https://www.mojeek.com/search?q=$q"
            SEZNAM -> "https://search.seznam.cz/?q=$q"
            NAVER -> "https://search.naver.com/search.naver?where=web&query=$q"
            // Englischer Index: bei Fragen in anderen Sprachen nicht fragen
            MWMBL -> if (lang != null && lang.code != "en") null else "https://api.mwmbl.org/search?s=$q"
            MARGINALIA -> "https://api.marginalia.nu/public/search/${q.replace("+", "%20")}?count=10"
            SEARXNG -> {
                val base = searxngBase.trim().trimEnd('/')
                if (!base.startsWith("https://") && !base.startsWith("http://")) null
                else "$base/search?q=$q&format=json" + (lang?.let { "&language=${it.code}" } ?: "")
            }
        }
    }

    fun parse(body: String, pageUrl: String, maxResults: Int): List<SearchResultItem> {
        val out = ArrayList<SearchResultItem>()
        fun add(url: String, title: String, snippet: String) {
            if (out.size >= maxResults) return
            if (!url.startsWith("http") || title.isBlank() || out.any { it.url == url }) return
            out.add(SearchResultItem(title.trim(), url, snippet.trim(), displayName))
        }
        when (this) {
            DUCKDUCKGO -> {
                val doc = Jsoup.parse(body, pageUrl)
                for (el in doc.select(".result, .results_links")) {
                    if (el.hasClass("result--ad")) continue
                    val a = el.selectFirst("a.result__a") ?: continue
                    add(resolveDdg(a.attr("abs:href").ifBlank { a.attr("href") }), a.text(),
                        el.selectFirst(".result__snippet")?.text().orEmpty())
                }
            }
            BING -> {
                val doc = Jsoup.parse(body, pageUrl)
                for (li in doc.select("li.b_algo")) {
                    // Titel im Link (a > h2) oder Link im Titel (h2 > a)
                    val a = li.selectFirst("h2 a") ?: li.selectFirst("a:has(h2)") ?: continue
                    add(resolveBing(a.attr("abs:href")), a.text(), li.selectFirst(".b_caption p, p")?.text().orEmpty())
                }
            }
            STARTPAGE -> {
                val doc = Jsoup.parse(body, pageUrl)
                for (a in doc.select("a[data-testid=bg-title-link]")) {
                    val snippet = a.nextElementSibling()?.takeIf { it.tagName() == "p" }?.text().orEmpty()
                    add(a.attr("abs:href"), a.text(), snippet)
                }
            }
            MOJEEK -> {
                val doc = Jsoup.parse(body, pageUrl)
                for (li in doc.select("ul.results-standard li")) {
                    val a = li.selectFirst("a.title") ?: continue
                    add(a.attr("abs:href"), a.text(), li.selectFirst("p.s")?.text().orEmpty())
                }
            }
            SEZNAM -> {
                val doc = Jsoup.parse(body, pageUrl)
                for (a in doc.select("a[data-e-a=heading][href^=http]")) {
                    // Ergebnisblock: naechster Vorfahre mit Kennung; Snippet ist sein Text ohne Titel und Adresszeile
                    val block = generateSequence(a.parent()) { it.parent() }.take(6).firstOrNull { it.hasAttr("data-e-b-n") }
                    val ref = block?.selectFirst("a[data-e-a=reference]")?.text().orEmpty()
                    val snippet = block?.text().orEmpty().replace(a.text(), "").replace(ref, "").trim()
                    add(a.attr("href"), a.text(), snippet)
                }
            }
            NAVER -> {
                val doc = Jsoup.parse(body, pageUrl)
                // Je Treffer mehrere Links auf dasselbe Ziel: Datum, Titel, Beschreibung
                val byUrl = LinkedHashMap<String, MutableList<String>>()
                for (a in doc.select("a[data-nlog-area^=urB_][data-nlog-area$=.w.link][href^=http]")) {
                    val text = a.text().replace(NAVER_NEW_WINDOW, "").trim()
                    if (text.isBlank() || NAVER_DATE.matches(text)) continue
                    byUrl.getOrPut(a.attr("href")) { ArrayList() }.add(text)
                }
                for ((url, texts) in byUrl) add(url, texts.first(), texts.drop(1).joinToString(" "))
            }
            MWMBL -> {
                val arr = runCatching { JSON.parseToJsonElement(body).jsonArray }.getOrNull() ?: return out
                fun joined(o: JsonObject, k: String) = runCatching {
                    o[k]!!.jsonArray.joinToString("") { it.jsonObject["value"]!!.jsonPrimitive.content }
                }.getOrDefault("")
                for (r in arr) {
                    val o = r as? JsonObject ?: continue
                    add(runCatching { o["url"]!!.jsonPrimitive.content }.getOrDefault(""), joined(o, "title"), joined(o, "extract"))
                }
            }
            MARGINALIA, SEARXNG -> {
                val root = runCatching { JSON.parseToJsonElement(body).jsonObject }.getOrNull() ?: return out
                val results = runCatching { root["results"]!!.jsonArray }.getOrNull() ?: return out
                for (r in results) {
                    val o = r as? JsonObject ?: continue
                    fun str(k: String) = runCatching { o[k]!!.jsonPrimitive.content }.getOrDefault("")
                    add(str("url"), str("title"), str("description").ifBlank { str("content") })
                }
            }
        }
        return out
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
        /** Versteckter Hinweis "öffnet in neuem Fenster" in Naver-Links. */
        private val NAVER_NEW_WINDOW = Regex("새 창 열림")
        private val NAVER_DATE = Regex("\\d{4}\\.\\d{2}\\.\\d{2}\\.?")

        fun byId(id: String): SearchEngine? = entries.firstOrNull { it.id == id }

        /**
         * Weiche Sperre: manche Suchmaschinen werten bei automatischen Anfragen nur das erste Wort aus. Bei mindestens
         * zwei Suchwoertern muss ein Drittel der Treffer zwei davon in Titel oder Beschreibung nennen.
         */
        fun looksDegraded(query: String, results: List<SearchResultItem>): Boolean {
            val words = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.distinct()
            if (words.size < 2 || results.size < 3) return false
            val good = results.count { r ->
                val text = (r.title + " " + r.snippet + " " + r.url).lowercase()
                words.count { it in text } >= 2
            }
            return good * 3 < results.size
        }

        /** Sperrt die Suchmaschine oder will sie einen Menschen sehen? Nur relevant, wenn nichts gefunden wurde. */
        fun isChallenge(code: Int, body: String): Boolean {
            if (code == 429 || code == 403 || code == 503) return true
            val head = body.take(20_000)
            return CHALLENGE_REGEX.containsMatchIn(head)
        }

        private val CHALLENGE_REGEX = Regex(
            "<title>[^<]*captcha|are you a robot|unusual traffic|verify you are (a )?human|anomaly-modal|" +
                "cf-chl|ich bin kein roboter|bots use duckduckgo",
            RegexOption.IGNORE_CASE
        )

        fun resolveDdg(rawHref: String): String {
            val normalized = if (rawHref.startsWith("//")) "https:$rawHref" else rawHref
            val idx = normalized.indexOf("uddg=")
            if (idx >= 0) {
                val decoded = runCatching {
                    URLDecoder.decode(normalized.substring(idx + 5).substringBefore('&'), "UTF-8")
                }.getOrNull()
                if (!decoded.isNullOrBlank() && decoded.startsWith("http")) return decoded
            }
            return normalized
        }

        /** Bing verpackt Ziele manchmal als bing.com/ck/a?...&u=a1<base64url>. */
        fun resolveBing(href: String): String {
            if (!href.contains("bing.com/ck/a")) return href
            val u = href.substringAfter("&u=", "").substringBefore('&')
            if (!u.startsWith("a1")) return href
            return runCatching {
                String(Base64.decode(u.substring(2), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
            }.getOrNull()?.takeIf { it.startsWith("http") } ?: href
        }
    }
}

/**
 * Budget und Pausen je Suchmaschine. Pausen ueberleben einen Neustart der App (SharedPreferences),
 * damit eine gesperrte Suchmaschine nicht gleich wieder gefragt wird.
 */
class SearchEngineGovernor(private val prefs: SharedPreferences) {

    private val recent = HashMap<SearchEngine, ArrayDeque<Long>>()
    private val lock = Any()

    fun cooldownUntil(engine: SearchEngine): Long = prefs.getLong("cool_${engine.id}", 0L)

    fun isCooling(engine: SearchEngine, now: Long = System.currentTimeMillis()) = cooldownUntil(engine) > now

    /**
     * Wartezeit in ms, bis die Suchmaschine wieder gefragt werden darf (0 = sofort), oder null, wenn sie pausiert.
     * Reserviert den Platz im Budget, wenn 0 zurueckkommt.
     */
    fun tryAcquire(engine: SearchEngine, now: Long = System.currentTimeMillis()): Long? = synchronized(lock) {
        if (isCooling(engine, now)) return null
        val q = recent.getOrPut(engine) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() >= 60_000L) q.removeFirst()
        val minGap = 60_000L / engine.perMinute / 2
        val lastGap = if (q.isEmpty()) Long.MAX_VALUE else now - q.last()
        val waitBudget = if (q.size >= engine.perMinute) 60_000L - (now - q.first()) else 0L
        val waitGap = if (lastGap < minGap) minGap - lastGap else 0L
        val wait = maxOf(waitBudget, waitGap)
        if (wait == 0L) q.addLast(now)
        wait
    }

    fun reportSuccess(engine: SearchEngine) {
        if (prefs.getInt("streak_${engine.id}", 0) != 0) prefs.edit().putInt("streak_${engine.id}", 0).apply()
    }

    /** Drosselung oder Captcha: 15 Minuten Pause, bei jeder weiteren Sperre doppelt so lange, hoechstens 4 Stunden. */
    fun reportChallenge(engine: SearchEngine, now: Long = System.currentTimeMillis()) {
        val streak = prefs.getInt("streak_${engine.id}", 0)
        val pause = (15 * 60_000L shl streak.coerceAtMost(4)).coerceAtMost(4 * 3_600_000L)
        prefs.edit()
            .putLong("cool_${engine.id}", now + pause)
            .putInt("streak_${engine.id}", streak + 1)
            .apply()
    }

    fun clearCooldown(engine: SearchEngine) {
        prefs.edit().remove("cool_${engine.id}").remove("streak_${engine.id}").apply()
    }

    /** Der Nutzer hat den Test selbst geloest: Pause aufheben, 24 Stunden lang ueber den eingebauten Browser suchen. */
    fun markSolved(engine: SearchEngine, now: Long = System.currentTimeMillis()) {
        prefs.edit().remove("cool_${engine.id}").remove("streak_${engine.id}")
            .putLong("browser_${engine.id}", now + 24 * 3_600_000L).apply()
    }

    fun viaBrowser(engine: SearchEngine, now: Long = System.currentTimeMillis()) = prefs.getLong("browser_${engine.id}", 0L) > now
}
