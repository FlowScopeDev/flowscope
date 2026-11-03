package dev.flowscope.ide

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Navigation and close are separate hit targets inside one visually grouped tab. */
@Composable
internal fun WorkspaceTab(label: String, detail: String, active: Boolean, closeEnabled: Boolean,
                          onSelect: () -> Unit, onClose: () -> Unit) {
    HoverTip(detail) {
        Box(Modifier.height(36.dp).widthIn(max = 260.dp)
            .background(if (active) Ink200 else Ink100)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = if (active) TextHi else TextMid, fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).height(36.dp)
                        .clickable(role = Role.Tab, onClick = onSelect)
                        .semantics { selected = active; contentDescription = detail }
                        .padding(start = 12.dp, end = 4.dp, top = 9.dp))
                HoverTip("Close $detail") {
                    IconButton(onClick = onClose, enabled = closeEnabled,
                        modifier = Modifier.size(32.dp).semantics { contentDescription = "Close $detail" }) {
                        Text("×", color = TextMid, fontSize = 18.sp)
                    }
                }
            }
            if (active) Box(Modifier.matchParentSize().padding(top = 34.dp).background(SignalIce))
        }
    }
}
