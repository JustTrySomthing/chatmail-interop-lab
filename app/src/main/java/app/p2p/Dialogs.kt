package app.p2p

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

internal val P2pDialogShape = RoundedCornerShape(20.dp)

internal data class ItemAction(val label: String, val icon: ImageVector, val run: () -> Unit)

@Composable
internal fun ItemActionsDialog(title: String, actions: List<ItemAction>, dismiss: () -> Unit) =
    P2pAlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        Column {
            actions.forEach { action ->
                P2pTextButton(onClick = action.run, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(action.icon, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(action.label, style = contentTextStyle(MaterialTheme.typography.bodyLarge))
                    }
                }
            }
        }
    }, confirmButton = { P2pTextButton(onClick = dismiss) { Text("Close") } })

@Composable
internal fun P2pAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties()
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = title,
        icon = icon,
        shape = P2pDialogShape,
        text = { Box(Modifier.verticalScroll(rememberScrollState())) { text() } },
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        containerColor = MaterialTheme.colorScheme.surface,
        properties = properties
    )
}
