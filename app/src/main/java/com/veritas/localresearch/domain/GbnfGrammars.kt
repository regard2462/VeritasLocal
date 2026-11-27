package com.veritas.localresearch.domain

object GbnfGrammars {

    private const val PRIMITIVES = """
ws ::= [ \t\n]*
string ::= "\"" ([^"\\\x7F\x00-\x1F] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F]{4}))* "\""
bool ::= "true" | "false"
int ::= [0-9]+
stringlist ::= "[" ws (string (ws "," ws string)*)? ws "]"
"""

    val QUERY_PLAN: String = """
root ::= "{" ws "\"queries\":" ws stringlist "," ws "\"en_query\":" ws string "," ws "\"use_wikipedia\":" ws bool "," ws "\"use_arxiv\":" ws bool ws "}"
$PRIMITIVES
""".trimIndent()

    val GAP_CHECK: String = """
root ::= "{" ws "\"sufficient\":" ws bool "," ws "\"missing_info\":" ws string "," ws "\"follow_up_queries\":" ws stringlist ws "}"
$PRIMITIVES
""".trimIndent()

    /** Gliederung: Abschnitte mit Ueberschrift und den Nummern der zugeordneten gepruefen Fakten. */
    val OUTLINE: String = """
root ::= "{" ws "\"sections\":" ws "[" ws section (ws "," ws section)* ws "]" ws "}"
section ::= "{" ws "\"title\":" ws string "," ws "\"facts\":" ws intlist ws "}"
intlist ::= "[" ws (int (ws "," ws int)*)? ws "]"
$PRIMITIVES
""".trimIndent()

    val CLAIM_EXTRACTION: String = """
root ::= "{" ws "\"claims\":" ws "[" ws (claimobj (ws "," ws claimobj)*)? ws "]" ws "}"
claimobj ::= "{" ws "\"claim\":" ws string "," ws "\"exact_quote\":" ws string "," ws "\"chunk_id\":" ws int ws "}"
$PRIMITIVES
""".trimIndent()
}
