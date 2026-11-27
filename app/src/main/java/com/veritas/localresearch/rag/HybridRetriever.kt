package com.veritas.localresearch.rag

import com.veritas.localresearch.domain.RankedChunk
import com.veritas.localresearch.domain.TextChunk
import com.veritas.localresearch.llm.OnnxEmbedder

class HybridRetriever(
    private val ftsStore: Fts5ChunkStore,
    private val embedder: OnnxEmbedder
) {
    private val allChunks = LinkedHashMap<Int, TextChunk>()
    private var cachedQueryText: String? = null
    private var cachedQueryVec: FloatArray? = null

    fun clear() {
        allChunks.clear()
        ftsStore.clearSession()
        cachedQueryText = null
        cachedQueryVec = null
    }

    suspend fun indexChunks(newChunks: List<TextChunk>) {
        if (newChunks.isEmpty()) return
        val vectors = if (embedder.isReady) {
            embedder.embedBatch(newChunks.map { "${it.title}. ${it.rawText}" }, batchSize = 16)
        } else {
            List(newChunks.size) { null }
        }
        val enriched = newChunks.mapIndexed { idx, chunk ->
            chunk.copy(embedding = vectors.getOrNull(idx))
        }
        enriched.forEach { allChunks[it.chunkId] = it }
        ftsStore.insertChunks(enriched)
    }

    fun getChunkById(id: Int): TextChunk? = allChunks[id]
    fun getAllChunks(): List<TextChunk> = allChunks.values.toList()
    fun totalChunks(): Int = allChunks.size

    val sameTopicThreshold: Float get() = embedder.sameTopicThreshold

    suspend fun retrieveTopChunks(
        query: String,
        topK: Int = 8,
        maxPerDomain: Int = 2
    ): List<RankedChunk> {
        if (allChunks.isEmpty()) return emptyList()

        // 1. BM25 Ranking via In-Memory Index
        val bm25Ids = ftsStore.searchBm25(query, limit = 40)
        val bm25RankMap = bm25Ids.withIndex().associate { (idx, id) -> id to (idx + 1) }

        // 2. Semantisches Ranking via ONNX Cosine Similarity im RAM (Query-Vektor gecacht)
        val queryVec = if (embedder.isReady) {
            if (cachedQueryText == query && cachedQueryVec != null) {
                cachedQueryVec
            } else {
                embedder.embed(query)?.also {
                    cachedQueryText = query
                    cachedQueryVec = it
                }
            }
        } else {
            null
        }
        val semanticScored = if (queryVec != null) {
            allChunks.values
                .map { c -> c.chunkId to (c.embedding?.let { OnnxEmbedder.cosineSimilarity(queryVec, it) } ?: 0f) }
                .sortedByDescending { it.second }
        } else {
            emptyList()
        }
        val semanticRankMap = semanticScored.withIndex().associate { (idx, pair) -> pair.first to (idx + 1) }
        val semanticScoreMap = semanticScored.toMap()

        // 3. Cross-Source-Korrelation (Tokens einmal vorab berechnen statt O(N^2) Regex)
        val candidateIds = (bm25Ids + semanticScored.take(35).map { it.first }).distinct()
        val candidates = candidateIds.mapNotNull { allChunks[it] }
        val keyTokensById = candidates.associate { c -> c.chunkId to extractKeyTokens(c.rawText) }

        val rrfK = 60f
        val scored = candidates.map { chunk ->
            val bm25Rank = bm25RankMap[chunk.chunkId]
            val semRank = semanticRankMap[chunk.chunkId]
            val semScore = semanticScoreMap[chunk.chunkId] ?: 0f

            val rrfBm25 = bm25Rank?.let { 1f / (rrfK + it) } ?: 0f
            val rrfSem = semRank?.let { 1f / (rrfK + it) } ?: 0f

            val corroboratingDomains = countCorroboratingDomains(chunk, candidates, keyTokensById)
            // Cross-Source-Bonus (+18 % pro bestaetigender Fremd-Domain, gedeckelt bei +45 %)
            val crossBoost = 1f + (corroboratingDomains * 0.18f).coerceAtMost(0.45f)

            RankedChunk(
                chunk = chunk,
                bm25Rank = bm25Rank,
                semanticScore = semScore,
                crossSourceDomains = corroboratingDomains,
                finalScore = (rrfBm25 + rrfSem * 1.15f) * crossBoost
            )
        }.sortedByDescending { it.finalScore }

        // 4. Strenge Kontext-Diaet: Max. `maxPerDomain` Chunks derselben Domain
        val domainCounts = HashMap<String, Int>()
        val selected = mutableListOf<RankedChunk>()
        for (item in scored) {
            val count = domainCounts.getOrDefault(item.chunk.domain, 0)
            if (count < maxPerDomain) {
                selected.add(item)
                domainCounts[item.chunk.domain] = count + 1
                if (selected.size >= topK) break
            }
        }
        return selected
    }

    private fun countCorroboratingDomains(
        target: TextChunk,
        pool: List<TextChunk>,
        keyTokensById: Map<Int, Set<String>>
    ): Int {
        val targetTokens = keyTokensById[target.chunkId].orEmpty()
        val matchedDomains = HashSet<String>()

        for (other in pool) {
            if (other.domain == target.domain) continue
            val targetVec = target.embedding
            val otherVec = other.embedding
            val isSimilar = if (targetVec != null && otherVec != null) {
                OnnxEmbedder.cosineSimilarity(targetVec, otherVec) >= embedder.sameTopicThreshold - 0.02f
            } else {
                val otherTokens = keyTokensById[other.chunkId].orEmpty()
                val overlap = targetTokens.intersect(otherTokens).size
                overlap >= 4 && (overlap.toFloat() / targetTokens.size.coerceAtLeast(1)) >= 0.22f
            }
            if (isSimilar) matchedDomains.add(other.domain)
        }
        return matchedDomains.size
    }

    private fun extractKeyTokens(text: String): Set<String> =
        WORD_REGEX.findAll(text.lowercase())
            .map { it.value }
            .filter { it.length >= 4 }
            .toSet()

    private companion object {
        val WORD_REGEX = Regex("[\\p{L}\\p{N}]+")
    }
}
