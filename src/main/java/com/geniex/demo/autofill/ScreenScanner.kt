package com.geniex.demo.autofill

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

enum class NodeKind { INPUT, OPTION }

class ScanResult(
    val lines: List<String>,                       // "[ID: 8] INPUT_FIELD ..." text, for the LLM / logs
    val nodes: Map<Int, AccessibilityNodeInfo>,    // ID -> real node on screen
    val kinds: Map<Int, NodeKind>                  // ID -> input or option
)

/**
 * Turns the accessibility tree into a numbered list of inputs and options.
 *
 * NOT a service. NodeScannerService and VoiceFillAccessibilityService each create
 * their own instance. Because both use these same rules, the same screen always
 * gets the same IDs in both, so IDs from the NodeScanner log work in VoiceFill's JSON.
 *
 * If you change the rules here, both services change together (that's the point).
 */
class ScreenScanner {

    // Set to true to log the node structure around these texts
    var debugDump = false
    var debugDumpTexts = setOf("True", "False", "10 points")

    // Remembers placeholder text of input fields across scans (survives typing)
    private val hintCache = mutableMapOf<String, String>()

    // Per-scan state
    private val lines = mutableListOf<String>()
    private val nodes = mutableMapOf<Int, AccessibilityNodeInfo>()
    private val kinds = mutableMapOf<Int, NodeKind>()
    private val consumed = mutableSetOf<AccessibilityNodeInfo>()
    private var idCounter = 0
    private var lastTextSeen: String? = null

    private val CHROME_ROLE_KEY = "AccessibilityNodeInfo.chromeRole"
    private val CHOICE_ROLES = setOf(
        "radiobutton", "radio", "checkbox", "switch",
        "menuitemradio", "menuitemcheckbox", "listboxoption", "togglebutton"
    )
    private val INTERACTIVE_ROLES = CHOICE_ROLES + setOf(
        "button", "link", "menuitem", "tab", "popupbutton", "combobox", "combobox_select"
    )

    /** Call on a new screen (TYPE_WINDOW_STATE_CHANGED). */
    fun clearHints() = hintCache.clear()

    /** Scans the screen. Can throw if the page changes mid-scan, so wrap it in try/catch. */
    fun scan(root: AccessibilityNodeInfo): ScanResult {
        lines.clear()
        nodes.clear()
        kinds.clear()
        consumed.clear()
        idCounter = 0
        lastTextSeen = null

        traverseTree(root)
        return ScanResult(lines.toList(), nodes.toMap(), kinds.toMap())
    }

    // ==========================================
    // TRAVERSAL
    // ==========================================

    private fun traverseTree(node: AccessibilityNodeInfo?) {
        if (node == null) return
        if (node in consumed) return

        val cleanText = cleanTextOf(node)
        if (debugDump && cleanText in debugDumpTexts) dumpAround(node)

        // 1. Input field
        if (node.isEditable || node.className?.contains("EditText") == true) {
            val id = idCounter++
            nodes[id] = node
            kinds[id] = NodeKind.INPUT

            val key = node.viewIdResourceName ?: node.hashCode().toString()
            val hint = node.hintText?.toString()
                ?: if (node.isShowingHintText) node.text?.toString() else null
            if (!hint.isNullOrBlank()) hintCache[key] = hint

            val currentValue = if (node.isShowingHintText) "" else node.text?.toString() ?: ""
            val label = hintCache[key]
                ?: node.contentDescription?.toString()
                ?: lastTextSeen
                ?: ""

            lines.add("[ID: $id] INPUT_FIELD hint=\"$label\" current=\"$currentValue\"")
        }
        // 2. Radio / checkbox / switch itself
        else if (isChoiceControl(node)) {
            val label = cleanText
                ?: firstTextIn(node)
                ?: findLabelFor(node)?.let { (labelNode, text) ->
                    consumed.add(labelNode)
                    text
                }
                ?: "(unlabeled)"

            addOption(node, label)
            lastTextSeen = label
            return
        }
        // 3. Plain text: button, or label of a nearby radio?
        else if (cleanText != null) {
            val target = when {
                isActionable(node) -> node
                else -> findChoiceControlNear(node)?.also { consumed.add(it) }
                    ?: findOwningClickableAncestor(node)
            }

            if (target != null) {
                addOption(target, cleanText)
            } else if (cleanText.length > 2) {
                lines.add("LABEL: $cleanText")
            }
            lastTextSeen = cleanText
        }

        for (i in 0 until node.childCount) {
            traverseTree(childAt(node, i))
        }
    }

    private fun addOption(target: AccessibilityNodeInfo, label: String) {
        if (nodes.containsValue(target)) return
        val id = idCounter++
        nodes[id] = target
        kinds[id] = NodeKind.OPTION
        val state = if (target.isChecked) " (SELECTED)" else ""
        lines.add("[ID: $id] SELECTABLE_OPTION: $label$state")
    }

    // ==========================================
    // CLASSIFICATION HELPERS
    // ==========================================

    private fun cleanTextOf(n: AccessibilityNodeInfo?): String? {
        if (n == null) return null
        val raw = n.text?.toString() ?: n.contentDescription?.toString() ?: return null
        val c = raw.replace(Regex("[^a-zA-Z0-9 ?]"), "").trim()
        return if (c.length > 1) c else null
    }

    private fun chromeRole(n: AccessibilityNodeInfo): String =
        n.extras?.getString(CHROME_ROLE_KEY)?.lowercase() ?: ""

    private fun isChoiceControl(n: AccessibilityNodeInfo?): Boolean {
        if (n == null) return false
        if (n.isCheckable) return true
        val cls = n.className?.toString() ?: ""
        if (cls.contains("RadioButton") || cls.contains("CheckBox") || cls.contains("Switch")) return true
        return chromeRole(n) in CHOICE_ROLES
    }

    private fun isActionable(n: AccessibilityNodeInfo?): Boolean {
        if (n == null) return false
        if (isChoiceControl(n)) return true

        val role = chromeRole(n)
        if (role in INTERACTIVE_ROLES) return true

        val cls = n.className?.toString() ?: ""
        if (cls.contains("Button")) return true

        val hasClickAction = n.isClickable ||
                n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
        // Native views: trust the click flag. Web nodes with a generic role: don't.
        return hasClickAction && role.isEmpty()
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
        for (i in 0 until n.childCount) {
            textsIn(childAt(n, i), out, limit, depth + 1)
        }
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

    private fun findLabelFor(control: AccessibilityNodeInfo): Pair<AccessibilityNodeInfo, String>? {
        var child = control
        var depth = 0
        while (depth < 3) {
            val parent = child.parent ?: return null

            if (countChoiceControls(parent) > 1) {
                // Option group, flat layout: [radio, text, radio, text]
                val next = childAt(parent, indexInParent(parent, child) + 1)
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
                0 -> { /* keep climbing */ }
                else -> {
                    // Flat layout, text before its control: [text, radio, text, radio]
                    val next = childAt(parent, indexInParent(parent, child) + 1)
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

    private fun dumpAround(node: AccessibilityNodeInfo) {
        var n: AccessibilityNodeInfo? = node
        var d = 0
        while (n != null && d < 4) {
            Log.d(
                "NodeDump",
                "L$d cls=${n.className} role=${chromeRole(n)} text=${n.text} desc=${n.contentDescription} " +
                        "click=${n.isClickable} check=${n.isCheckable} checked=${n.isChecked} " +
                        "actions=${n.actionList.map { it.id }} children=${n.childCount}"
            )
            n = n.parent
            d++
        }
    }
}