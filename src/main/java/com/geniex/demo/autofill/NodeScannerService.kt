package com.geniex.demo.autofill

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * ONLY reads the screen and prints the numbered layout to Logcat (tag "LlamaContext").
 * It never clicks or types anything. Filling is VoiceFillAccessibilityService's job.
 */
class NodeScannerService : AccessibilityService() {

    private val scanner = ScreenScanner()
    private var lastLayout = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) scanner.clearHints()

        val root = rootInActiveWindow ?: return
        val result = try {
            scanner.scan(root)
        } catch (e: Exception) {
            Log.e("NodeScanner", "Scan failed, skipping this screen update", e)
            return
        }

        if (result.lines.isEmpty()) return
        val layout = result.lines.joinToString("\n")
        if (layout == lastLayout) return   // don't spam the same layout again and again
        lastLayout = layout

        Log.d("LlamaContext", "\n--- SCREEN LAYOUT ---\n$layout\n---------------------")
    }

    override fun onInterrupt() {
        Log.d("NodeScanner", "Service Interrupted")
    }
}