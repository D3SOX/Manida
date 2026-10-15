package me.d3sox.manida

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Slow emulators sometimes report a system app, such as the launcher, as not responding. That dialog covers the
 * screen under test until someone answers it, so keep waiting for the app. Returns whether a dialog was answered.
 */
internal fun waitOnNotRespondingDialog(visible: List<AccessibilityNodeInfo>): Boolean =
    visible.firstOrNull { it.viewIdResourceName == "android:id/aerr_wait" }
        ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
