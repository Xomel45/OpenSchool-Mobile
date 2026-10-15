package io.github.xomel45.openschool

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.openschool_bridge.Client

enum class NetMode(val title: String) { Auto("Автоматически"), Direct("Напрямую"), System("Как в системе") }

/**
 * Gosuslugi refuses foreign addresses. On the phone the route is decided by the system (Wi-Fi proxy, a VPN app), and an app
 * cannot step around a VPN; what it can do is (a) say honestly which country the traffic leaves from and (b) make the
 * login window ignore a Wi-Fi/HTTP proxy. "Auto" does (b) whenever the country is not Russia.
 */
class NetPolicy(context: Context) {
    private val prefs = context.getSharedPreferences("net", Context.MODE_PRIVATE)

    var mode by mutableStateOf(runCatching { NetMode.valueOf(prefs.getString("mode", "") ?: "") }.getOrDefault(NetMode.Auto))
        private set
    /** Country (two letters) of the last check, `null` when unknown. */
    var country by mutableStateOf<String?>(null)
        private set
    /** The login window and the Bridge connect without a proxy. */
    var direct by mutableStateOf(mode == NetMode.Direct)
        private set

    fun choose(m: NetMode) { mode = m; prefs.edit().putString("mode", m.name).apply(); direct = m == NetMode.Direct }

    // Without a system WebView the feature check itself would throw (it loads the WebView): then there is nothing to set.
    val overrideSupported: Boolean get() = webViewInstalled() && WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    /** Ask a public service which country the traffic leaves from, and settle `direct` for the mode. */
    suspend fun resolve(): String? {
        val code = withContext(Dispatchers.IO) {
            withTimeoutOrNull(10_000) { runCatching { Client().exitCountry() }.getOrNull() }
        }
        country = code
        direct = when (mode) {
            NetMode.Direct -> true
            NetMode.System -> false
            NetMode.Auto -> code != null && !code.equals("RU", ignoreCase = true) // a foreign address always means "go direct"
        }
        return code
    }

    /** Make the WebView (login window) use the decided route. Cleared when the system route is wanted. */
    fun applyToWebView(context: Context) {
        if (!overrideSupported) return
        val controller = ProxyController.getInstance()
        val run = java.util.concurrent.Executor { it.run() }
        if (direct) controller.setProxyOverride(ProxyConfig.Builder().addDirect().build(), run) { }
        else controller.clearProxyOverride(run) { }
    }

    fun newClient(): Client = if (direct) Client.direct() else Client()
}
