package com.veritas.localresearch.scraping

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceMetaExtractorTest {

    @Test
    fun siteNameFromLogo() {
        val html = """<html><head><title>LWA  -  Wetterdaten</title></head><body>
            <header><div class="logo"><a href="/"><img src="logo.svg" alt="Logo: Landeswetteramt (Link zur Startseite)"></a></div></header>
            <main><p>Text</p><img alt="Logo der Europäischen Union" src="eu.png"></main></body></html>"""
        assertEquals("Landeswetteramt", SourceMetaExtractor.fromHtml(Jsoup.parse(html)).siteName)
    }

    @Test
    fun siteNameFromJsonLd() {
        val html = """<html><head><script type="application/ld+json">{"@context":"https://schema.org","@graph":[
            {"@type":"WebSite","name":"Startseite"},
            {"@type":"Organization","name":"Datenverband Süd","logo":{"@type":"ImageObject","url":"x.png"}}]}</script></head><body></body></html>"""
        assertEquals("Datenverband Süd", SourceMetaExtractor.fromHtml(Jsoup.parse(html)).siteName)
    }

    @Test
    fun siteNameFromHomeTitle() {
        assertEquals("Landeswetteramt", SourceMetaExtractor.siteNameFromHomeTitle("LWA  -  Landeswetteramt", "www.lwa.example.org"))
        assertEquals("Verband für Datenmanagement", SourceMetaExtractor.siteNameFromHomeTitle("VFD | Verband für Datenmanagement", "www.vfd.example.org"))
    }
}
