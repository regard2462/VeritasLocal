package com.veritas.localresearch.pipeline

import com.veritas.localresearch.settings.AnswerLength

/**
 * Wie lang und wie tief eine Antwort wird. Aus einer der drei Stufen oder aus einer Wortzahl, die der Nutzer selbst
 * festlegt. Die Laenge haengt an der Zahl gepruefter Fakten (jeder Satz braucht einen Beleg): eine Wortzahl setzt
 * daher auch, wie viele Quellen, Abschnitte und Fakten gesucht werden.
 *
 * [tokenFactor] skaliert das Schreibbudget, [extraFacts] die Zahl der gesuchten Fakten, [sentences] die Satzzahl je
 * Abschnitt (Text fuer den Prompt), [outline] = Gliederung in Abschnitte, [maxSections] hoechstens so viele Abschnitte.
 * [words] > 0: selbst festgelegte Wortzahl.
 */
data class LengthSpec(
    val tokenFactor: Float,
    val extraFacts: Int,
    val sentences: String,
    val outline: Boolean,
    val short: Boolean,
    val detailed: Boolean,
    val maxSections: Int,
    val words: Int = 0
) {
    /** Gesuchte Fakten insgesamt bei selbst festgelegter Wortzahl (etwa ein belegter Satz je 30 Woerter). */
    val factTarget: Int get() = if (words > 0) (words / 30).coerceIn(4, 100) else 0

    companion object {
        const val MIN_WORDS = 200
        const val MAX_WORDS = 3000

        fun of(preset: AnswerLength, words: Int = 0): LengthSpec {
            if (words <= 0) return when (preset) {
                AnswerLength.SHORT -> LengthSpec(0.45f, -2, "2 bis 4", outline = false, short = true, detailed = false, maxSections = 5)
                AnswerLength.MEDIUM -> LengthSpec(1.0f, 0, "3 bis 6", outline = true, short = false, detailed = false, maxSections = 5)
                AnswerLength.LONG -> LengthSpec(1.8f, 4, "5 bis 9", outline = true, short = false, detailed = true, maxSections = 7)
            }
            val w = words.coerceIn(MIN_WORDS, MAX_WORDS)
            val facts = (w / 30).coerceIn(4, 100)
            val sections = (facts / 6).coerceIn(3, 12)
            val perSection = (facts + sections - 1) / sections
            return LengthSpec(
                tokenFactor = w / 330f,
                extraFacts = facts,
                sentences = "${perSection} bis ${perSection + 3}",
                outline = true,
                short = false,
                detailed = w >= 600,
                maxSections = sections,
                words = w
            )
        }
    }
}
