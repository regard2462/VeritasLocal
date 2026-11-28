package com.veritas.localresearch.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.veritas.localresearch.i18n.tr

/**
 * Sichtbares Browserfenster fuer den Test einer Suchmaschine. Der Nutzer loest ihn selbst; die Cookies landen im
 * gemeinsamen Cookie-Speicher der App, den auch der unsichtbare Browser fuer die weiteren Suchen nutzt.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CaptchaDialog(engineName: String, url: String, userAgent: String, onSolved: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("captcha_title", engineName), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(tr("captcha_info", engineName), style = MaterialTheme.typography.bodySmall)
                }
                val context = androidx.compose.ui.platform.LocalContext.current
                val webView = remember {
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = userAgent
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = WebViewClient()
                        loadUrl(url)
                    }
                }
                DisposableEffect(webView) {
                    onDispose {
                        CookieManager.getInstance().flush()
                        runCatching { webView.stopLoading(); webView.destroy() }
                    }
                }
                AndroidView(factory = { webView }, modifier = Modifier.weight(1f).fillMaxWidth())
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                ) {
                    TextButton(onClick = onDismiss) { Text(tr("cancel")) }
                    Button(onClick = { CookieManager.getInstance().flush(); onSolved() }) { Text(tr("captcha_done")) }
                }
            }
        }
    }
}
