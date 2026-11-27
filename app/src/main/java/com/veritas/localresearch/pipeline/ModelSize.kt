package com.veritas.localresearch.pipeline

/** Parameterzahl im Dateinamen: "Qwen3-1.7B" (Milliarden) oder "SmolLM2-360M" (Millionen) */
private val SIZE_B = Regex("(?i)(?<![0-9.a-z])([0-9]+(?:[.,][0-9]+)?)B(?![a-z])")
private val SIZE_M = Regex("(?<![0-9])([1-9][0-9]{1,2})M(?![a-zA-Z])")

/** Modellgroesse in Milliarden Parametern aus dem Dateinamen, null wenn sie nicht darin steht */
fun modelSizeB(name: String): Float? =
    SIZE_B.find(name)?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull()
        ?: SIZE_M.find(name)?.groupValues?.get(1)?.toFloatOrNull()?.div(1000f)

/** Mindestanteil gemeinsamer Wortpaare mit dem zitierten Quellsatz: streng bis 2.5B, lockerer darueber, 0.5 ohne Angabe */
fun wordingThreshold(name: String): Float {
    val size = modelSizeB(name) ?: return 0.5f
    return if (size <= 2.5f) 0.6f else 0.4f
}
