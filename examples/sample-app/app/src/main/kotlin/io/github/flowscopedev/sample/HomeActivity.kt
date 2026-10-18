package io.github.flowscopedev.sample

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import dev.flowscope.agent.FlowScopeAgent

/** The launcher hub: one button per feature screen, in XML views. */
class HomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        applySystemBarInsets()
        val targets = mapOf(
            R.id.open_login_xml to LoginXmlActivity::class.java,
            R.id.open_login_compose to LoginComposeActivity::class.java,
            R.id.open_list_xml to ListXmlActivity::class.java,
            R.id.open_list_compose to ListComposeActivity::class.java,
            R.id.open_web_xml to WebXmlActivity::class.java,
            R.id.open_web_compose to WebComposeActivity::class.java,
            R.id.open_permissions to PermissionsActivity::class.java,
            R.id.open_network to NetworkActivity::class.java,
            R.id.open_referrer to ReferrerActivity::class.java,
        )
        targets.forEach { (id, target) ->
            findViewById<Button>(id).setOnClickListener {
                FlowScopeAgent.onSemanticAction(this, "open", resources.getResourceEntryName(id), "home")
                startActivity(Intent(this, target))
            }
        }
    }
}
