package io.github.flowscopedev.sample

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.flowscope.agent.FlowScopeAgent

/** Login form in XML views: typing, a tap, field validation, and a success state. */
class LoginXmlActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login_xml)
        applySystemBarInsets()
        val email = findViewById<EditText>(R.id.email)
        val password = findViewById<EditText>(R.id.password)
        val error = findViewById<TextView>(R.id.error)
        val welcome = findViewById<TextView>(R.id.welcome)

        findViewById<Button>(R.id.sign_in).setOnClickListener {
            FlowScopeAgent.onSemanticAction(this, "submit", "sign_in", "login-xml")
            val message = LoginValidator.validate(email.text.toString().trim(), password.text.toString())
            welcome.visibility = View.GONE
            if (message != null) {
                error.text = message
                error.visibility = View.VISIBLE
            } else {
                error.visibility = View.GONE
                welcome.text = "Welcome, ${email.text.toString().trim()}"
                welcome.visibility = View.VISIBLE
            }
        }
    }
}
