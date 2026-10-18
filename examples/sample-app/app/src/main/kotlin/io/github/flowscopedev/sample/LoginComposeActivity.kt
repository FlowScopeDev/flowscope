package io.github.flowscopedev.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.flowscope.agent.FlowScopeAgent

/**
 * The same login form in Compose. Test tags are exposed as resource ids so selectors such as
 * `id=email` work through UIAutomator in zero-code mode, exactly as they do for the XML screen.
 */
class LoginComposeActivity : ComponentActivity() {
    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var email by remember { mutableStateOf("") }
                var password by remember { mutableStateOf("") }
                var error by remember { mutableStateOf<String?>(null) }
                var welcome by remember { mutableStateOf<String?>(null) }

                Column(
                    Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp).semantics { testTagsAsResourceId = true },
                ) {
                    Text("Sign in (Compose)", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth().testTag("email"),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().testTag("password"),
                    )
                    error?.let {
                        Text(it, color = Color(0xFFB00020), modifier = Modifier.testTag("error"))
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            FlowScopeAgent.onSemanticAction(this@LoginComposeActivity, "submit", "sign_in", "login-compose")
                            val message = LoginValidator.validate(email.trim(), password)
                            error = message
                            welcome = if (message == null) "Welcome, ${email.trim()}" else null
                        },
                        modifier = Modifier.fillMaxWidth().testTag("sign_in"),
                    ) { Text("Sign in") }
                    welcome?.let {
                        Spacer(Modifier.height(16.dp))
                        Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("welcome"))
                    }
                }
            }
        }
    }
}
