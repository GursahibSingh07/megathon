package com.geniex.demo.autofill

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ONLY reads form_actions.json and fills the form on screen. Prints nothing about the
 * layout (that's NodeScannerService). Logcat tag: "VoiceFill".
 *
 * JSON lives at: /data/data/com.example.nodescannertest/files/form_actions.json
 * Format:
 *   [ {"action": "type",  "target_id": 8, "value": "Kanishka"},
 *     {"action": "click", "target_id": 9} ]
 * target_id = the [ID: n] numbers from the NodeScanner log for this same form.
 */
class VoiceFillAccessibilityService : AccessibilityService() {

    private val TAG = "VoiceFill"
    private val ACTIONS_FILE = "form_actions.json"
    private val DONE_FILE = "form_actions.done.json"   // renamed after a run, so it never runs twice
    private val STEP_DELAY_MS = 500L                    // raise if fields get skipped

    private data class UiAction(val action: String, val targetId: Int, val value: String?)

    private val scanner = ScreenScanner()
    private val handler = Handler(Looper.getMainLooper())
    private var isFilling = false
    private var lastSkipReason = ""

    override fun onServiceConnected() {
        Log.d(TAG, "Service connected. Waiting for ${File(filesDir, ACTIONS_FILE).absolutePath}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Our own clicks/typing fire events; rescanning mid-run would renumber the IDs
        if (isFilling) return

        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            type != AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) scanner.clearHints()

        // Cheap check first: no JSON -> nothing to do, don't even scan
        val file = File(filesDir, ACTIONS_FILE)
        if (!file.exists()) return

        val root = rootInActiveWindow ?: return
        val screen = try {
            scanner.scan(root)
        } catch (e: Exception) {
            Log.e(TAG, "Scan failed, will retry on next screen update", e)
            return
        }

        tryFill(file, screen)
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    // ==========================================
    // JSON -> FORM
    // ==========================================

    private fun tryFill(file: File, screen: ScanResult) {
        val actions = try {
            parseActions(file.readText())
        } catch (e: Exception) {
            logSkipOnce("Could not parse $ACTIONS_FILE: ${e.message}")
            return
        }
        if (actions.isEmpty()) {
            logSkipOnce("$ACTIONS_FILE has no actions")
            return
        }

        // Check EVERY action against the current screen before touching anything.
        // Wrong screen (e.g. home screen) -> IDs won't line up -> do nothing.
        val problems = actions.mapNotNull { a ->
            val kind = screen.kinds[a.targetId]
            when {
                a.action != "type" && a.action != "click" -> "unknown action '${a.action}'"
                kind == null -> "ID ${a.targetId} is not on this screen"
                a.action == "type" && kind != NodeKind.INPUT -> "ID ${a.targetId} is not an input field"
                a.action == "click" && kind != NodeKind.OPTION -> "ID ${a.targetId} is not a selectable option"
                else -> null
            }
        }
        if (problems.isNotEmpty()) {
            logSkipOnce("Not filling, this screen doesn't match the JSON: ${problems.joinToString("; ")}")
            return
        }

        val plan = actions.map { it to screen.nodes.getValue(it.targetId) }

        // Mark used BEFORE running, so a restart can't run it twice (would un-tick checkboxes)
        file.renameTo(File(filesDir, DONE_FILE))

        Log.d(TAG, "Screen matches JSON, running ${plan.size} action(s)")
        lastSkipReason = ""
        isFilling = true
        runStep(plan, 0)
    }

    private fun runStep(plan: List<Pair<UiAction, AccessibilityNodeInfo>>, index: Int) {
        if (index >= plan.size) {
            isFilling = false
            Log.d(TAG, "Autofill finished (${plan.size} action(s))")
            return
        }

        val (action, node) = plan[index]
        try {
            node.refresh()
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)

            when (action.action) {
                "type" -> {
                    val ok = typeInto(node, action.value ?: "")
                    Log.d(TAG, "Step ${index + 1}: typed \"${action.value}\" into ID ${action.targetId} (success=$ok)")
                }
                "click" -> {
                    if (node.isChecked) {
                        Log.d(TAG, "Step ${index + 1}: ID ${action.targetId} already selected, skipping")
                    } else {
                        val ok = clickNode(node)
                        Log.d(TAG, "Step ${index + 1}: clicked ID ${action.targetId} (success=$ok)")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Step ${index + 1} (ID ${action.targetId}) failed", e)
        }

        handler.postDelayed({ runStep(plan, index + 1) }, STEP_DELAY_MS)
    }

    // Accepts a top-level array, or {"actions": [...]}
    private fun parseActions(text: String): List<UiAction> {
        val trimmed = text.trim()
        val array = if (trimmed.startsWith("[")) JSONArray(trimmed)
        else JSONObject(trimmed).optJSONArray("actions") ?: JSONArray()

        val result = mutableListOf<UiAction>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = when {
                item.has("target_id") -> item.optInt("target_id", -1)
                item.has("id") -> item.optInt("id", -1)
                else -> -1
            }
            if (id < 0) continue
            result.add(
                UiAction(
                    action = item.optString("action").trim().lowercase(),
                    targetId = id,
                    value = if (item.has("value")) item.optString("value") else null
                )
            )
        }
        return result
    }

    private fun logSkipOnce(reason: String) {
        if (reason == lastSkipReason) return
        lastSkipReason = reason
        Log.d(TAG, reason)
    }

    // ==========================================
    // LOW-LEVEL ACTIONS
    // ==========================================

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        // Try the node, then its parents
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 5) {
            if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
            depth++
        }

        // Fallback: tap its center on screen (needs canPerformGestures="true")
        Log.w(TAG, "performAction click failed, trying coordinate tap")
        return tapNodeCenter(node)
    }

    private fun typeInto(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK) // web inputs often need a tap to focus

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true

        // Fallback: paste from clipboard
        Log.w(TAG, "SET_TEXT failed, trying clipboard paste")
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("voicefill", text))
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun tapNodeCenter(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false

        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        val ok = dispatchGesture(gesture, null, null)
        if (!ok) Log.e(TAG, "dispatchGesture rejected (is canPerformGestures enabled?)")
        return ok
    }
}