package io.github.xomel45.openschool

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import uniffi.openschool_bridge.loginUrl

private const val HOST = "www.gosuslugi.ru"
private const val CHECK_EVERY_MS = 4_000L
private const val MAX_REFUSALS = 15

/**
 * Gosuslugi/ESIA login in a WebView. The user types the password and the code there; the app only reads the cookies once
 * the page is back on the diary and the server accepts them.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(tryLogin: suspend (String) -> Boolean) {
    val scope = rememberCoroutineScope()
    val web = remember { arrayOfNulls<WebView>(1) }
    SmsCodeEffect { code -> web[0]?.evaluateJavascript(FILL_CODE_JS.replace("__CODE__", code)) { println("sms fill: $it") } }
    Column(Modifier.fillMaxSize()) {
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
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = maybeCheck(url)
                        override fun onPageFinished(view: WebView, url: String) = maybeCheck(url)

                        private fun maybeCheck(url: String) {
                            val uri = android.net.Uri.parse(url)
                            if (uri.host != HOST || uri.path?.startsWith("/school") != true) return
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
