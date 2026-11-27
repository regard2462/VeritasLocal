package com.veritas.localresearch.llm

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Schreibt Token-IDs fuer die Texte aus VL_TOK_IN, damit ein Skript sie mit der Rust-Bibliothek `tokenizers` vergleicht.
 * Zeilen mit " ||| " werden als Paar (Frage, Abschnitt) im Format des Rerankers kodiert.
 */
class UnigramTokenizerTest {
    @Test
    fun dumpIds() {
        val json = System.getenv("VL_TOK_JSON") ?: ""
        val input = System.getenv("VL_TOK_IN") ?: ""
        assumeTrue(json.isNotBlank() && File(json).exists() && File(input).exists())
        val tok = UnigramTokenizer(File(json))
        val ws = Regex("\\s+")
        val lines = File(input).readLines(Charsets.UTF_8)
        File(System.getenv("VL_TOK_OUT")).writeText(
            lines.joinToString("\n") { line ->
                if (" ||| " in line) {
                    val (q, d) = line.split(" ||| ", limit = 2).map { it.replace(ws, " ").trim() }
                    (listOf(0) + tok.encode(q) + listOf(2, 2) + tok.encode(d) + listOf(2)).joinToString(" ")
                } else {
                    tok.tokenize(line, 4096).inputIds.joinToString(" ")
                }
            },
            Charsets.UTF_8
        )
    }
}
