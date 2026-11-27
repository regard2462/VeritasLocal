package com.veritas.localresearch.scraping

import android.content.Context
import android.net.Uri
import com.veritas.localresearch.domain.ScrapedDocument
import com.veritas.localresearch.domain.SearchResultItem
import com.veritas.localresearch.domain.SourceType
import com.veritas.localresearch.domain.SourceMeta
import com.veritas.localresearch.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class HiddenWebScraper(
    private val context: Context,
    private val robotsChecker: RobotsTxtChecker,
    private val settings: SettingsStore,
    baseClient: OkHttpClient? = null
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val isDestroyed = AtomicBoolean(false)
    // JavaScript-Ausweichweg fuer Seiten ohne Text im HTML (Android: WebView, Desktop: keiner)
    private val jsRenderer: JsPageRenderer? by lazy { createJsPageRenderer(context, USER_AGENT) }

    private val httpClient: OkHttpClient = (baseClient?.newBuilder() ?: OkHttpClient.Builder())
        .connectTimeout(4500, TimeUnit.MILLISECONDS)
        .readTimeout(5500, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()

    // PDFs sind groesser: laengere Lesezeit pro Block
    private val pdfClient: OkHttpClient = httpClient.newBuilder()
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val pdfExtractor = PdfTextExtractor(context)

    @Serializable
    private data class JsSearchResult(val title: String = "", val url: String = "", val snippet: String = "")

    @Serializable
    private data class JsArticleResult(val title: String = "", val text: String = "")

    private val skipPageExtensions = setOf(
        ".zip", ".tar", ".gz", ".rar", ".7z", ".exe", ".dmg", ".apk",
        ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".mp4", ".mp3", ".wav",
        ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx"
    )

    private val governor = SearchEngineGovernor(settings.enginePrefs)
    @Volatile private var rotation = 0

    /**
     * Fragt die eingeschalteten Suchmaschinen reihum, jeweils im Rahmen ihres Budgets. Reicht eine nicht fuer
     * [maxResults] Treffer, kommt die naechste dazu (hoechstens zwei pro Anfrage). Pausierende werden uebersprungen.
     */
    suspend fun searchWeb(
        query: String,
        maxResults: Int = 6,
        lang: com.veritas.localresearch.domain.LanguageDetector.Language? = null
    ): List<SearchResultItem> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        val cfg = settings.current
        val engines = cfg.engines()
        if (engines.isEmpty()) return emptyList()
        val start = (rotation++).mod(engines.size)
        val ordered = engines.drop(start) + engines.take(start)

        val merged = ArrayList<SearchResultItem>()
        var answered = 0
        for (engine in ordered) {
            if (merged.size >= maxResults || answered >= 2) break
            var wait = governor.tryAcquire(engine) ?: continue
            // Kurz warten, wenn das Budget gleich wieder frei ist; sonst die naechste Suchmaschine nehmen
            if (wait > 0L) {
                if (wait > 12_000L) continue
                delay(wait)
                wait = governor.tryAcquire(engine) ?: continue
                if (wait > 0L) continue
            }
            val url = engine.buildUrl(trimmed, cfg.searxngUrl, lang) ?: continue
            val res = fetchRaw(url, if (engine == SearchEngine.MARGINALIA || engine == SearchEngine.SEARXNG) "application/json" else null)
                ?: continue
            val found = if (res.first in 200..299) runCatching { engine.parse(res.second, url, maxResults) }.getOrDefault(emptyList()) else emptyList()
            if (found.isEmpty() && SearchEngine.isChallenge(res.first, res.second)) {
                governor.reportChallenge(engine)
                continue
            }
            if (found.isNotEmpty()) {
                governor.reportSuccess(engine)
                answered++
                for (r in found) if (merged.none { it.url == r.url }) merged.add(r)
            }
        }
        return merged.take(maxResults)
    }

    fun engineCooldownUntil(engine: SearchEngine): Long = governor.cooldownUntil(engine)
    fun clearEngineCooldown(engine: SearchEngine) = governor.clearCooldown(engine)

    /** Statuscode und Text (hoechstens 1,5 MB), oder null bei Netzwerkfehler. */
    private suspend fun fetchRaw(url: String, accept: String?): Pair<Int, String>? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", accept ?: "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()
        val call = httpClient.newCall(req)
        try {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val res = runCatching {
                            response.use { resp ->
                                val body = resp.body
                                val text = if (body == null) "" else {
                                    val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                                    val src = body.source()
                                    src.request(MAX_HTML_BYTES)
                                    String(src.buffer.readByteArray(minOf(src.buffer.size, MAX_HTML_BYTES)), charset)
                                }
                                resp.code to text
                            }
                        }.getOrNull()
                        if (cont.isActive) cont.resume(res)
                    }
                })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    suspend fun scrapePage(url: String, fallbackTitle: String): ScrapedDocument? {
        val lowerPath = runCatching { Uri.parse(url).path?.lowercase().orEmpty() }.getOrDefault("")
        if (skipPageExtensions.any { lowerPath.endsWith(it) }) return null
        if (!robotsChecker.isAllowed(url)) return null

        // PDFs (Studien, Berichte) direkt als Text lesen
        if (lowerPath.endsWith(".pdf")) return scrapePdf(url, fallbackTitle)

        // Fast-Path: Paralleles HTTP-Fetching + Jsoup-Readability (ohne WebView-Mutex, reicht fuer ~85 % der Seiten)
        val httpFetch = fetchHtmlHttp(url)
        if (httpFetch != null) {
            if (httpFetch.isPdf) return scrapePdf(url, fallbackTitle)
            if (!httpFetch.isHtml) return null // Keine Bilder/Binaries an WebView schicken
            val extracted = extractArticleFromHtml(httpFetch.html, url, fallbackTitle)
            if (extracted != null) return withSiteName(extracted)
            // Wenn HTTP 200 HTML war, aber kaum Text hatte (SPA / JS-Rendering), weiter zum WebView-Fallback
            if (!httpFetch.allowWebViewFallback) return null
        }

        // Fallback: Hidden WebView fuer JS-gerenderte Seiten
        if (isDestroyed.get()) return null
        val rawJson = jsRenderer?.loadAndEval(url, ReadabilityJs.EXTRACT_ARTICLE_SCRIPT, timeoutMs = 8_500L)
            ?: return null
        val article = runCatching {
            json.decodeFromString<JsArticleResult>(unwrapJsString(rawJson))
        }.getOrNull() ?: return null

        val clean = article.text.trim()
        if (clean.length < 180) return null

        val domain = extractDomain(url)
        return ScrapedDocument(
            url = url,
            domain = domain,
            title = article.title.ifBlank { fallbackTitle },
            cleanText = clean,
            sourceType = SourceType.WEB
        )
    }

    private data class HttpFetchResult(
        val html: String,
        val isHtml: Boolean,
        val allowWebViewFallback: Boolean,
        val blocked: Boolean = false,
        val isPdf: Boolean = false
    )

    private suspend fun scrapePdf(url: String, fallbackTitle: String): ScrapedDocument? {
        val bytes = withTimeoutOrNull(25_000L) { fetchBytes(url, PdfTextExtractor.MAX_BYTES) } ?: return null
        if (bytes.size < 5 || String(bytes, 0, 5, Charsets.ISO_8859_1) != "%PDF-") return null
        val pdf = pdfExtractor.extract(bytes) ?: return null
        return withSiteName(ScrapedDocument(
            url = url,
            domain = extractDomain(url),
            title = pdf.title.takeIf { usablePdfTitle(it, pdf.text) } ?: fallbackTitle.replace(PDF_MARK, "").trim().ifBlank {
                url.substringAfterLast('/').substringBeforeLast('.').replace(Regex("[-_]+"), " ")
            },
            cleanText = pdf.text,
            sourceType = SourceType.WEB,
            meta = SourceMeta(authors = pdf.authors, date = pdf.date, isPdf = true)
        ))
    }

    private val siteNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Ergaenzt den Seitennamen, wenn die Seite selbst keinen nennt (PDFs, Seiten ohne og:site_name): einmal je
     * Domain die Startseite lesen (og:site_name, JSON-LD, Logo, sonst ihr Titel). Ohne Treffer bleibt es bei der Domain.
     */
    private suspend fun withSiteName(doc: ScrapedDocument): ScrapedDocument {
        if (doc.meta.siteName.isNotBlank()) return doc
        val host = runCatching { Uri.parse(doc.url).host }.getOrNull() ?: return doc
        val name = siteNameCache[host] ?: run {
            val home = "https://$host/"
            val found = if (!robotsChecker.isAllowed(home)) "" else withTimeoutOrNull(6_000L) {
                val res = fetchHtmlHttp(home)
                if (res == null || !res.isHtml || res.blocked) return@withTimeoutOrNull ""
                runCatching {
                    val page = Jsoup.parse(res.html, home)
                    SourceMetaExtractor.fromHtml(page).siteName.ifBlank {
                        SourceMetaExtractor.siteNameFromHomeTitle(page.title(), host)
                    }
                }.getOrDefault("")
            }.orEmpty()
            siteNameCache[host] = found
            found
        }
        return if (name.isBlank()) doc else doc.copy(meta = doc.meta.copy(siteName = name))
    }

    /**
     * Titel aus den Dokumenteigenschaften nur, wenn er nach einem echten Titel aussieht: Vorlagenreste wie
     * "KD24 Erster Test", "Microsoft Word - Dok1" oder "Untitled" scheiden aus, ebenso Titel, deren Woerter im Text
     * gar nicht vorkommen. Sonst gilt der Titel aus dem Suchergebnis.
     */
    private fun usablePdfTitle(title: String, text: String): Boolean {
        if (title.length !in 8..200) return false
        val lower = title.lowercase()
        if (PDF_TITLE_JUNK.containsMatchIn(lower)) return false
        val words = lower.split(Regex("[^\\p{L}]+")).filter { it.length >= 5 }
        val head = text.take(4000).lowercase()
        return words.isNotEmpty() && words.count { it in head } * 2 >= words.size
    }

    /** Ganze Antwort als Bytes (fuer PDFs), hoechstens [maxBytes]; null bei Fehler, Sperre oder zu grosser Datei. */
    private suspend fun fetchBytes(url: String, maxBytes: Long): ByteArray? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/pdf,*/*;q=0.8")
            .build()
        val call = pdfClient.newCall(req)
        try {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val res = runCatching {
                            response.use { resp ->
                                val body = resp.body
                                if (!resp.isSuccessful || body == null) return@use null
                                if (body.contentLength() > maxBytes) return@use null
                                val src = body.source()
                                src.request(maxBytes + 1)
                                if (src.buffer.size > maxBytes) null else src.buffer.readByteArray()
                            }
                        }.getOrNull()
                        if (cont.isActive) cont.resume(res)
                    }
                })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchHtmlHttp(url: String): HttpFetchResult? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()
        val call = httpClient.newCall(req)
        try {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val res = response.use { resp ->
                            val code = resp.code
                            if (code == 403 || code == 429 || code == 503) {
                                // Sperre oder Drosselung durch die Seite respektieren, kein Umweg ueber die WebView
                                return@use HttpFetchResult("", isHtml = false, allowWebViewFallback = false, blocked = true)
                            }
                            if (!resp.isSuccessful) {
                                return@use HttpFetchResult("", isHtml = false, allowWebViewFallback = false)
                            }
                            val contentType = resp.header("Content-Type")?.lowercase().orEmpty()
                            val isHtml = contentType.isBlank() ||
                                contentType.contains("text/html") ||
                                contentType.contains("application/xhtml")
                            if (!isHtml) {
                                return@use HttpFetchResult("", isHtml = false, allowWebViewFallback = false,
                                    isPdf = contentType.contains("application/pdf"))
                            }
                            val body = resp.body ?: return@use HttpFetchResult("", isHtml = true, allowWebViewFallback = true)
                            val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                            // Maximal 1.5 MB lesen, um OOM bei riesigen Seiten zu vermeiden
                            val bytes = body.source().use { src ->
                                src.request(MAX_HTML_BYTES)
                                val buffer = src.buffer
                                val toRead = minOf(buffer.size, MAX_HTML_BYTES)
                                buffer.readByteArray(toRead)
                            }
                            HttpFetchResult(String(bytes, charset), isHtml = true, allowWebViewFallback = true)
                        }
                        if (cont.isActive) cont.resume(res)
                    }
                })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun extractArticleFromHtml(html: String, url: String, fallbackTitle: String): ScrapedDocument? {
        if (html.length < 200) return null
        return runCatching {
            val doc = Jsoup.parse(html, url)
            val body = doc.body() ?: return null
            // Meta-Daten vor dem Aufraeumen lesen: Logo im Kopfbereich und JSON-LD-Skripte werden gleich entfernt
            val meta = SourceMetaExtractor.fromHtml(doc)
            body.select(JSOUP_REMOVE_SELECTOR).remove()

            var bestEl: Element = body
            var bestScore = 0.0
            for (el in body.select("article, main, [role=main], section, div")) {
                val paragraphs = el.select("p")
                if (paragraphs.size < 2) continue
                var textLen = 0
                for (p in paragraphs) textLen += p.text().length
                if (textLen < 250) continue
                var linkLen = 0
                for (a in el.select("a")) linkLen += a.text().length
                val linkDensity = linkLen.toDouble() / textLen.coerceAtLeast(1)
                if (linkDensity > 0.45) continue
                val score = textLen * (1.0 - linkDensity) + paragraphs.size * 40.0
                if (score > bestScore) {
                    bestScore = score
                    bestEl = el
                }
            }

            val blocks = ArrayList<String>()
            val seen = HashSet<String>()
            for (node in bestEl.select(BLOCK_SELECTOR)) {
                // Aeussere Bloecke mit eigenen Unterbloecken ueberspringen (z. B. <li><p>), sonst doppelter Text
                if (node.select(BLOCK_SELECTOR).size > 1) continue
                val txt = node.text().replace(WHITESPACE_REGEX, " ").trim()
                if (txt.length >= 25 && seen.add(txt)) {
                    blocks.add(txt)
                }
            }

            val rawText = if (blocks.isNotEmpty()) blocks.joinToString("\n") else bestEl.text()
            val clean = rawText.replace(MULTI_NEWLINE_REGEX, "\n\n").trim().take(14_000)
            if (clean.length < 220) return null

            val title = SourceMetaExtractor.cleanTitle(
                doc.select("meta[name=citation_title]").attr("content").ifBlank { doc.title() }.trim(), meta.siteName, extractDomain(url)
            ).ifBlank { fallbackTitle }
            ScrapedDocument(
                url = url,
                domain = extractDomain(url),
                title = title,
                cleanText = clean,
                sourceType = SourceType.WEB,
                meta = meta
            )
        }.getOrNull()
    }

    private fun unwrapJsString(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            json.decodeFromString<String>(trimmed)
        } else {
            trimmed
        }
    }

    fun destroy() {
        if (!isDestroyed.compareAndSet(false, true)) return
        jsRenderer?.destroy()
    }

    companion object {
        /** Kennzeichnung der Suchmaschine vor PDF-Titeln: "[PDF] …", "PDF …", "… (PDF)". */
        private val PDF_MARK = Regex("^\\[?PDF]?\\s+|\\s*\\((?:PDF|pdf)[^)]*\\)$")
        private val PDF_TITLE_JUNK =Regex("\\b(?:test|untitled|unbenannt|dokument\\d*|document\\d*|dok\\d+|vorlage|template|folie\\s*\\d|präsentation\\d*)\\b|microsoft|powerpoint|\\.docx?\\b|\\.pptx?\\b")
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"
        private const val MAX_HTML_BYTES = 1_500_000L
        private const val BLOCK_SELECTOR = "h1, h2, h3, p, li, td"
        private const val JSOUP_REMOVE_SELECTOR =
            "script, style, noscript, iframe, svg, canvas, nav, footer, header, aside, form, button, input, select, template, " +
                "[hidden], [aria-hidden=true], [role=navigation], [role=banner], [role=complementary], " +
                "[id*=cookie], [class*=cookie], [class*=consent], [class*=banner], [class*=sidebar], " +
                "[class*=advert], [class*=promo], [class*=newsletter], [class*=share], [class*=comment], [class*=menu]"
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val MULTI_NEWLINE_REGEX = Regex("\n{3,}")

        fun extractDomain(url: String): String {
            val host = runCatching { URI(url).host }.getOrNull()
                ?: runCatching { Uri.parse(url).host }.getOrNull()
            return host
                ?.lowercase()
                ?.removePrefix("www.")
                ?.removePrefix("m.")
                ?.takeIf { it.isNotBlank() }
                ?: "unknown"
        }
    }
}
