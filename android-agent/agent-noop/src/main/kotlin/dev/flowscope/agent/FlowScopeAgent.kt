package dev.flowscope.agent

import android.app.Activity
import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView

/**
 * Release twin of `dev.flowscope:agent`. Same public API, no behaviour, no manifest entries.
 */
object FlowScopeAgent {
	@Suppress("UNUSED_PARAMETER")
	fun onSemanticAction(activity: Activity?, action: String, componentId: String, screen: String? = null) = Unit

	@Suppress("UNUSED_PARAMETER")
	fun onNetwork(
		requestId: String,
		phase: String,
		method: String,
		routeTemplate: String,
		statusCode: Int? = null,
		durationMillis: Long? = null
	) = Unit

	@Suppress("UNUSED_PARAMETER")
	fun onBackgroundWork(workName: String, state: String) = Unit

	@Suppress("UNUSED_PARAMETER")
	fun installReferrerOverride(context: Context): String? = null

	@Suppress("UNUSED_PARAMETER")
	@SuppressLint("JavascriptInterface") // API-compatible delegate; the app owns bridge annotations.
	fun addJavascriptInterface(webView: WebView, bridge: Any, name: String) =
		webView.addJavascriptInterface(bridge, name)

	@Suppress("UNUSED_PARAMETER")
	fun removeJavascriptInterface(webView: WebView, name: String) =
		webView.removeJavascriptInterface(name)

	@Suppress("UNUSED_PARAMETER")
	fun observeWebView(webView: WebView, label: String) = Unit
}
