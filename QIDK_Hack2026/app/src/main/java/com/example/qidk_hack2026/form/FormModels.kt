package com.example.qidk_hack2026.form

enum class FieldKind { INPUT, OPTION }

/**
 * One thing on the screen the user could fill in or pick.
 *
 * @param label the field's own label (an input's hint/question, or an option's text)
 * @param group for options: the question they belong to ("Severity" for "High"), if known
 * @param current an input's present text; empty when it still shows its placeholder
 */
data class ScreenField(
    val id: Int,
    val kind: FieldKind,
    val label: String,
    val group: String = "",
    val current: String = "",
    val selected: Boolean = false,
    /** True for a real radio/checkbox/switch, false for other clickable text such as buttons and links. */
    val choice: Boolean = false,
)

/** What the accessibility service saw on the form at one moment. Ids are only valid for this scan. */
data class ScreenSnapshot(val fields: List<ScreenField>, val layoutText: String) {
    val inputs get() = fields.filter { it.kind == FieldKind.INPUT }
    val options get() = fields.filter { it.kind == FieldKind.OPTION }
}

sealed class FormAction {
    abstract val targetId: Int

    data class Type(override val targetId: Int, val text: String) : FormAction()
    data class Click(override val targetId: Int) : FormAction()
}

data class ExecReport(val attempted: Int, val succeeded: Int, val failedIds: List<Int>)

/**
 * Turns what the user said into form actions. The real implementation will be an on-device
 * language model; [RuleBasedExtractor] stands in until then so the rest of the path can be tested.
 */
interface FormExtractor {
    suspend fun plan(transcript: String, screen: ScreenSnapshot): List<FormAction>
}

/**
 * Cleans up what the accessibility tree reports. Web pages expose more "clickable" things than a
 * person sees as separate choices.
 */
object ScreenTidy {
    /**
     * @param choiceIds ids that are real radio/checkbox/switch controls (not just clickable text)
     */
    fun tidy(fields: List<ScreenField>, choiceIds: Set<Int>): List<ScreenField> {
        val inputLabels = fields.filter { it.kind == FieldKind.INPUT }.map { norm(it.label) }.filter { it.isNotEmpty() }.toSet()

        // A clickable <label> sitting next to its input is the input's name, not a choice.
        val withoutLabels = fields.filterNot {
            it.kind == FieldKind.OPTION && it.id !in choiceIds && norm(it.label) in inputLabels
        }

        // A radio and its caption are reported separately; keep the control so a click can't toggle twice.
        val result = mutableListOf<ScreenField>()
        for (f in withoutLabels) {
            if (f.kind != FieldKind.OPTION) {
                result += f
                continue
            }
            // Only the entry right before counts: the control and its caption are neighbours, while the
            // same word in another question ("True" in question 3 and question 6) is a different answer.
            val prev = result.lastOrNull()
            val sameAsPrev = prev != null && prev.kind == FieldKind.OPTION &&
                prev.group == f.group && norm(prev.label) == norm(f.label)
            when {
                !sameAsPrev -> result += f
                f.id in choiceIds && prev!!.id !in choiceIds -> result[result.lastIndex] = f
            }
        }
        return result
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
}
