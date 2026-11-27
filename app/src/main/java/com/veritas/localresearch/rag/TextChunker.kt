package com.veritas.localresearch.rag

import com.veritas.localresearch.domain.ScrapedDocument
import com.veritas.localresearch.domain.TextChunk
import java.util.concurrent.atomic.AtomicInteger

class TextChunker(
    private val chunkSizeChars: Int = 500,
    private val overlapChars: Int = 80
) {
    private val idGen = AtomicInteger(1)

    fun resetIds() = idGen.set(1)

    fun chunkDocument(doc: ScrapedDocument): List<TextChunk> {
        val cleaned = doc.cleanText.replace(HSPACE_REGEX, " ").trim()
        if (cleaned.length < 40) return emptyList()
        if (cleaned.length <= chunkSizeChars) {
            return listOf(
                TextChunk(
                    chunkId = idGen.getAndIncrement(),
                    url = doc.url,
                    domain = doc.domain,
                    title = doc.title,
                    rawText = cleaned,
                    sourceType = doc.sourceType,
                    meta = doc.meta
                )
            )
        }

        val chunks = mutableListOf<TextChunk>()
        var start = 0
        while (start < cleaned.length) {
            var end = (start + chunkSizeChars).coerceAtMost(cleaned.length)
            // Moeglichst an Satzende trennen, sonst an einem echten Zeilenende. Weiche Umbrueche mitten im Satz
            // (PDF-Zeilen) zaehlen nicht, sonst endet ein Abschnitt mitten im Satz.
            if (end < cleaned.length) {
                val minIdx = start + chunkSizeChars / 2
                val breakIdx = lastBreak(cleaned, minIdx, end)
                if (breakIdx > minIdx) {
                    end = breakIdx + 1
                } else {
                    val spaceIdx = cleaned.lastIndexOf(' ', end)
                    if (spaceIdx > start + chunkSizeChars / 2) {
                        end = spaceIdx
                    }
                }
            }
            val slice = cleaned.substring(start, end).trim()
            if (slice.length >= 60) {
                chunks.add(
                    TextChunk(
                        chunkId = idGen.getAndIncrement(),
                        url = doc.url,
                        domain = doc.domain,
                        title = doc.title,
                        rawText = slice,
                        sourceType = doc.sourceType,
                    meta = doc.meta
                    )
                )
            }
            if (end >= cleaned.length) break
            var nextStart = (end - overlapChars).coerceAtLeast(start + 1)
            // Overlap moeglichst an Wortgrenze beginnen statt mitten im Wort
            if (nextStart > 0 && nextStart < cleaned.length && !cleaned[nextStart - 1].isWhitespace()) {
                val nextSpace = cleaned.indexOf(' ', nextStart)
                if (nextSpace in (nextStart + 1)..(nextStart + 24) && nextSpace + 1 < end) {
                    nextStart = nextSpace + 1
                }
            }
            start = nextStart.coerceAtLeast(start + 1)
        }
        return chunks
    }

    /** Letztes Satzende (Satzzeichen vor Leerraum) in [from, to], sonst letzter harter Zeilenumbruch, sonst -1. */
    private fun lastBreak(text: String, from: Int, to: Int): Int {
        var hardNl = -1
        val minLine = com.veritas.localresearch.verification.softLineMin(text.substring(from.coerceAtLeast(0), to.coerceAtMost(text.length)))
        var i = to.coerceAtMost(text.length - 1)
        while (i > from) {
            val c = text[i]
            if ((c == '.' || c == '!' || c == '?') && (i + 1 >= text.length || text[i + 1].isWhitespace())) return i
            if (c == '\n' && hardNl < 0 && !com.veritas.localresearch.verification.isSoftBreak(text, i, minLine)) hardNl = i
            i--
        }
        return hardNl
    }

    private companion object {
        val HSPACE_REGEX = Regex("[ \\t]+")
    }
}
