package com.veritas.localresearch.scraping

import com.veritas.localresearch.domain.LanguageDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEngineParseTest {

    @Test
    fun seznamResultsWithSnippet() {
        val html = """
            <div data-e-b-n="a1"><h3><a data-e-a="heading" href="https://example.org/heat">Heat pumps in winter</a></h3>
            <div><a data-e-a="reference" href="https://example.org/heat">example.org/heat</a></div>
            <div><span>Output drops below minus ten degrees.</span></div></div>
            <div data-e-b-n="a2"><h3><a data-e-a="heading" href="/obrazky/?q=x">Images</a></h3></div>
        """.trimIndent()
        val r = SearchEngine.SEZNAM.parse(html, "https://search.seznam.cz/?q=x", 6)
        assertEquals(1, r.size)
        assertEquals("https://example.org/heat", r[0].url)
        assertEquals("Heat pumps in winter", r[0].title)
        assertEquals("Output drops below minus ten degrees.", r[0].snippet)
    }

    @Test
    fun naverGroupsLinksOfOneResult() {
        val html = """
            <a data-nlog-area="urB_coR.w.link" href="https://example.org/a"><span>2026.09.15.</span><span>새 창 열림</span></a>
            <a data-nlog-area="urB_coR.w.link" href="https://example.org/a">Cold climate heat pumps<span>새 창 열림</span></a>
            <a data-nlog-area="urB_coR.w.link" href="https://example.org/a">They keep working at low temperatures.</a>
            <a data-nlog-area="urB_b1R.w.link" href="https://example.net/b">Second result</a>
            <a data-nlog-area="urB_coR.w.brand" href="https://example.org/">example.org</a>
        """.trimIndent()
        val r = SearchEngine.NAVER.parse(html, "https://search.naver.com/search.naver", 6)
        assertEquals(listOf("https://example.org/a", "https://example.net/b"), r.map { it.url })
        assertEquals("Cold climate heat pumps", r[0].title)
        assertEquals("They keep working at low temperatures.", r[0].snippet)
    }

    @Test
    fun mwmblJoinsHighlightedParts() {
        val json = """[{"url":"https://example.org/x","title":[{"value":"Heat","is_bold":true},{"value":" pump guide","is_bold":false}],
            "extract":[{"value":"Efficiency ","is_bold":false},{"value":"falls","is_bold":true}],"source":"mwmbl"}]"""
        val r = SearchEngine.MWMBL.parse(json, "https://api.mwmbl.org/search", 6)
        assertEquals(1, r.size)
        assertEquals("Heat pump guide", r[0].title)
        assertEquals("Efficiency falls", r[0].snippet)
    }

    @Test
    fun bingTitleInsideLink() {
        val html = """<ol><li class="b_algo"><div class="b_algoheader"><a href="https://example.org/hp"><h2>Heat pump guide</h2></a></div>
            <div class="b_caption"><p>Efficiency in cold weather.</p></div></li></ol>"""
        val r = SearchEngine.BING.parse(html, "https://www.bing.com/search", 6)
        assertEquals("https://example.org/hp", r.single().url)
        assertEquals("Heat pump guide", r[0].title)
    }

    @Test
    fun firstWordOnlyResultsAreDegraded() {
        fun item(t: String) = com.veritas.localresearch.domain.SearchResultItem(t, "https://example.org/" + t.hashCode(), "", "x")
        val junk = listOf(item("Heat (1995 film)"), item("Miami Heat"), item("Heat - Wikipedia"), item("Watch Heat"))
        val good = listOf(item("Heat pump efficiency in cold climates"), item("Cold climate heat pumps"), item("Heat (1995 film)"))
        assertTrue(SearchEngine.looksDegraded("heat pump efficiency cold climate", junk))
        assertFalse(SearchEngine.looksDegraded("heat pump efficiency cold climate", good))
        assertFalse(SearchEngine.looksDegraded("heat", junk))
    }

    @Test
    fun mwmblOnlyForEnglish() {
        assertNull(SearchEngine.MWMBL.buildUrl("Wärmepumpe", "", LanguageDetector.GERMAN))
        assertTrue(SearchEngine.MWMBL.buildUrl("heat pump", "", null)!!.startsWith("https://api.mwmbl.org/"))
    }
}
