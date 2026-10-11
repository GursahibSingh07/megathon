package com.example.qidk_hack2026.form

/**
 * Stand-in for the on-device language model. It looks for each field's label in the narration and
 * takes the words after it, up to the next label, as the value:
 *
 *   "name is Priya Shah, email priya at example dot com, severity high"
 *     -> Name = "Priya Shah", Email = "priya@example.com", Severity: click "High"
 *
 * It only understands narrations that mention the field names. Free conversational speech is what
 * the language model is for; this exists so the capture -> fill path can be exercised end to end.
 */
object RuleBasedExtractor : FormExtractor {

    override suspend fun plan(transcript: String, screen: ScreenSnapshot) = extract(transcript, screen)

    private class Anchor(val range: IntRange, val fieldId: Int?, val group: String?)

    fun extract(transcript: String, screen: ScreenSnapshot): List<FormAction> {
        if (transcript.isBlank()) return emptyList()

        val anchors = findAnchors(transcript, screen)
        val actions = mutableListOf<FormAction>()

        for ((i, anchor) in anchors.withIndex()) {
            val end = anchors.getOrNull(i + 1)?.range?.first ?: transcript.length
            val segment = transcript.substring(anchor.range.last + 1, end)

            if (anchor.fieldId != null) {
                val field = screen.fields.first { it.id == anchor.fieldId }
                val value = formatFor(field.label, cleanValue(segment))
                if (value.isNotEmpty()) {
                    actions += when (field.kind) {
                        FieldKind.DROPDOWN -> FormAction.Select(field.id, value)
                        else -> FormAction.Type(field.id, value)
                    }
                }
            } else if (anchor.group != null) {
                actions += clicksFor(screen.options.filter { it.group == anchor.group }, segment)
            }
        }

        // Options whose question was never mentioned: accept them only if their own label is spoken.
        val mentioned = anchors.mapNotNull { it.group }.toSet()
        val loose = screen.options.filter { it.group !in mentioned }
        actions += clicksFor(loose, transcript)

        return actions.distinctBy { it.targetId }.sortedBy { it.targetId }
    }

    // ---- anchors: where in the narration each field / question is named ----

    private fun findAnchors(transcript: String, screen: ScreenSnapshot): List<Anchor> {
        val candidates = mutableListOf<Anchor>()
        for (field in screen.inputs + screen.dropdowns) {
            findPhrase(transcript, field.label)?.let { candidates += Anchor(it, field.id, null) }
        }
        for (group in screen.options.map { it.group }.filter { it.isNotBlank() }.distinct()) {
            findPhrase(transcript, group)?.let { candidates += Anchor(it, null, group) }
        }
        // Longest match wins when two labels overlap ("full name" over "name"), then read left to right.
        val chosen = mutableListOf<Anchor>()
        for (c in candidates.sortedByDescending { it.range.last - it.range.first }) {
            if (chosen.none { it.range.first <= c.range.last && c.range.first <= it.range.last }) chosen += c
        }
        return chosen.sortedBy { it.range.first }
    }

    private val filler = setOf("your", "the", "enter", "please", "of", "a", "an", "what", "is", "was", "type")

    /** Finds the label in the narration: the whole phrase first, then its most specific single word. */
    private fun findPhrase(transcript: String, label: String): IntRange? {
        val words = label.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in filler }
        if (words.isEmpty()) return null
        val whole = Regex("\\b" + words.joinToString("\\W+") { Regex.escape(it) } + "\\b", RegexOption.IGNORE_CASE)
        whole.find(transcript)?.let { return it.range }
        for (w in words.filter { it.length >= 3 }.sortedByDescending { it.length }) {
            Regex("\\b" + Regex.escape(w) + "\\b", RegexOption.IGNORE_CASE).find(transcript)?.let { return it.range }
        }
        return null
    }

    // ---- values ----

    private val leadingConnectors =
        Regex("^[\\s,:\\-]*(?:(?:is|was|are|equals?|should be|to be|as|it's|its)\\b[\\s,:\\-]*)*", RegexOption.IGNORE_CASE)
    private val trailingJunk = Regex("(?:[\\s,]+and)?[\\s,.;!?]*$", RegexOption.IGNORE_CASE)

    private fun cleanValue(segment: String): String =
        segment.replace(leadingConnectors, "").replace(trailingJunk, "").trim()

    // Speech recognition writes "priya at example dot com"; the field needs an address.
    private fun formatFor(label: String, value: String): String {
        val l = label.lowercase()
        return when {
            "mail" in l -> value.replace(Regex("\\s+at\\s+", RegexOption.IGNORE_CASE), "@")
                .replace(Regex("\\s+dot\\s+", RegexOption.IGNORE_CASE), ".")
                .replace(" ", "").lowercase()
            "phone" in l || "mobile" in l || "number" in l ->
                if (value.count { it.isDigit() } >= value.count { it.isLetter() }) value.filter { it.isDigit() || it == '+' } else value
            else -> value
        }
    }

    // ---- options ----

    // Only a real radio/checkbox/switch may be clicked by voice — never a plain button
    // such as Submit/Send/Done, which [ScreenField.choice] marks false.
    private fun clicksFor(options: List<ScreenField>, segment: String): List<FormAction> =
        options.filter { it.choice && !it.selected && labelSpoken(it.label, segment) }.map { FormAction.Click(it.id) }

    private fun labelSpoken(label: String, text: String): Boolean {
        val clean = label.trim()
        if (clean.length < 2) return false
        return Regex("\\b" + Regex.escape(clean) + "\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }
}
