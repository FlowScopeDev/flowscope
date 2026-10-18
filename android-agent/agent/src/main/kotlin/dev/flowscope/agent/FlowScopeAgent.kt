package dev.flowscope.agent

import android.app.Activity
import android.app.Application
import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Window
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebSettings
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Debug instrumentation consumed by the FlowScope desktop recorder (`dev.flowscope:agent`).
 *
 * Installed automatically by [FlowScopeInitProvider]: lifecycle and touch events need no app code.
 * [onSemanticAction], [onNetwork] and [onBackgroundWork] are optional extension points.
 */
object FlowScopeAgent {
	private const val TAG = "FlowScopeAgent"
	private val gestures = WeakHashMap<Activity, Gesture>()

	private var installed = false
	private var applicationContext: Context? = null
	private val wrappedWindows = WeakHashMap<Window, Boolean>()
	private val bridgeRegistrations = linkedMapOf<String, BridgeRegistration>()
	private val observedWebViews = linkedMapOf<String, ObservedWebView>()

	internal fun install(application: Application) {
		if (installed) return
		installed = true
		applicationContext = application.applicationContext
		application.filesDir.resolve("flowscope-webview-registry.json").delete()
		application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
			override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
				wrapWindow(activity)
				onLifecycle(activity, "created")
			}

			override fun onActivityResumed(activity: Activity) {
				wrapWindow(activity)
				onLifecycle(activity, "resumed")
			}

