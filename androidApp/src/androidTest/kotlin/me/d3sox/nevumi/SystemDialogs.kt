package me.d3sox.nevumi

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertFalse

/**
 * Slow emulators sometimes report a system app, such as the launcher, as not responding. That dialog covers the
 * screen under test until someone answers it, so keep waiting for the app. Nevumi's own dialog fails the test instead.
 * Returns whether a dialog was answered.
 */
internal fun waitOnNotRespondingDialog(root: AccessibilityNodeInfo): Boolean {
    val title = root.findAccessibilityNodeInfosByViewId("android:id/alertTitle").firstOrNull { it.isVisibleToUser }?.text?.toString()
    assertFalse("Nevumi stopped responding: $title", title?.contains("Nevumi") == true)
    return root.findAccessibilityNodeInfosByViewId("android:id/aerr_wait").firstOrNull { it.isVisibleToUser }
        ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
}
