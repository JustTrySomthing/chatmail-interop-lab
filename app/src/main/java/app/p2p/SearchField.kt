package app.p2p

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

internal val RoundedFieldShape = RoundedCornerShape(18.dp)

@Composable
internal fun SearchField(value: String, change: (String) -> Unit, label: String = "Search", submit: () -> Unit = {}) {
    OutlinedTextField(value, change, modifier = Modifier.fillMaxWidth(), singleLine = true,
        shape = RoundedFieldShape, label = { Text(label) },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { change("") }) { Icon(Icons.Outlined.Close, "Clear search") } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { submit() }))
}
