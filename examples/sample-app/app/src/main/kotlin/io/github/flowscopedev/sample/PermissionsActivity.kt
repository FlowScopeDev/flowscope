package io.github.flowscopedev.sample

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import dev.flowscope.agent.FlowScopeAgent

/** A runtime permission prompt (camera), for `grant-permission` / `revoke-permission` and the system dialog. */
class PermissionsActivity : AppCompatActivity() {
    private lateinit var status: TextView

    private val request = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions)
        applySystemBarInsets()
        status = findViewById(R.id.permission_status)
        findViewById<Button>(R.id.request_permission).setOnClickListener {
            FlowScopeAgent.onSemanticAction(this, "request", "camera_permission", "permissions")
            request.launch(Manifest.permission.CAMERA)
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        status.text = if (granted) "Camera: granted" else "Camera: not granted"
    }
}
