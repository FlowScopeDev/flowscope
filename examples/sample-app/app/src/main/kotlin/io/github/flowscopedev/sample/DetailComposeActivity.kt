package io.github.flowscopedev.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp

class DetailComposeActivity : ComponentActivity() {
    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val item = Demo.item(intent.getIntExtra(DetailXmlActivity.EXTRA_ID, 0))
        setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp).semantics { testTagsAsResourceId = true }) {
                    Text(item.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("detail_title"))
                    Text(item.body, modifier = Modifier.testTag("detail_body"))
                }
            }
        }
    }
}
