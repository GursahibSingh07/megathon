package com.example.qidk_hack2026.form

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Reads the form on screen and fills it in on request.
 *
 * Screen-reading (node classification, labels for radios/checkboxes, Chrome roles) is adapted from
 * the NodeScannerTest prototype. Unlike the prototype it does not watch every window change or read
 * a JSON file: [FormPipeline] asks for a [scan] when the user has spoken, then calls [execute].
 *
 * Needs minSdk 26 (hintText / isShowingHintText).
 */
class FormAccessibilityService : AccessibilityService() {

    // Per-scan state. Ids are indexes into [nodes] and are only valid until the next scan.
    private val nodes = mutableMapOf<Int, AccessibilityNodeInfo>()
    private val kinds = mutableMapOf<Int, FieldKind>()
    private val fields = mutableListOf<ScreenField>()
    // (field id, or null for plain labels) to a line of text; lets us drop lines of fields tidy() removes
    private val layout = mutableListOf<Pair<Int?, String>>()
    private val choiceIds = mutableSetOf<Int>()
    private val consumed = mutableSetOf<AccessibilityNodeInfo>()
    private val hintCache = mutableMapOf<String, String>()
    private var nextId = 0
    private var lastTextSeen: String? = null
    private var lastQuestion = ""

    private var bubble: BubbleOverlay? = null

    override fun onServiceConnected() {
        instance = this
        bubble?.destroy()
        bubble = BubbleOverlay(this)
        Log.d(TAG, "Form service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        bubble?.destroy(); bubble = null
        return super.onUnbind(intent)
    }

    // Scanning is on demand, so there is nothing to do per event.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    // ==========================================
    // PUBLIC API
    // ==========================================

    /** Reads the form currently in front. Null when there is no readable window. */
    @Synchronized
    fun scan(): ScreenSnapshot? {
        val root = formRoot() ?: return null
        nodes.clear(); kinds.clear(); fields.clear(); layout.clear(); consumed.clear(); choiceIds.clear()
        nextId = 0
        lastTextSeen = null
        lastQuestion = ""
        try {
            // In a browser, only the page counts; the address bar, tab button and menu are not the form.
            traverse(findWebView(root) ?: root)
        } catch (e: Exception) {
            // The page can change mid-scan; report "nothing readable" rather than half a form.
            Log.e(TAG, "Scan failed", e)
            return null
        }
        val keep = ScreenTidy.tidy(fields, choiceIds).map { it.id }.toSet()
        nodes.keys.retainAll(keep)
        kinds.keys.retainAll(keep)
        return ScreenSnapshot(
            fields.filter { it.id in keep }.map { if (it.id in choiceIds) it.copy(choice = true) else it },
            layout.filter { it.first == null || it.first in keep }.joinToString("\n") { it.second },
        )
    }

    /** Taps the first empty text field (or any text field) so the keyboard comes up for the user. */
    suspend fun raiseKeyboard(): Boolean = withContext(Dispatchers.Main) {
        val root = formRoot() ?: return@withContext false
        val edits = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40) return
            if (n.isEditable && n.isVisibleToUser) edits += n
            for (i in 0 until n.childCount) walk(childAt(n, i), depth + 1)
        }
        walk(findWebView(root) ?: root, 0)
        val target = edits.firstOrNull { it.text.isNullOrBlank() || it.isShowingHintText } ?: edits.firstOrNull()
            ?: return@withContext false
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findWebView(n: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (n == null || depth > 30) return null
        if (n.className?.toString() == "android.webkit.WebView" && n.childCount > 0) return n
        for (i in 0 until n.childCount) findWebView(childAt(n, i), depth + 1)?.let { return it }
        return null
    }

    /** Runs [actions] against the nodes from the last [scan], pausing between steps so the page can react. */
    suspend fun execute(actions: List<FormAction>): ExecReport {
        val failed = mutableListOf<Int>()
        var ok = 0
        for (action in actions) {
            val node = nodes[action.targetId]
            val kind = kinds[action.targetId]
            val valid = node != null && when (action) {
                is FormAction.Type -> kind == FieldKind.INPUT
                is FormAction.Click -> kind == FieldKind.OPTION
            }
            if (!valid) {
                failed += action.targetId
                continue
            }
            val done = withContext(Dispatchers.Main) {
                try {
                    node!!.refresh()
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                    when (action) {
                        is FormAction.Type -> typeInto(node, action.text)
                        // Clicking a ticked checkbox would un-tick it.
                        is FormAction.Click -> node.isChecked || clickNode(node)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Action on ID ${action.targetId} failed", e)
                    false
                }
            }
            if (done) ok++ else failed += action.targetId
            delay(STEP_DELAY_MS)
        }
        return ExecReport(actions.size, ok, failed)
    }

    // ==========================================
    // WHICH WINDOW
    // ==========================================

    // The form is another app's window, not the keyboard panel or our own screens; fall back to our
    // own window so the in-app test field still works.
    private fun formRoot(): AccessibilityNodeInfo? {
        val appWindows = windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }
            .mapNotNull { it.root }
        appWindows.firstOrNull { it.packageName != packageName }?.let { return it }
        rootInActiveWindow?.takeIf { it.packageName != packageName }?.let { return it }
        return appWindows.firstOrNull() ?: rootInActiveWindow
    }

