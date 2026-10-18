package io.github.flowscopedev.sample

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Demo credentials and list data shared by the XML and Compose screens, so both behave identically. */
object Demo {
    const val EMAIL = "user@example.com"
    const val PASSWORD = "correct-horse"

    data class Item(val id: Int, val title: String, val body: String)

    val items: List<Item> = (1..60).map {
        Item(it, "Item $it", "This is the detail text for item $it.")
    }

    fun item(id: Int): Item = items.firstOrNull { it.id == id } ?: Item(id, "Item $id", "Unknown item.")
}

/** One validator for both login screens: the error strings are what the flows assert on. */
object LoginValidator {
    const val EMAIL_ERROR = "Enter a valid email address"
    const val PASSWORD_ERROR = "Password must be at least 6 characters"
    const val CREDENTIAL_ERROR = "Incorrect email or password"

    /** Returns the message to show, or null when the credentials are accepted. */
    fun validate(email: String, password: String): String? = when {
        !email.contains('@') || email.startsWith("@") || email.endsWith("@") -> EMAIL_ERROR
        password.length < 6 -> PASSWORD_ERROR
        email != Demo.EMAIL || password != Demo.PASSWORD -> CREDENTIAL_ERROR
        else -> null
    }
}

/**
 * targetSdk 35 draws apps edge-to-edge, so content starts under the status bar. XML screens call this
 * after `setContentView` to keep their content inside the system bars, as a production app would.
 */
fun Activity.applySystemBarInsets() {
    val content = findViewById<View>(android.R.id.content)
    ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        WindowInsetsCompat.CONSUMED
    }
}
