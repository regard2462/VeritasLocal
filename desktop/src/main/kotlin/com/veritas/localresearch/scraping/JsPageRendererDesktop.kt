package com.veritas.localresearch.scraping

import android.content.Context

/** Desktop: kein JavaScript-Ausweichweg; Seiten ohne Text im HTML werden uebersprungen. */
@Suppress("UNUSED_PARAMETER")
fun createJsPageRenderer(context: Context, userAgent: String): JsPageRenderer? = null
