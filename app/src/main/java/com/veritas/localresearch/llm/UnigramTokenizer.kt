package com.veritas.localresearch.llm

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.text.Normalizer

/**
 * SentencePiece-Unigram-Tokenizer fuer XLM-RoBERTa-Modelle (multilingual-e5, paraphrase-multilingual),
 * gelesen aus der tokenizer.json von Hugging Face. Bildet den Ablauf der Rust-Bibliothek `tokenizers` nach:
 * Normalisierung (NFKC, mehrfache Leerzeichen), Metaspace mit Praefix, Viterbi ueber die Stueck-Wahrscheinlichkeiten,
 * zusammengefasste Unbekannt-Stuecke, dann <s> ... </s>.
 *
 * Beim ersten Laden entsteht neben der JSON-Datei ein kompakter Binaer-Cache, damit spaetere Starts schnell sind.
 */
class UnigramTokenizer(tokenizerJson: File) : EmbeddingTokenizer {

    private val pieceIds: HashMap<String, Int>
    private val scores: FloatArray
    private val unkId: Int
    private val bosId: Int
    private val eosId: Int
    private val unkScore: Float
    private val maxPieceLen: Int

    init {
        val cache = File(tokenizerJson.parentFile, tokenizerJson.name + ".bin")
        val (pieces, sc) = if (cache.exists() && cache.lastModified() >= tokenizerJson.lastModified()) {
            readCache(cache)
        } else {
            val parsed = parseVocab(tokenizerJson.readText(Charsets.UTF_8))
            runCatching { writeCache(cache, parsed.first, parsed.second) }
            parsed
        }
        pieceIds = HashMap(pieces.size * 2)
        var maxLen = 1
        for (i in pieces.indices) {
            // Erstes Vorkommen gewinnt, wie in der Rust-Bibliothek
            pieceIds.putIfAbsent(pieces[i], i)
            val cps = pieces[i].codePointCount(0, pieces[i].length)
            if (cps > maxLen) maxLen = cps
        }
        scores = sc
        maxPieceLen = maxLen.coerceAtMost(64)
        unkId = pieceIds["<unk>"] ?: 3
        bosId = pieceIds["<s>"] ?: 0
        eosId = pieceIds["</s>"] ?: 2
        var minScore = Float.MAX_VALUE
        for (s in sc) if (s < minScore) minScore = s
        unkScore = minScore - UNK_PENALTY
    }

    override fun tokenize(text: String, maxLength: Int): TokenizedInput {
        val ids = encode(text)
        val body = ids.take((maxLength - 2).coerceAtLeast(0))
        val all = LongArray(body.size + 2)
        all[0] = bosId.toLong()
        for (i in body.indices) all[i + 1] = body[i].toLong()
        all[all.size - 1] = eosId.toLong()
        return TokenizedInput(
            inputIds = all,
            attentionMask = LongArray(all.size) { 1L },
            tokenTypeIds = LongArray(all.size)
        )
    }

    /** Token-IDs ohne <s> und </s>. */
    fun encode(text: String): List<Int> {
        // Wie die Precompiled-Tabelle von SentencePiece: Steuerzeichen weg, Sonder-Leerzeichen zu " ", dann NFKC
        val cleaned = text.replace(CONTROL_CHARS, "").replace(SPACE_LIKE, " ")
        val normalized = Normalizer.normalize(cleaned, Normalizer.Form.NFKC)
            .replace(MULTI_SPACE, " ")
        if (normalized.isEmpty()) return emptyList()
        // Metaspace: Leerzeichen werden zu ▁, vorne kommt ein ▁ dazu, jedes Wort beginnt mit seinem ▁
        val replaced = normalized.replace(" ", META)
        val withPrefix = if (replaced.startsWith(META)) replaced else META + replaced
        val out = ArrayList<Int>(withPrefix.length / 3 + 4)
        var start = 0
        for (i in 1..withPrefix.length) {
            if (i == withPrefix.length || withPrefix[i] == META.first()) {
                if (i > start) viterbi(withPrefix.substring(start, i), out)
                start = i
            }
        }
        return out
    }

