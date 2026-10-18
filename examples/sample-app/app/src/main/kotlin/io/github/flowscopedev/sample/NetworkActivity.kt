package io.github.flowscopedev.sample

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import dev.flowscope.agent.FlowScopeAgent
import java.util.UUID

/**
 * A simulated network call and background job. They need no server: the point is the agent hooks.
 * Routes are templates (`/v1/profile/{id}`) and work names are stable logical names, never data.
 */
class NetworkActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var network by remember { mutableStateOf("Network: idle") }
                var work by remember { mutableStateOf("Work: idle") }
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp).semantics { testTagsAsResourceId = true }) {
                    Button(
                        onClick = { fetchProfile { network = it } },
                        modifier = Modifier.fillMaxWidth().testTag("fetch_profile"),
                    ) { Text("Fetch profile") }
                    Text(network, modifier = Modifier.testTag("network_status"))
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { runSync { work = it } },
                        modifier = Modifier.fillMaxWidth().testTag("run_sync"),
                    ) { Text("Run background sync") }
                    Text(work, modifier = Modifier.testTag("work_status"))
                }
            }
        }
    }

    private fun fetchProfile(render: (String) -> Unit) {
        val requestId = UUID.randomUUID().toString()
        val started = System.nanoTime()
        FlowScopeAgent.onNetwork(requestId, "request", "GET", "/v1/profile/{id}")
        render("Network: loading")
        main.postDelayed({
            val millis = (System.nanoTime() - started) / 1_000_000
            FlowScopeAgent.onNetwork(requestId, "response", "GET", "/v1/profile/{id}", 200, millis)
            render("Network: 200 OK")
        }, 800)
    }

    private fun runSync(render: (String) -> Unit) {
        FlowScopeAgent.onBackgroundWork("profile-sync", "started")
        render("Work: running")
        main.postDelayed({
            FlowScopeAgent.onBackgroundWork("profile-sync", "finished")
            render("Work: finished")
        }, 1200)
    }
}
