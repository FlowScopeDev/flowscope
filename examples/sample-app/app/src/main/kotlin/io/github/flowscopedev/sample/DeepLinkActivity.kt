package io.github.flowscopedev.sample

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.flowscope.agent.FlowScopeAgent

/** Handles `flowscope-sample://item/<id>` and `https://sample.flowscope.dev/item/<id>`. */
class DeepLinkActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_deep_link)
        applySystemBarInsets()
        val uri = intent?.data
        val id = uri?.lastPathSegment?.toIntOrNull()
        findViewById<TextView>(R.id.deep_link_target).text =
            if (id != null) "Deep link opened: item $id" else "Deep link opened: unknown target"
        findViewById<TextView>(R.id.deep_link_scheme).text = "scheme: ${uri?.scheme.orEmpty()}"
        FlowScopeAgent.onSemanticAction(this, "deep-link", "item", "deep-link")
    }
}
