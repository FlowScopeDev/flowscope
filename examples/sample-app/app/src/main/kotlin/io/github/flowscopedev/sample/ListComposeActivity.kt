package io.github.flowscopedev.sample

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import dev.flowscope.agent.FlowScopeAgent

/** The same list as a LazyColumn: only visible rows exist, so scrolling is part of every test. */
class ListComposeActivity : ComponentActivity() {
    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().semantics { testTagsAsResourceId = true }.testTag("item_list")) {
                    items(Demo.items, key = { it.id }) { item ->
                        Column(
                            Modifier.fillMaxWidth()
                                .semantics { contentDescription = "Open ${item.title}" }
                                .testTag("item_${item.id}")
                                .clickable {
                                    FlowScopeAgent.onSemanticAction(
                                        this@ListComposeActivity, "select", "item_${item.id}", "list-compose",
                                    )
                                    startActivity(
                                        Intent(this@ListComposeActivity, DetailComposeActivity::class.java)
                                            .putExtra(DetailXmlActivity.EXTRA_ID, item.id),
                                    )
                                }
                                .padding(16.dp),
                        ) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium)
                            Text(item.body)
                        }
                    }
                }
            }
        }
    }
}
