package io.github.sayach.dshmobile

import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets

/** Enables edge-to-edge rendering while retaining explicit safe-area ownership. */
@Suppress("DEPRECATION")
internal fun configureEdgeToEdgeWindow(window: Window) {
    window.statusBarColor = Color.TRANSPARENT
    window.navigationBarColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.setDecorFitsSystemWindows(false)
    } else {
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
}

/**
 * Keeps a root view clear of system bars, display cutouts, and the on-screen keyboard.
 *
 * @param root the setup or browser root whose original padding must be preserved.
 */
internal fun applySafeAreaInsets(root: View) {
    val contentPadding = ContentPadding(
        left = root.paddingLeft,
        top = root.paddingTop,
        right = root.paddingRight,
        bottom = root.paddingBottom,
    )
    root.setOnApplyWindowInsetsListener { view, insets ->
        val padding = contentPadding.withSafeArea(resolveSafeArea(insets))
        if (
            view.paddingLeft != padding.left ||
            view.paddingTop != padding.top ||
            view.paddingRight != padding.right ||
            view.paddingBottom != padding.bottom
        ) {
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
        }
        consumeSafeAreaInsets(insets)
    }
    root.requestApplyInsets()
}

@Suppress("DEPRECATION")
private fun resolveSafeArea(insets: WindowInsets): SafeAreaEdges {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val systemBars = insets.getInsets(WindowInsets.Type.systemBars()).toSafeAreaEdges()
        val displayCutout = insets.getInsets(WindowInsets.Type.displayCutout()).toSafeAreaEdges()
        val ime = insets.getInsets(WindowInsets.Type.ime()).toSafeAreaEdges()
        return systemBars.union(displayCutout).union(ime)
    }

    val displayCutout =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) insets.displayCutout else null
    return SafeAreaEdges(
        left = insets.systemWindowInsetLeft,
        top = insets.systemWindowInsetTop,
        right = insets.systemWindowInsetRight,
        bottom = insets.systemWindowInsetBottom,
    ).union(
        SafeAreaEdges(
            left = displayCutout?.safeInsetLeft ?: 0,
            top = displayCutout?.safeInsetTop ?: 0,
            right = displayCutout?.safeInsetRight ?: 0,
            bottom = displayCutout?.safeInsetBottom ?: 0,
        ),
    )
}

private fun android.graphics.Insets.toSafeAreaEdges(): SafeAreaEdges = SafeAreaEdges(
    left = left,
    top = top,
    right = right,
    bottom = bottom,
)

@Suppress("DEPRECATION")
private fun consumeSafeAreaInsets(insets: WindowInsets): WindowInsets =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        WindowInsets.CONSUMED
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        insets.consumeSystemWindowInsets().consumeStableInsets().consumeDisplayCutout()
    } else {
        insets.consumeSystemWindowInsets().consumeStableInsets()
    }
