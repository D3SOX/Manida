package me.d3sox.nevumi

import android.app.Instrumentation
import android.os.ParcelFileDescriptor

internal fun Instrumentation.awaitMediaStoreIdle() {
    // Data-clear broadcasts and file publishing both update MediaStore asynchronously.
    for ((command, completed) in listOf(
        "am wait-for-broadcast-idle" to "All broadcast queues are idle!",
        "content call --uri content://media --method wait_for_idle" to "Result:"
    )) {
        val output = ParcelFileDescriptor.AutoCloseInputStream(
            uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        check(completed in output) { output }
    }
}
