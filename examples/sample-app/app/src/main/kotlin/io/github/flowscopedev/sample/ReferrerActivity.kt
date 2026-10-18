package io.github.flowscopedev.sample

import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.flowscope.agent.FlowScopeAgent

/**
 * Shows how an app opts into the install-referrer hook: consult the FlowScope override first, then
 * fall back to the real Play Install Referrer client (omitted here). The release twin returns null.
 */
class ReferrerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_referrer)
        applySystemBarInsets()
        val referrer = FlowScopeAgent.installReferrerOverride(this)
        val params = referrer?.let { Uri.parse("https://referrer.invalid/?$it") }
        findViewById<TextView>(R.id.referrer_source).text =
            "Source: ${params?.getQueryParameter("utm_source") ?: "organic"}"
        findViewById<TextView>(R.id.referrer_campaign).text =
            "Campaign: ${params?.getQueryParameter("utm_campaign") ?: "none"}"
    }
}
