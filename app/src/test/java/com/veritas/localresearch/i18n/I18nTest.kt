package com.veritas.localresearch.i18n

import com.veritas.localresearch.domain.LanguageDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class I18nTest {

    private val placeholder = Regex("\\{\\d\\}")

    @Test
    fun everyLanguageHasEveryKeyAndPlaceholder() {
        val problems = mutableListOf<String>()
        assertEquals(I18n.LANGUAGES.map { it.code }.toSet(), I18n.TABLES.keys)
        for ((code, table) in I18n.TABLES) {
            (EN.keys - table.keys).forEach { problems += "$code: fehlt $it" }
            (table.keys - EN.keys).forEach { problems += "$code: unbekannt $it" }
            for ((key, en) in EN) {
                val t = table[key] ?: continue
                if (t.isBlank()) problems += "$code: leer $key"
                val want = placeholder.findAll(en).map { it.value }.toSortedSet()
                val have = placeholder.findAll(t).map { it.value }.toSortedSet()
                if (want != have) problems += "$code: $key hat $have statt $want"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun formatsArgumentsInTheChosenLanguage() {
        I18n.setPreference("en")
        assertEquals("3 sources", tr("sources_n", 3))
        I18n.setPreference("de")
        assertEquals("Indexiere Buch: 2 von 9 Abschnitten…", tr("indexing", "Buch", 2, 9))
        I18n.setPreference("xx")
        assertEquals(I18n.systemCode(), I18n.code)
    }

    @Test
    fun detectsLanguagesOfQuestions() {
        fun code(q: String) = LanguageDetector.detect(q)?.code
        assertEquals("de", code("Wie funktioniert ein Batteriespeicher für Solarstrom?"))
        assertEquals("en", code("How does a home battery store solar power?"))
        assertEquals("ja", code("家庭用蓄電池はどのように太陽光発電を蓄えますか？"))
        assertEquals("zh", code("家用电池如何储存太阳能？"))
        assertEquals("ko", code("가정용 배터리는 태양광 전기를 어떻게 저장합니까?"))
        assertEquals("ru", code("Как домашний аккумулятор хранит солнечную энергию?"))
        assertEquals("uk", code("Як домашній акумулятор зберігає сонячну енергію?"))
        assertEquals("ar", code("كيف تخزن البطارية المنزلية الطاقة الشمسية؟"))
        assertEquals("es", code("¿Cómo almacena una batería doméstica la energía solar de los paneles?"))
        assertEquals("pl", code("Jak domowy akumulator przechowuje energię słoneczną i czy jest to opłacalne?"))
    }
}
