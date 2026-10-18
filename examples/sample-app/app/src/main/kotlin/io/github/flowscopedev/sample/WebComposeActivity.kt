package io.github.flowscopedev.sample

import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** A WebView hosted inside Compose through AndroidView. */
class WebComposeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AndroidView(
                modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                factory = { context -> WebView(context).also { SampleWeb.configure(it, "sample-compose") } },
            )
        }
    }
}
