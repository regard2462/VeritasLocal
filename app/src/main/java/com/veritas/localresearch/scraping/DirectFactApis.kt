package com.veritas.localresearch.scraping

import com.veritas.localresearch.domain.ScrapedDocument
import com.veritas.localresearch.domain.SourceType
import com.veritas.localresearch.domain.SourceMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class DirectFactApis(baseClient: OkHttpClient? = null) {

    private val client: OkHttpClient = (baseClient?.newBuilder() ?: OkHttpClient.Builder())
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchWikipedia(query: String, lang: String = "de", limit: Int = 2): List<ScrapedDocument> =
        withContext(Dispatchers.IO) {
            try {
                val enc = URLEncoder.encode(query, "UTF-8")
                val searchUrl =
                    "https://$lang.wikipedia.org/w/api.php?action=query&list=search&srsearch=$enc&srlimit=$limit&format=json"
                val searchBody = httpGet(searchUrl) ?: return@withContext emptyList()
                val root = json.parseToJsonElement(searchBody).jsonObject
                val hits = root["query"]?.jsonObject?.get("search")?.jsonArray ?: return@withContext emptyList()

                val titles = hits.mapNotNull { it.jsonObject["title"]?.jsonPrimitive?.content?.takeIf { t -> t.isNotBlank() } }
                if (titles.isEmpty()) return@withContext emptyList()

                coroutineScope {
                    titles.map { title ->
                        async {
                            val encTitle = URLEncoder.encode(title, "UTF-8")
                            val extractUrl =
                                "https://$lang.wikipedia.org/w/api.php?action=query&prop=extracts&explaintext=1&exchars=6000&titles=$encTitle&format=json"
                            val exBody = httpGet(extractUrl) ?: return@async null
                            val pages = json.parseToJsonElement(exBody)
                                .jsonObject["query"]?.jsonObject?.get("pages")?.jsonObject ?: return@async null

                            val firstPage = pages.values.firstOrNull()?.jsonObject ?: return@async null
                            val extract = firstPage["extract"]?.jsonPrimitive?.content?.trim().orEmpty()
                            if (extract.length < 150) return@async null

                            val encodedSlug = URLEncoder.encode(title.replace(' ', '_'), "UTF-8")
                                .replace("+", "%20")
                                .replace("%2F", "/")
                            val pageUrl = "https://$lang.wikipedia.org/wiki/$encodedSlug"
                            ScrapedDocument(
                                url = pageUrl,
                                domain = "$lang.wikipedia.org",
                                title = "Wikipedia: $title",
                                cleanText = extract,
                                sourceType = SourceType.WIKIPEDIA,
                                meta = SourceMeta(siteName = "Wikipedia", publisher = "Wikimedia Foundation")
                            )
                        }
                    }.awaitAll().filterNotNull()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }

    suspend fun fetchArxiv(query: String, maxResults: Int = 3): List<ScrapedDocument> =
        withContext(Dispatchers.IO) {
            try {
                val enc = URLEncoder.encode(query, "UTF-8")
                val url = "https://export.arxiv.org/api/query?search_query=all:$enc&start=0&max_results=$maxResults"
                val xml = httpGet(url) ?: return@withContext emptyList()
                val doc = Jsoup.parse(xml, "", Parser.xmlParser())
                doc.select("entry").mapNotNull { entry ->
                    val title = entry.selectFirst("title")?.text()?.replace(WHITESPACE_REGEX, " ")?.trim()
                        ?: return@mapNotNull null
                    val summary = entry.selectFirst("summary")?.text()?.replace(WHITESPACE_REGEX, " ")?.trim()
                        ?: return@mapNotNull null
                    val idUrl = entry.selectFirst("id")?.text()?.trim() ?: return@mapNotNull null
                    val published = entry.selectFirst("published")?.text()?.take(10).orEmpty()
                    val authors = entry.select("author > name").joinToString(", ") { it.text() }

                    ScrapedDocument(
                        url = idUrl,
                        domain = "arxiv.org",
                        title = "arXiv ($published): $title",
                        cleanText = "Titel: $title\nAutoren: $authors\nDatum: $published\nAbstract: $summary",
                        sourceType = SourceType.ARXIV,
                        meta = SourceMeta(
                            authors = entry.select("author > name").map { it.text().trim() }.filter { it.isNotEmpty() },
                            date = published,
                            siteName = "arXiv"
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }

    private suspend fun httpGet(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "VeritasLocalResearch/1.0 (Android On-Device Research)")
            .build()
        val call = client.newCall(req)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = runCatching {
                        response.use { resp -> if (resp.isSuccessful) resp.body?.string() else null }
                    }.getOrNull()
                    if (cont.isActive) cont.resume(body)
                }
            })
        }
    }

    private companion object {
        val WHITESPACE_REGEX = Regex("\\s+")
    }
}
