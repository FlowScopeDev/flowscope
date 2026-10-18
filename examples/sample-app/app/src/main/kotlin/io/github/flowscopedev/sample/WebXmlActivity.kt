package io.github.flowscopedev.sample

import android.os.Bundle
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity

class WebXmlActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_xml)
        applySystemBarInsets()
        SampleWeb.configure(findViewById<WebView>(R.id.web), "sample-xml")
    }
}
