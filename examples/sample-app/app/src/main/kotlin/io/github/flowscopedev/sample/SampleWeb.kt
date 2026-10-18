package io.github.flowscopedev.sample

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.flowscope.agent.FlowScopeAgent

/** One WebView setup for the XML and Compose hosts, so a flow can target either with the same `web-*` steps. */
object SampleWeb {
    const val ORIGIN = "https://sample.flowscope.dev"
    const val BRIDGE_NAME = "SampleAndroid"

    /** Methods exposed to the page. `notExposed` has no annotation and must never be listed as a bridge method. */
    class Bridge {
        @JavascriptInterface fun ping(): String = "pong"
        @JavascriptInterface fun appVersion(): String = "1.0"
        fun notExposed(): String = "hidden"
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView, label: String) {
        WebView.setWebContentsDebuggingEnabled(true)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        // The wrapper delegates to addJavascriptInterface and records the bridge for FlowScope.
        FlowScopeAgent.addJavascriptInterface(webView, Bridge(), BRIDGE_NAME)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                FlowScopeAgent.observeWebView(view, label)
            }
        }
        // Metadata-only cookies: FlowScope may assert names and flags but never shows values.
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setCookie(ORIGIN, "sample_session=metadata-only; Path=/; Secure; HttpOnly; SameSite=Lax")
            flush()
        }
        webView.loadDataWithBaseURL(ORIGIN, HTML, "text/html", "UTF-8", null)
    }

    private const val HTML = """
        <!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
        <title>FlowScope sample page</title>
        <style>body{font-family:sans-serif;padding:20px}input,button{font-size:18px;margin:6px 0;width:100%;padding:8px}</style>
        <h1 id="ready">Sample page ready</h1>
        <form id="checkout" onsubmit="return false">
          <input name="email" type="email" placeholder="Email" aria-label="Email">
          <button type="submit" id="pay">Pay now</button>
        </form>
        <button id="ping">Ping native bridge</button>
        <p id="result"></p>
        <iframe title="sample-frame" srcdoc="<p id='frame-ready'>Frame ready</p>"></iframe>
        <script>
          localStorage.setItem('customerId', 'sample-customer');
          sessionStorage.setItem('checkoutState', 'ready');
          document.cookie = 'sample_locale=en-IN; Path=/; Secure; SameSite=Lax';
          document.getElementById('pay').addEventListener('click', function () {
            document.getElementById('result').textContent = 'Payment complete';
          });
          document.getElementById('ping').addEventListener('click', function () {
            document.getElementById('result').textContent = SampleAndroid.ping();
          });
        </script>
    """
}
