package io.github.flowscopedev.sample

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class DetailXmlActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail_xml)
        applySystemBarInsets()
        val item = Demo.item(intent.getIntExtra(EXTRA_ID, 0))
        findViewById<TextView>(R.id.detail_title).text = item.title
        findViewById<TextView>(R.id.detail_body).text = item.body
    }

    companion object {
        const val EXTRA_ID = "item_id"
    }
}
