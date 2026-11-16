package app.p2p

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Calendar

internal data class QuietHours(
    val enabled: Boolean = false,
    val start: Int = 22 * 60,
    val end: Int = 8 * 60,
    val days: Int = 127
) {
    fun active(now: Calendar = Calendar.getInstance()): Boolean {
        if (!enabled || start == end) return false
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val day = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val overnight = start > end
        val startDay = if (overnight && minute < end) (day + 6) % 7 else day
        val withinHours = if (overnight) minute >= start || minute < end else minute in start until end
        return withinHours && days and (1 shl startDay) != 0
    }

    fun save(context: Context) {
        prefs(context).edit().putBoolean("enabled", enabled)
            .putInt("start", start).putInt("end", end).putInt("days", days).apply()
    }

    companion object {
        private fun prefs(context: Context) = context.getSharedPreferences("quiet_hours", Context.MODE_PRIVATE)

        fun read(context: Context): QuietHours = prefs(context).let {
            QuietHours(it.getBoolean("enabled", false), it.getInt("start", 1320).coerceIn(0, 1439),
                it.getInt("end", 480).coerceIn(0, 1439), it.getInt("days", 127) and 127)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun QuietHoursControls(current: QuietHours, cancel: () -> Unit, save: (QuietHours) -> Unit) {
    val context = LocalContext.current
    var enabled by rememberSaveable { mutableStateOf(current.enabled) }
    var start by rememberSaveable { mutableIntStateOf(current.start) }
    var end by rememberSaveable { mutableIntStateOf(current.end) }
    var days by rememberSaveable { mutableIntStateOf(current.days) }
    var pickingStart by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val schedule = QuietHours(enabled, start, end, days)
    val clock by produceState(Calendar.getInstance()) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            value = Calendar.getInstance()
        }
    }
    val dayNames = remember { java.text.DateFormatSymbols.getInstance().weekdays.let { names ->
        (Calendar.MONDAY..Calendar.SATURDAY).map { names[it] } + names[Calendar.SUNDAY]
    } }
    fun time(minute: Int): String = DateFormat.getTimeFormat(context).format(Calendar.getInstance().apply {
        set(2000, Calendar.JANUARY, 15, minute / 60, minute % 60, 0)
    }.time)
    pickingStart?.let { from ->
        val minute = if (from) start else end
        val time = rememberTimePickerState(minute / 60, minute % 60, DateFormat.is24HourFormat(context))
        P2pAlertDialog(onDismissRequest = { pickingStart = null },
            title = { Text(if (from) "Start time" else "End time") },
            text = { TimeInput(state = time) },
            confirmButton = { Button(shape = P2pButtonShape, onClick = {
                val selected = time.hour * 60 + time.minute
                if (from) start = selected else end = selected
                pickingStart = null
            }) { Text("Set time") } },
            dismissButton = { P2pTextButton(onClick = { pickingStart = null }) { Text("Cancel") } })
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Enable schedule", Modifier.weight(1f))
        Switch(enabled, { enabled = it }, modifier = Modifier.semantics { contentDescription = "Enable schedule" })
    }
    Text(when {
        !enabled -> "Off"
        schedule == current && schedule.active(clock) -> "Quiet now"
        else -> "Schedule ready"
    }, color = MaterialTheme.colorScheme.primary)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ pickingStart = true }, Modifier.weight(1f), enabled = enabled, shape = P2pButtonShape,
            contentPadding = PaddingValues(horizontal = 8.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("From")
                Text(time(start), style = MaterialTheme.typography.labelMedium)
            }
        }
        OutlinedButton({ pickingStart = false }, Modifier.weight(1f), enabled = enabled, shape = P2pButtonShape,
            contentPadding = PaddingValues(horizontal = 8.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Until")
                Text(time(end), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    Text("Repeat", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        dayNames.forEachIndexed { index, day ->
            val bit = 1 shl index
            FilterChip(selected = days and bit != 0, onClick = { days = days xor bit },
                enabled = enabled, label = { Text(day) })
        }
    }
    Text("P2P notifications arrive silently during this schedule.", style = MaterialTheme.typography.bodySmall)
    val error = when {
        enabled && days == 0 -> "Choose at least one day."
        enabled && start == end -> "Choose different start and end times."
        else -> null
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        P2pTextButton(onClick = cancel) { Text("Cancel") }
        Button(onClick = { save(schedule) }, shape = P2pButtonShape, enabled = error == null) { Text("Save") }
    }
}
