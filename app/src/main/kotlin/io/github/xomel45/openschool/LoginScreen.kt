package io.github.xomel45.openschool

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import uniffi.openschool_bridge.loginUrl

private const val HOST = "www.gosuslugi.ru"
private const val CHECK_EVERY_MS = 4_000L
private const val MAX_REFUSALS = 15
/** Refused hidden attempts after which the diary page is simply shown (no endless loop when ESIA sends it straight back). */
private const val MAX_HIDDEN_TRIES = 3

/**
 * Gosuslugi/ESIA login in a WebView. The user types the password and the code there. When ESIA sends the page back to the diary
 * (`www.gosuslugi.ru/school…`), the diary page is not loaded at all: the WebView is covered by «Входим…», its cookies go to the
 * Bridge and the server decides. A refused attempt shows the WebView again and starts the login over.
 * The page can never start another app (`intent:`, `market:`, custom schemes): only http(s) is loaded.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(tryLogin: suspend (String) -> Boolean) {
    val scope = rememberCoroutineScope()
    val web = remember { arrayOfNulls<WebView>(1) }
    var busy by remember { mutableStateOf(false) }
    SmsCodeEffect { code -> web[0]?.evaluateJavascript(FILL_CODE_JS.replace("__CODE__", code)) { println("sms fill: $it") } }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    web[0] = this
                    WebView.setWebContentsDebuggingEnabled(context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    var lastCheck = 0L
                    var refusals = 0
                    var checking = false
                    var wentToLogin = false // the page has been on the ESIA login (or anywhere off the diary) at least once
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val uri = request.url
                            if (uri.scheme != "https" && uri.scheme != "http") return true // never hand a link to another app
                            if (request.isForMainFrame && isDiary(uri) && hide()) return takeCookies(view)
                            return false
                        }
                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            val uri = android.net.Uri.parse(url)
                            if (!isDiary(uri)) { wentToLogin = true; return }
                            // A server redirect does not always pass shouldOverrideUrlLoading: stop the diary page here too.
                            if (hide()) takeCookies(view) else maybeCheck()
                        }
                        override fun onPageFinished(view: WebView, url: String) {
                            if (isDiary(android.net.Uri.parse(url)) && !hide()) maybeCheck() // signed in from before, or hidden tries used up
                        }

                        private fun hide() = wentToLogin && refusals < MAX_HIDDEN_TRIES
                        private fun isDiary(uri: android.net.Uri) = uri.host == HOST && uri.path?.startsWith("/school") == true

                        /** Back from ESIA: do not show the diary site, take the cookies and let the server decide. */
                        private fun takeCookies(view: WebView): Boolean {
                            view.stopLoading()
                            if (checking) return true
                            val header = CookieManager.getInstance().getCookie("https://$HOST")
                            if (header == null) { view.loadUrl(loginUrl()); return true }
                            checking = true
                            busy = true
                            scope.launch {
                                val ok = tryLogin(header)
                                checking = false
                                if (!ok) {
                                    refusals++
                                    busy = false
                                    wentToLogin = false
                                    view.loadUrl(loginUrl()) // start over: Gosuslugi sends the page to ESIA again
                                }
                            }
                            return true
                        }

                        /** The diary opened straight away (cookies of an earlier login): try them, without hiding anything. */
                        private fun maybeCheck() {
                            val now = System.currentTimeMillis()
                            if (checking || refusals >= MAX_REFUSALS || now - lastCheck < CHECK_EVERY_MS) return
                            val header = CookieManager.getInstance().getCookie("https://$HOST") ?: return
                            lastCheck = now
                            checking = true
                            scope.launch {
                                if (!tryLogin(header)) refusals++
                                checking = false
                            }
                        }
                    }
                    loadUrl(loginUrl())
                }
            },
            onRelease = { web[0] = null; it.destroy() },
        )
        if (busy) {
            Column(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Входим…")
            }
        }
    }
}

/** Puts the code into the page's code field (one field, or one box per digit) the way typing would: through the native setter, then input events. */
private const val FILL_CODE_JS = """
(function(code){
  var set=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;
  function put(el,v){el.focus();set.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}
  var all=[].slice.call(document.querySelectorAll('input')).filter(function(i){
    var t=(i.type||'text').toLowerCase();return !i.disabled&&!i.readOnly&&i.offsetParent!==null&&['text','tel','number','password'].indexOf(t)>=0;});
  var boxes=all.filter(function(i){return i.maxLength===1;});
  if(boxes.length>=code.length){for(var k=0;k<code.length;k++)put(boxes[k],code.charAt(k));return 'boxes';}
  var hint=/code|otp|sms|код|смс/i;
  var one=all.filter(function(i){return i.autocomplete==='one-time-code'||hint.test([i.name,i.id,i.placeholder,i.getAttribute('aria-label')].join(' '));})[0]||(all.length===1?all[0]:null);
  if(one){put(one,code);return 'single';}
  return 'none:'+all.length;
})('__CODE__')
"""
