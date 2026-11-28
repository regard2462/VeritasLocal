package com.veritas.localresearch.ui

import androidx.compose.runtime.Composable

/** Desktop: kein eingebauter Browser, deshalb kein Test zum Selbstloesen (canSolveCaptcha ist hier false). */
@Composable
fun CaptchaDialog(engineName: String, url: String, userAgent: String, onSolved: () -> Unit, onDismiss: () -> Unit) {
    onDismiss()
}