			override fun onActivityPaused(activity: Activity) = onLifecycle(activity, "paused")
			override fun onActivityDestroyed(activity: Activity) = onLifecycle(activity, "destroyed")
			override fun onActivityStarted(activity: Activity) = Unit
			override fun onActivityStopped(activity: Activity) = Unit
			override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
		})
		emit(
			kind = "agent.hello",
			activity = null,
			payload = JSONObject().put("agentVersion", BuildConfig.AGENT_VERSION)
		)
	}

	// Once per window; the wrapper delegates, so it composes with an app's own Window.Callback wrapper.
	private fun wrapWindow(activity: Activity) {
		val window = activity.window
		if (wrappedWindows.put(window, true) != null) return
		val current = window.callback ?: return
		window.callback = TouchRecordingCallback(activity, current)
	}

	private class TouchRecordingCallback(
		private val activity: Activity,
		private val delegate: Window.Callback
	) : Window.Callback by delegate {
		override fun dispatchTouchEvent(event: MotionEvent): Boolean {
			onTouch(activity, event)
			return delegate.dispatchTouchEvent(event)
		}
	}

	internal fun onLifecycle(activity: Activity, state: String) {
		emit(
			kind = "activity.lifecycle",
			activity = activity,
			payload = JSONObject().put("state", state)
		)
		if (state == "destroyed") gestures.remove(activity)
	}

	internal fun onTouch(activity: Activity, event: MotionEvent) {
		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> gestures[activity] = Gesture(
				x = event.rawX,
				y = event.rawY,
				startedAtMillis = event.eventTime,
				target = targetAt(activity.window.decorView, event.rawX, event.rawY)
			)

			MotionEvent.ACTION_UP -> finishGesture(activity, event)
			MotionEvent.ACTION_CANCEL -> gestures.remove(activity)
		}
	}

	fun onSemanticAction(activity: Activity?, action: String, componentId: String, screen: String? = null) {
		emit(
			kind = "semantic.action",
			activity = activity,
			payload = JSONObject()
				.put("action", action)
				.put("componentId", componentId)
				.put("screen", screen ?: JSONObject.NULL)
		)
	}

	fun onNetwork(
		requestId: String,
		phase: String,
		method: String,
		routeTemplate: String,
		statusCode: Int? = null,
		durationMillis: Long? = null
	) {
		emit(
			kind = "network.$phase",
			activity = null,
			payload = JSONObject()
				.put("requestId", requestId)
				.put("method", method)
				.put("routeTemplate", routeTemplate)
				.put("statusCode", statusCode ?: JSONObject.NULL)
				.put("durationMillis", durationMillis ?: JSONObject.NULL)
		)
	}

	fun onBackgroundWork(workName: String, state: String) {
		emit(
			kind = "background.work",
			activity = null,
			payload = JSONObject().put("workName", workName).put("state", state)
		)
	}

	/**
	 * Returns a FlowScope-injected install referrer for debug tests, or null when none is present.
	 * Apps must opt in by consulting this before their normal Play Install Referrer path. The value
	 * stays in the app sandbox and is never emitted through Logcat.
	 */
	fun installReferrerOverride(context: Context): String? = runCatching {
		context.filesDir.resolve("flowscope-install-referrer")
			.takeIf { it.isFile }
			?.readText(Charsets.UTF_8)
			?.takeIf { it.isNotEmpty() }
	}.getOrNull()

	/**
	 * Debug-safe twin of [WebView.addJavascriptInterface]. It delegates unchanged, then records only
	 * the interface and @JavascriptInterface method names for FlowScope inspection.
	 */
	@JvmStatic
	@SuppressLint("JavascriptInterface") // The wrapper records and verifies annotated methods below.
	fun addJavascriptInterface(webView: WebView, bridge: Any, name: String) {
		webView.addJavascriptInterface(bridge, name)
		val webViewId = webViewId(webView)
		val safeName = safeMetadataName(name)
		val methods = bridge.javaClass.methods
			.filter { it.getAnnotation(JavascriptInterface::class.java) != null }
			.map { it.name }
			.distinct()
			.sorted()
		synchronized(bridgeRegistrations) {
			bridgeRegistrations["$webViewId:$safeName"] = BridgeRegistration(webViewId, safeName, methods)
			writeWebViewRegistry()
		}
		emit("webview.bridge.registered", null, JSONObject()
			.put("webViewId", webViewId).put("name", safeName).put("methods", methods))
	}

	/** Delegates to [WebView.removeJavascriptInterface] and removes its safe registry metadata. */
	@JvmStatic
	fun removeJavascriptInterface(webView: WebView, name: String) {
		webView.removeJavascriptInterface(name)
		val webViewId = webViewId(webView)
		val safeName = safeMetadataName(name)
		synchronized(bridgeRegistrations) {
			bridgeRegistrations.remove("$webViewId:$safeName")
			writeWebViewRegistry()
		}
		emit("webview.bridge.removed", null, JSONObject()
			.put("webViewId", webViewId).put("name", safeName))
	}

	/** Records safe WebView settings for debug inspection; it does not retain page content or URLs. */
	@JvmStatic
	fun observeWebView(webView: WebView, label: String) {
		val settings = webView.settings
		val observed = ObservedWebView(
			id = webViewId(webView),
			label = safeMetadataName(label),
			origin = safeWebOrigin(webView.url),
			javaScriptEnabled = settings.javaScriptEnabled,
			domStorageEnabled = settings.domStorageEnabled,
			allowFileAccess = settings.allowFileAccess,
			allowContentAccess = settings.allowContentAccess,
			mixedContentMode = when (settings.mixedContentMode) {
				WebSettings.MIXED_CONTENT_NEVER_ALLOW -> "never"
				WebSettings.MIXED_CONTENT_ALWAYS_ALLOW -> "always"
				WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE -> "compatibility"
				else -> "unknown"
			},
			safeBrowsingEnabled = if (Build.VERSION.SDK_INT >= 26) settings.safeBrowsingEnabled else null,
		)
		synchronized(bridgeRegistrations) {
			observedWebViews[observed.id] = observed
			writeWebViewRegistry()
		}
		emit("webview.observed", null, JSONObject()
			.put("webViewId", observed.id).put("label", observed.label).put("origin", observed.origin))
	}

	private fun writeWebViewRegistry() {
		val context = applicationContext ?: return
		val bridges = org.json.JSONArray()
		bridgeRegistrations.values.forEach { bridge ->
			bridges.put(JSONObject().put("webViewId", bridge.webViewId).put("name", bridge.name)
				.put("methods", org.json.JSONArray(bridge.methods)))
		}
		val webViews = org.json.JSONArray()
		observedWebViews.values.forEach { view ->
			webViews.put(JSONObject().put("id", view.id).put("label", view.label)
				.put("origin", view.origin).put("settings", JSONObject()
					.put("javaScriptEnabled", view.javaScriptEnabled)
					.put("domStorageEnabled", view.domStorageEnabled)
					.put("allowFileAccess", view.allowFileAccess)
					.put("allowContentAccess", view.allowContentAccess)
					.put("mixedContentMode", view.mixedContentMode)
					.put("safeBrowsingEnabled", view.safeBrowsingEnabled ?: JSONObject.NULL)))
		}
		runCatching {
			context.filesDir.resolve("flowscope-webview-registry.json")
				.writeText(JSONObject().put("schemaVersion", 1).put("bridges", bridges)
					.put("webViews", webViews).toString(), Charsets.UTF_8)
		}
	}

	private fun webViewId(webView: WebView): String =
		Integer.toHexString(System.identityHashCode(webView))

	private fun safeMetadataName(value: String): String = value
		.filterNot(Char::isISOControl)
		.take(128)

	private fun safeWebOrigin(raw: String?): String {
		if (raw.isNullOrBlank()) return ""
		return runCatching {
			val uri = Uri.parse(raw)
			buildString {
				append(uri.scheme ?: "")
				if (!uri.authority.isNullOrBlank()) append("://").append(uri.authority)
			}
		}.getOrDefault("")
	}

	private fun finishGesture(activity: Activity, event: MotionEvent) {
		val gesture = gestures.remove(activity) ?: return
		val deltaX = event.rawX - gesture.x
		val deltaY = event.rawY - gesture.y
		val distance = hypot(deltaX.toDouble(), deltaY.toDouble())
		val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
		val isScroll = distance > touchSlop
		val payload = JSONObject()
			.put("startX", gesture.x.roundToInt())
			.put("startY", gesture.y.roundToInt())
			.put("endX", event.rawX.roundToInt())
			.put("endY", event.rawY.roundToInt())
			.put("durationMillis", event.eventTime - gesture.startedAtMillis)
			.put("component", gesture.target)

		if (isScroll) {
			payload
				.put("deltaX", deltaX.roundToInt())
				.put("deltaY", deltaY.roundToInt())
				.put("axis", if (abs(deltaY) >= abs(deltaX)) "vertical" else "horizontal")
		}
		emit(if (isScroll) "ui.scroll" else "ui.tap", activity, payload)
	}

	private fun targetAt(view: View, rawX: Float, rawY: Float): JSONObject {
		val target = deepestVisibleViewAt(view, rawX, rawY) ?: view
		val resourceName = if (target.id == View.NO_ID) null else runCatching {
			target.resources.getResourceName(target.id)
		}.getOrNull()
		return JSONObject()
			.put("className", target.javaClass.name)
			.put("resourceName", resourceName ?: JSONObject.NULL)
			.put("clickable", target.isClickable)
			.put("longClickable", target.isLongClickable)
			.put("scrollable", target.canScrollVertically(1) || target.canScrollVertically(-1) ||
				target.canScrollHorizontally(1) || target.canScrollHorizontally(-1))
	}

	private fun deepestVisibleViewAt(view: View, rawX: Float, rawY: Float): View? {
		if (!view.isShown) return null
		val location = IntArray(2)
		view.getLocationOnScreen(location)
		if (rawX < location[0] || rawY < location[1] ||
			rawX >= location[0] + view.width || rawY >= location[1] + view.height
		) return null

		if (view is ViewGroup) {
			for (index in view.childCount - 1 downTo 0) {
				deepestVisibleViewAt(view.getChildAt(index), rawX, rawY)?.let { return it }
			}
		}
		return view
	}

	private fun emit(kind: String, activity: Activity?, payload: JSONObject) {
		val event = JSONObject()
			.put("schemaVersion", 1)
			.put("elapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos())
			.put("wallTime", utcWallTime())
			.put("source", "android-agent")
			.put("kind", kind)
			.put("activity", activity?.javaClass?.name ?: JSONObject.NULL)
			.put("payload", payload)
		Log.println(Log.INFO, TAG, event.toString())
	}

	private fun utcWallTime(): String = SimpleDateFormat(
		"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
		Locale.US
	).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

	private data class Gesture(
		val x: Float,
		val y: Float,
		val startedAtMillis: Long,
		val target: JSONObject
	)

	private data class BridgeRegistration(
		val webViewId: String,
		val name: String,
		val methods: List<String>,
	)

	private data class ObservedWebView(
		val id: String,
		val label: String,
		val origin: String,
		val javaScriptEnabled: Boolean,
		val domStorageEnabled: Boolean,
		val allowFileAccess: Boolean,
		val allowContentAccess: Boolean,
		val mixedContentMode: String,
		val safeBrowsingEnabled: Boolean?,
	)
}
