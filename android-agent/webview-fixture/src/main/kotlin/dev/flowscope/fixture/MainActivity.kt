package dev.flowscope.fixture

import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.flowscope.agent.FlowScopeAgent

/** Deterministic local fixture for opt-in connected-device WebView smoke tests. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(true)
        val webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        FlowScopeAgent.addJavascriptInterface(webView, FixtureBridge(), "FixtureAndroid")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                FlowScopeAgent.observeWebView(view, "fixture")
            }
        }
        setContentView(webView)

        val origin = "https://fixture.flowscope.dev"
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setCookie(origin, "fixture_session=metadata-only; Path=/; Secure; HttpOnly; SameSite=Lax")
            flush()
        }
        webView.loadDataWithBaseURL(origin, HTML, "text/html", "UTF-8", null)
    }

    class FixtureBridge {
        @JavascriptInterface fun ping(): String = "pong"
        @JavascriptInterface fun version(): Int = 1
        fun notExposed(): String = "hidden"
    }

    private companion object {
        const val HTML = """
            <!doctype html><meta charset="utf-8"><title>FlowScope fixture</title>
            <h1 id="ready">Fixture ready</h1>
            <input id="email" aria-label="Email"><button id="continue">Continue</button>
            <iframe title="fixture-frame" srcdoc="<p id='frame-ready'>Frame ready</p>"></iframe>
            <script>
              localStorage.setItem('customerId', 'fixture-customer');
              sessionStorage.setItem('checkoutState', 'ready');
              document.cookie = 'fixture_locale=en-IN; Path=/; Secure; SameSite=Lax';
            </script>
        """
    }
}
