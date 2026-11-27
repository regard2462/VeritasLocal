package com.veritas.localresearch.llm

import java.io.File
import java.text.Normalizer

class WordPieceTokenizer(vocabFile: File) : EmbeddingTokenizer {

    private val vocab: Map<String, Long>
    private val clsId: Long
    private val sepId: Long
    private val unkId: Long

    init {
        val map = HashMap<String, Long>(32000)
        vocabFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEachIndexed { idx, token ->
                map[token.trim()] = idx.toLong()
            }
        }
        vocab = map
        clsId = vocab["[CLS]"] ?: 101L
        sepId = vocab["[SEP]"] ?: 102L
        unkId = vocab["[UNK]"] ?: 100L
    }


    override fun tokenize(text: String, maxLength: Int): TokenizedInput {
        val lowered = text.lowercase().replace("ß", "ss")
        val cleaned = Normalizer.normalize(lowered, Normalizer.Form.NFD)
            .replace(DIACRITICS_REGEX, "")
            .replace(NON_ALNUM_REGEX, " $1 ")
            .trim()

        val ids = ArrayList<Long>(maxLength)
        ids.add(clsId)

        val words = cleaned.split(WHITESPACE_REGEX).filter { it.isNotBlank() }
        for (word in words) {
            if (ids.size >= maxLength - 1) break
            var start = 0
            val subTokens = mutableListOf<Long>()
            var isBad = false

            while (start < word.length) {
                var end = word.length
                var foundId: Long? = null
                while (start < end) {
                    val piece = if (start > 0) "##${word.substring(start, end)}" else word.substring(start, end)
                    val id = vocab[piece]
                    if (id != null) {
                        foundId = id
                        break
                    }
                    end--
                }
                if (foundId == null) {
                    isBad = true
                    break
                }
                subTokens.add(foundId)
                start = end
            }
            if (isBad) {
                ids.add(unkId)
            } else {
                for (st in subTokens) {
                    if (ids.size >= maxLength - 1) break
                    ids.add(st)
                }
            }
        }
        ids.add(sepId)

        val len = ids.size
        return TokenizedInput(
            inputIds = ids.toLongArray(),
            attentionMask = LongArray(len) { 1L },
            tokenTypeIds = LongArray(len) { 0L }
        )
    }

    private companion object {
        val DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
        val NON_ALNUM_REGEX = Regex("([^a-z0-9\\s])")
        val WHITESPACE_REGEX = Regex("\\s+")
    }
}
