package io.github.xomel45.openschool

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.webkit.WebViewCompat

/** The Gosuslugi login page needs Chrome 80+ (it uses `?.`): an older system WebView shows a blank page (seen on Android 10). */
const val MIN_WEBVIEW_MAJOR = 80

/** "74.0.3729.185" -> 74; null when it cannot be read. Pure: JVM-tested. */
fun webViewMajor(version: String?): Int? = version?.substringBefore('.')?.toIntOrNull()

fun webViewVersion(context: Context): String? = runCatching { WebViewCompat.getCurrentWebViewPackage(context)?.versionName }.getOrNull()

/** On the first screen: says that the login will not work and opens the WebView page of the store. Nothing when it is fine. */
@Composable
fun OldWebViewNotice() {
    val context = LocalContext.current
    val version = remember { webViewVersion(context) }
    val major = webViewMajor(version) ?: return
    if (major >= MIN_WEBVIEW_MAJOR) return
    Text(
        "Встроенный браузер телефона устарел (версия $major), и страница входа Госуслуг в нём не откроется. Обновите «Android System WebView» (или Google Chrome) в магазине приложений, потом войдите.",
        Modifier.padding(top = 20.dp), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center,
    )
    OutlinedButton({
        val pkg = runCatching { WebViewCompat.getCurrentWebViewPackage(context)?.packageName }.getOrNull() ?: "com.google.android.webview"
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(market) }.onFailure {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }, Modifier.padding(top = 8.dp)) { Text("Обновить WebView") }
}