    // ==========================================
    // CLASSIFICATION HELPERS (from NodeScannerTest)
    // ==========================================

    private fun cleanTextOf(n: AccessibilityNodeInfo?): String? {
        if (n == null) return null
        val raw = n.text?.toString() ?: n.contentDescription?.toString() ?: return null
        val c = raw.replace(Regex("[^a-zA-Z0-9 ?]"), "").trim()
        return if (c.length > 1) c else null
    }

    private fun chromeRole(n: AccessibilityNodeInfo): String =
        n.extras?.getString(CHROME_ROLE_KEY)?.lowercase() ?: ""

    // Radio / checkbox / switch, native or web
    private fun isChoiceControl(n: AccessibilityNodeInfo?): Boolean {
        if (n == null) return false
        if (n.isCheckable) return true
        val cls = n.className?.toString() ?: ""
        if (cls.contains("RadioButton") || cls.contains("CheckBox") || cls.contains("Switch")) return true
        return chromeRole(n) in CHOICE_ROLES
    }

    // Something the user can actually press
    private fun isActionable(n: AccessibilityNodeInfo?): Boolean {
        if (n == null) return false
        if (isChoiceControl(n)) return true

        val role = chromeRole(n)
        if (role in INTERACTIVE_ROLES) return true

        val cls = n.className?.toString() ?: ""
        if (cls.contains("Button")) return true

        val hasClickAction = n.isClickable ||
            n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
        if (!hasClickAction) return false

        // Native views: trust the click flag. Web nodes with a generic role: don't.
        return role.isEmpty()
    }

    private fun countChoiceControls(n: AccessibilityNodeInfo?, stopAt: Int = 2, depth: Int = 0): Int {
        if (n == null || depth > 10) return 0
        var c = if (isChoiceControl(n)) 1 else 0
        for (i in 0 until n.childCount) {
            if (c >= stopAt) break
            c += countChoiceControls(childAt(n, i), stopAt, depth + 1)
        }
        return c
    }

    private fun findFirstChoiceControl(n: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (n == null || depth > 10) return null
        if (isChoiceControl(n)) return n
        for (i in 0 until n.childCount) {
            findFirstChoiceControl(childAt(n, i), depth + 1)?.let { return it }
        }
        return null
    }

    private fun textsIn(n: AccessibilityNodeInfo?, out: MutableSet<String>, limit: Int, depth: Int = 0) {
        if (n == null || depth > 10 || out.size >= limit) return
        cleanTextOf(n)?.let { out.add(it) }
        for (i in 0 until n.childCount) textsIn(childAt(n, i), out, limit, depth + 1)
    }

    private fun firstTextIn(n: AccessibilityNodeInfo?): String? {
        val s = linkedSetOf<String>()
        textsIn(n, s, 1)
        return s.firstOrNull()
    }

    // getChild() throws (instead of returning null) when the index is out of range
    private fun childAt(parent: AccessibilityNodeInfo, i: Int): AccessibilityNodeInfo? {
        if (i < 0 || i >= parent.childCount) return null
        return try {
            parent.getChild(i)
        } catch (e: IndexOutOfBoundsException) {
            null
        }
    }

    private fun indexInParent(parent: AccessibilityNodeInfo, child: AccessibilityNodeInfo): Int {
        for (i in 0 until parent.childCount) if (childAt(parent, i) == child) return i
        return -1
    }

    // ==========================================
    // TRAVERSAL
    // ==========================================