    private fun viterbi(word: String, out: MutableList<Int>) {
        val cps = word.codePoints().toArray()
        val n = cps.size
        val bestScore = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val bestStart = IntArray(n + 1) { -1 }
        val bestId = IntArray(n + 1) { -1 }
        bestScore[0] = 0f
        for (s in 0 until n) {
            if (bestScore[s] == Float.NEGATIVE_INFINITY) continue
            var hasSingle = false
            val maxEnd = minOf(n, s + maxPieceLen)
            for (e in s + 1..maxEnd) {
                val piece = String(cps, s, e - s)
                val id = pieceIds[piece] ?: continue
                if (e == s + 1) hasSingle = true
                val cand = bestScore[s] + scores[id]
                if (cand > bestScore[e]) {
                    bestScore[e] = cand
                    bestStart[e] = s
                    bestId[e] = id
                }
            }
            if (!hasSingle) {
                val cand = bestScore[s] + unkScore
                if (cand > bestScore[s + 1]) {
                    bestScore[s + 1] = cand
                    bestStart[s + 1] = s
                    bestId[s + 1] = unkId
                }
            }
        }
        val rev = ArrayList<Int>()
        var pos = n
        while (pos > 0) {
            val id = bestId[pos]
            // Aufeinanderfolgende Unbekannt-Stuecke werden zu einem zusammengefasst
            if (!(id == unkId && rev.isNotEmpty() && rev.last() == unkId)) rev.add(id)
            pos = bestStart[pos]
        }
        for (i in rev.indices.reversed()) out.add(rev[i])
    }

    private companion object {
        const val META = "▁"
        const val UNK_PENALTY = 10f
        const val CACHE_MAGIC = 0x554E4731 // "UNG1"
        val MULTI_SPACE = Regex(" {2,}")
        val CONTROL_CHARS = Regex("[\u0000-\u0008\u000B\u000E-\u001F\u007F]")
        val SPACE_LIKE = Regex("[\t\n\r\u000C\u00A0\u1680\u2000-\u200F\u2028\u2029\u202F\u205F\u3000\uFEFF\uFFFD]")

        /** Liest model.vocab ([["stueck", score], ...]) ohne die ganze JSON-Datei als Baum zu laden. */
        fun parseVocab(json: String): Pair<Array<String>, FloatArray> {
            val modelIdx = json.indexOf("\"model\"")
            require(modelIdx >= 0) { "tokenizer.json ohne model" }
            val vocabKey = json.indexOf("\"vocab\"", modelIdx)
            require(vocabKey >= 0) { "tokenizer.json ohne vocab" }
            var i = json.indexOf('[', vocabKey) + 1
            val pieces = ArrayList<String>(260_000)
            val sc = ArrayList<Float>(260_000)
            val sb = StringBuilder()
            while (i < json.length) {
                while (json[i].isWhitespace() || json[i] == ',') i++
                if (json[i] == ']') break
                require(json[i] == '[') { "Unerwartetes Zeichen in vocab" }
                i++
                while (json[i].isWhitespace()) i++
                require(json[i] == '"')
                i++
                sb.setLength(0)
                while (json[i] != '"') {
                    val c = json[i]
                    if (c == '\\') {
                        i++
                        when (val esc = json[i]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> { sb.append(json.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                            else -> sb.append(esc)
                        }
                    } else {
                        sb.append(c)
                    }
                    i++
                }
                i++
                pieces.add(sb.toString())
                while (json[i].isWhitespace() || json[i] == ',') i++
                val numStart = i
                while (json[i] != ']' && !json[i].isWhitespace()) i++
                sc.add(json.substring(numStart, i).toFloat())
                while (json[i] != ']') i++
                i++
            }
            return pieces.toTypedArray() to sc.toFloatArray()
        }

        fun writeCache(file: File, pieces: Array<String>, sc: FloatArray) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            DataOutputStream(BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { out ->
                out.writeInt(CACHE_MAGIC)
                out.writeInt(pieces.size)
                for (i in pieces.indices) {
                    out.writeUTF(pieces[i])
                    out.writeFloat(sc[i])
                }
            }
            if (!tmp.renameTo(file)) tmp.delete()
        }

        fun readCache(file: File): Pair<Array<String>, FloatArray> =
            DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 16)).use { inp ->
                require(inp.readInt() == CACHE_MAGIC)
                val n = inp.readInt()
                val pieces = Array(n) { "" }
                val sc = FloatArray(n)
                for (i in 0 until n) {
                    pieces[i] = inp.readUTF()
                    sc[i] = inp.readFloat()
                }
                pieces to sc
            }
    }
}
