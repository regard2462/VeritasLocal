package com.veritas.localresearch.scraping

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class RobotsTxtChecker(baseClient: OkHttpClient? = null) {

    private val client: OkHttpClient = (baseClient?.newBuilder() ?: OkHttpClient.Builder())
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .build()

    private data class Rule(val pattern: String, val allow: Boolean)
    private data class CachedRules(val rules: List<Rule>, val fetchedAtMs: Long)

    private val cache = ConcurrentHashMap<String, CachedRules>()

    suspend fun isAllowed(url: String): Boolean = withContext(Dispatchers.IO) {
        val parsedUri = runCatching { URI(url) }.getOrNull()
        val androidUri = runCatching { Uri.parse(url) }.getOrNull()

        val host = (parsedUri?.host ?: androidUri?.host)?.lowercase()
            ?.takeIf { it.isNotBlank() } ?: return@withContext false
        val scheme = (parsedUri?.scheme ?: androidUri?.scheme)?.lowercase()
            ?.takeIf { it == "http" || it == "https" } ?: return@withContext false
        val rawPath = parsedUri?.rawPath?.takeIf { it.isNotBlank() }
            ?: androidUri?.encodedPath?.takeIf { it.isNotBlank() }
            ?: "/"
        val query = parsedUri?.rawQuery ?: androidUri?.encodedQuery
        val fullPath = if (query.isNullOrEmpty()) rawPath else "$rawPath?$query"

        val now = System.currentTimeMillis()
        val cached = cache[host]
        val rules = if (cached != null && now - cached.fetchedAtMs < CACHE_TTL_MS) {
            cached.rules
        } else {
            val fetched = fetchRules("$scheme://$host/robots.txt")
            if (fetched != null) {
                cache[host] = CachedRules(fetched, now)
                fetched
            } else {
                // Transienten Netzwerkfehler nicht dauerhaft cachen -> Standard: erlaubt
                emptyList()
            }
        }

        evaluateRules(fullPath, rules)
    }

    private suspend fun fetchRules(robotsUrl: String): List<Rule>? {
        val req = Request.Builder()
            .url(robotsUrl)
            .header("User-Agent", "VeritasLocalResearch/1.0")
            .build()
        return try {
            val call = client.newCall(req)
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = runCatching {
                            response.use { resp ->
                                if (!resp.isSuccessful) emptyList()
                                else parseRules(resp.body?.string().orEmpty())
                            }
                        }.getOrNull()
                        if (cont.isActive) cont.resume(result)
                    }
                })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun parseRules(content: String): List<Rule> {
        val wildcardRules = mutableListOf<Rule>()
        val specificRules = mutableListOf<Rule>()

        var inWildcardGroup = false
        var inSpecificGroup = false
        var seenRuleInGroup = false

        for (rawLine in content.lineSequence()) {
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val colonIdx = line.indexOf(':')
            if (colonIdx <= 0) continue

            val field = line.substring(0, colonIdx).trim().lowercase()
            val value = line.substring(colonIdx + 1).trim()

            when (field) {
                "user-agent" -> {
                    // Mehrere aufeinanderfolgende User-agent-Zeilen gehoeren zur selben Gruppe (RFC 9309)
                    if (seenRuleInGroup) {
                        inWildcardGroup = false
                        inSpecificGroup = false
                        seenRuleInGroup = false
                    }
                    if (value == "*") {
                        inWildcardGroup = true
                    } else if (value.contains("veritas", ignoreCase = true)) {
                        inSpecificGroup = true
                    }
                }
                "disallow", "allow" -> {
                    seenRuleInGroup = true
                    if (value.isEmpty()) continue
                    val rule = Rule(pattern = value, allow = (field == "allow"))
                    if (inSpecificGroup) specificRules.add(rule)
                    if (inWildcardGroup) wildcardRules.add(rule)
                }
            }
        }
        return if (specificRules.isNotEmpty()) specificRules else wildcardRules
    }

    private fun evaluateRules(path: String, rules: List<Rule>): Boolean {
        if (rules.isEmpty()) return true
        var bestMatchLen = -1
        var bestAllow = true
        for (rule in rules) {
            if (matchesPattern(path, rule.pattern)) {
                val len = rule.pattern.length
                // Laengste passende Regel gewinnt; bei gleicher Laenge gewinnt Allow (RFC 9309)
                if (len > bestMatchLen || (len == bestMatchLen && rule.allow)) {
                    bestMatchLen = len
                    bestAllow = rule.allow
                }
            }
        }
        return bestAllow
    }

    private fun matchesPattern(path: String, pattern: String): Boolean {
        val endAnchor = pattern.endsWith("$")
        val core = if (endAnchor) pattern.dropLast(1) else pattern
        if ('*' !in core) {
            return if (endAnchor) path == core else path.startsWith(core)
        }
        val parts = core.split('*')
        var pos = 0
        for ((idx, part) in parts.withIndex()) {
            if (part.isEmpty()) continue
            if (idx == 0 && !core.startsWith('*')) {
                if (!path.startsWith(part)) return false
                pos = part.length
            } else {
                val found = path.indexOf(part, startIndex = pos)
                if (found < 0) return false
                pos = found + part.length
            }
        }
        return if (endAnchor) {
            val last = parts.lastOrNull().orEmpty()
            last.isEmpty() || path.endsWith(last)
        } else {
            true
        }
    }

    private companion object {
        const val CACHE_TTL_MS = 10 * 60 * 1000L
    }
}