    private fun traverse(node: AccessibilityNodeInfo?) {
        if (node == null) return
        if (node in consumed) return // already listed as part of an option

        val cleanText = cleanTextOf(node)

        if (node.isEditable || node.className?.contains("EditText") == true) {
            // 1. Input field
            val id = nextId++
            nodes[id] = node
            kinds[id] = FieldKind.INPUT

            val key = node.viewIdResourceName ?: node.hashCode().toString()
            val hint = node.hintText?.toString()
                ?: if (node.isShowingHintText) node.text?.toString() else null
            if (!hint.isNullOrBlank()) hintCache[key] = hint

            val current = if (node.isShowingHintText) "" else node.text?.toString() ?: ""
            val label = hintCache[key]
                ?: node.contentDescription?.toString()
                ?: lastTextSeen
                ?: ""

            fields += ScreenField(id, FieldKind.INPUT, label.trim(), current = current)
            layout += id to "[ID: $id] INPUT_FIELD hint=\"$label\" current=\"$current\""
        } else if (isChoiceControl(node)) {
            // 2. Radio / checkbox / switch: list it with the text that belongs to it
            val label = cleanText
                ?: firstTextIn(node)
                ?: findLabelFor(node)?.let { (labelNode, text) ->
                    consumed.add(labelNode)
                    text
                }
                ?: "(unlabeled)"
            addOption(node, label)
            lastTextSeen = label
            return // its children are part of the control
        } else if (cleanText != null) {
            // 3. Plain text: a button, the label of a nearby radio, or just a label
            val target = when {
                isActionable(node) -> node
                else -> findChoiceControlNear(node)?.also { consumed.add(it) }
                    ?: findOwningClickableAncestor(node)
            }

            if (target != null) {
                addOption(target, cleanText)
            } else if (cleanText.length > 2) {
                layout += null to "LABEL: $cleanText"
                lastQuestion = cleanText
            }
            lastTextSeen = cleanText
        }

        for (i in 0 until node.childCount) traverse(childAt(node, i))
    }

    private fun addOption(target: AccessibilityNodeInfo, label: String) {
        if (nodes.containsValue(target)) return
        val id = nextId++
        nodes[id] = target
        kinds[id] = FieldKind.OPTION
        if (isChoiceControl(target)) choiceIds += id
        fields += ScreenField(id, FieldKind.OPTION, label, group = lastQuestion, selected = target.isChecked)
        layout += id to "[ID: $id] SELECTABLE_OPTION: $label${if (target.isChecked) " (SELECTED)" else ""}"
    }

    private fun findLabelFor(control: AccessibilityNodeInfo): Pair<AccessibilityNodeInfo, String>? {
        var child = control
        var depth = 0
        while (depth < 3) {
            val parent = child.parent ?: return null

            if (countChoiceControls(parent) > 1) {
                // Reached the option group. Flat layout: [radio, text, radio, text]
                val idx = indexInParent(parent, child)
                val next = childAt(parent, idx + 1)
                if (next != null && countChoiceControls(next, 1) == 0) {
                    firstTextIn(next)?.let { return next to it }
                }
                return null
            }

            for (i in 0 until parent.childCount) {
                val sib = childAt(parent, i) ?: continue
                if (sib == child) continue
                val t = firstTextIn(sib) ?: continue
                return sib to t
            }

            child = parent
            depth++
        }
        return null
    }

    private fun findChoiceControlNear(textNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var child = textNode
        var depth = 0
        while (depth < 3) {
            val parent = child.parent ?: return null
            when (countChoiceControls(parent)) {
                1 -> return findFirstChoiceControl(parent)
                0 -> Unit // keep climbing
                else -> {
                    // Flat layout with text before its control: [text, radio, text, radio]
                    val idx = indexInParent(parent, child)
                    val next = childAt(parent, idx + 1)
                    return if (isChoiceControl(next)) next else null
                }
            }
            child = parent
            depth++
        }
        return null
    }

    // A clickable ancestor only counts if this text is the ONLY text inside it
    private fun findOwningClickableAncestor(textNode: AccessibilityNodeInfo, maxDepth: Int = 3): AccessibilityNodeInfo? {
        var p = textNode.parent
        var depth = 0
        while (p != null && depth < maxDepth) {
            val texts = linkedSetOf<String>()
            textsIn(p, texts, limit = 2)
            if (texts.size > 1) return null
            if (isActionable(p)) return p
            p = p.parent
            depth++
        }
        return null
    }

    // ==========================================
    // LOW-LEVEL ACTIONS
    // ==========================================

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        // Try the node, then walk up parents until something accepts the click
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 5) {
            if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
            depth++
        }
        // Fallback: tap the node's centre (needs canPerformGestures in the service config)
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

        // Fallback: paste from the clipboard
        Log.w(TAG, "SET_TEXT failed, trying clipboard paste")
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("autofill", text))
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
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        private const val TAG = "FormService"
        private const val STEP_DELAY_MS = 450L

        // Chrome/WebView exposes the HTML/ARIA role in the node extras under this key
        private const val CHROME_ROLE_KEY = "AccessibilityNodeInfo.chromeRole"

        private val CHOICE_ROLES = setOf(
            "radiobutton", "radio", "checkbox", "switch",
            "menuitemradio", "menuitemcheckbox", "listboxoption", "togglebutton",
        )
        private val INTERACTIVE_ROLES = CHOICE_ROLES + setOf(
            "button", "link", "menuitem", "tab", "popupbutton", "combobox", "combobox_select",
        )

        /** The running service, or null when the user hasn't enabled it in Accessibility settings. */
        @Volatile
        var instance: FormAccessibilityService? = null
            private set
    }
}
