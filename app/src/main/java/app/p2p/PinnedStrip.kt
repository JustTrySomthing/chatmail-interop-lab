package app.p2p

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal data class PinnedItem(val id: Int, val preview: String)

@Composable
internal fun PinnedStrip(pins: List<PinnedItem>, onSelect: (Int) -> Unit) {
    if (pins.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    BackHandler(expanded) { expanded = false }
    val colors = MaterialTheme.colorScheme
    val stripColor = colors.primary.copy(alpha = 0.18f).compositeOver(colors.surface)
    val stripInk = inkOnAccent(stripColor)
    val listHeight = (LocalConfiguration.current.screenHeightDp / 3).coerceIn(72, 192).dp
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = stripColor,
        contentColor = stripInk,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                    .clickable(role = Role.Button, onClick = { expanded = !expanded })
                    .heightIn(min = 44.dp).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.PushPin, null, Modifier.size(18.dp))
                Text("Pinned", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Surface(shape = CircleShape, color = colors.primary, contentColor = colors.onPrimary) {
                    Box(Modifier.heightIn(min = 22.dp).widthIn(min = 22.dp).padding(horizontal = 7.dp),
                        contentAlignment = Alignment.Center) {
                        Text(pins.size.toString(), style = MaterialTheme.typography.labelMedium)
                    }
                }
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            if (expanded) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = listHeight)
                    .semantics { contentDescription = "Pinned items" }) {
                    items(pins, key = { it.id }) { pin ->
                        HorizontalDivider(color = stripInk.copy(alpha = 0.18f))
                        Text(pin.preview,
                            Modifier.fillMaxWidth().clickable(role = Role.Button) {
                                expanded = false
                                onSelect(pin.id)
                            }.padding(horizontal = 14.dp, vertical = 12.dp),
                            style = contentTextStyle(MaterialTheme.typography.bodyMedium),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
