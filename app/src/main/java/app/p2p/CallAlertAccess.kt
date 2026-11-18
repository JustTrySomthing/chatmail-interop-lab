package app.p2p

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

internal object CallAlertAccess {
    fun allowed(context: Context): Boolean = Build.VERSION.SDK_INT < 34 ||
        context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun settingsIntent(context: Context): Intent =
        Intent(if (Build.VERSION.SDK_INT >= 34) Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT
            else Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
}

// Only the live call UI may cover the lock screen; never dismiss the keyguard.
@Composable
internal fun CallLockScreen(visible: Boolean, incoming: Boolean) {
    val activity = LocalActivity.current
    DisposableEffect(activity, visible, incoming) {
        activity?.setShowWhenLocked(visible)
        activity?.setTurnScreenOn(visible && incoming)
        onDispose {
            activity?.setShowWhenLocked(false)
            activity?.setTurnScreenOn(false)
        }
    }
}
