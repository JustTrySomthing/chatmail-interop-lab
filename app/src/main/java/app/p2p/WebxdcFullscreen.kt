package app.p2p

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Owns the WebView-provided fullscreen surface, never the app's ordinary WebView. */
internal class WebxdcFullscreen(private val window: Window) {
    var active by mutableStateOf(false)
        private set
    private var overlay: FrameLayout? = null
    private var callback: WebChromeClient.CustomViewCallback? = null
    private var restoreBars = 0
    private var behavior = 0

    fun show(view: View, result: WebChromeClient.CustomViewCallback, allowed: Boolean) {
        val root = window.decorView as? ViewGroup
        if (!allowed || active || root == null || view.parent != null) {
            result.onCustomViewHidden()
            return
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val insets = ViewCompat.getRootWindowInsets(window.decorView)
        restoreBars = 0
        for (type in listOf(WindowInsetsCompat.Type.statusBars(), WindowInsetsCompat.Type.navigationBars())) {
            if (insets?.isVisible(type) != false) restoreBars = restoreBars or type
        }
        behavior = controller.systemBarsBehavior
        val container = FrameLayout(view.context).apply {
            setBackgroundColor(Color.BLACK)
            addView(view, FrameLayout.LayoutParams(-1, -1))
        }
        overlay = container
        callback = result
        active = true
        root.addView(container, ViewGroup.LayoutParams(-1, -1))
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    fun hide(keepImmersive: Boolean = false) {
        val container = overlay ?: return
        val result = callback
        overlay = null
        callback = null
        active = false
        container.removeAllViews()
        (container.parent as? ViewGroup)?.removeView(container)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (keepImmersive) controller.hide(WindowInsetsCompat.Type.systemBars())
        else if (restoreBars != 0) controller.show(restoreBars)
        controller.systemBarsBehavior = if (keepImmersive)
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE else behavior
        result?.onCustomViewHidden()
    }
}
