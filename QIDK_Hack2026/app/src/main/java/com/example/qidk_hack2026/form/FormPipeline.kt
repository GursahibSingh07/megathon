package com.example.qidk_hack2026.form

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of one "speak -> fill the form" pass. */
data class FillResult(
    val status: Status,
    val filled: Int = 0,
    val failed: Int = 0,
    /** Input fields still empty afterwards: what the assistant should ask about next. */
    val missing: List<String> = emptyList(),
    /** What went into the form this pass, as label and value. */
    val entries: List<com.example.qidk_hack2026.job.Entry> = emptyList(),
    /** A name for the form, from what the page shows. */
    val title: String = "",
) {
    enum class Status { SERVICE_OFF, NO_FORM, NOTHING_MATCHED, DONE }
}

/**
 * Transcript -> actions -> typed into the real form on screen.
 *
 * Reads the form fresh each time, so it also works for the second turn (filling the gaps the first
 * pass left): fields that already have text are not asked for again.
 */
object FormPipeline {
    private const val TAG = "FormPipeline"

    /** True when the user has switched the form service on in system Accessibility settings. */
    fun isServiceEnabled(context: Context): Boolean {
        val me = ComponentName(context, FormAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled?.split(':')?.any { it.equals(me, ignoreCase = true) } == true
    }

    suspend fun run(transcript: String, extractor: FormExtractor = RuleBasedExtractor): FillResult {
        val service = FormAccessibilityService.instance ?: return FillResult(FillResult.Status.SERVICE_OFF)

        val screen = withContext(Dispatchers.Default) { service.scan() }
        if (screen == null || screen.fields.isEmpty()) return FillResult(FillResult.Status.NO_FORM)

        Log.d(TAG, "SCREEN:\n${screen.layoutText}")
        val actions = extractor.plan(transcript, screen)
        Log.d(TAG, "ACTIONS for \"$transcript\": $actions")
        val report = if (actions.isEmpty()) ExecReport(0, 0, emptyList()) else service.execute(actions)

        val filledInputs = actions.filterIsInstance<FormAction.Type>().map { it.targetId }.toSet() - report.failedIds.toSet()
        val missing = screen.inputs
            .filter { it.current.isBlank() && it.id !in filledInputs }
            .map { it.label.ifBlank { "field ${it.id}" } }

        return FillResult(
            status = if (actions.isEmpty()) FillResult.Status.NOTHING_MATCHED else FillResult.Status.DONE,
            filled = report.succeeded,
            failed = report.failedIds.size,
            missing = missing,
            entries = entriesFor(actions, screen, report.failedIds.toSet()),
            title = titleOf(screen),
        )
    }

    /** Types [text] into the empty field called [label]; used when the assistant asked for that one field. */
    suspend fun fillField(label: String, text: String): FillResult {
        val service = FormAccessibilityService.instance ?: return FillResult(FillResult.Status.SERVICE_OFF)
        val screen = withContext(Dispatchers.Default) { service.scan() }
        if (screen == null || screen.fields.isEmpty()) return FillResult(FillResult.Status.NO_FORM)
        val target = screen.inputs.firstOrNull { it.label.ifBlank { "field ${it.id}" } == label }
            ?: return FillResult(FillResult.Status.NOTHING_MATCHED, missing = emptyMissing(screen))
        val actions = listOf<FormAction>(FormAction.Type(target.id, cleanAnswer(text, label)))
        val report = service.execute(actions)
        val filledIds = if (report.failedIds.isEmpty()) setOf(target.id) else emptySet()
        return FillResult(
            status = FillResult.Status.DONE,
            filled = report.succeeded,
            failed = report.failedIds.size,
            missing = screen.inputs.filter { it.current.isBlank() && it.id !in filledIds }
                .map { it.label.ifBlank { "field ${it.id}" } },
            entries = entriesFor(actions, screen, report.failedIds.toSet()),
            title = titleOf(screen),
        )
    }

    private fun emptyMissing(screen: ScreenSnapshot) =
        screen.inputs.filter { it.current.isBlank() }.map { it.label.ifBlank { "field ${it.id}" } }

    /** A spoken answer is a sentence; the field wants the bare value. */
    internal fun cleanAnswer(text: String, label: String): String {
        val t = text.trim().trimEnd('.', '!', '?', ' ')
        return RuleBasedExtractor.formatFor(label, t)
    }

    private fun entriesFor(actions: List<FormAction>, screen: ScreenSnapshot, failed: Set<Int>) =
        actions.filter { it.targetId !in failed }.mapNotNull { a ->
            val f = screen.fields.firstOrNull { it.id == a.targetId } ?: return@mapNotNull null
            when (a) {
                is FormAction.Type -> com.example.qidk_hack2026.job.Entry(f.label.ifBlank { "Field" }, a.text)
                is FormAction.Click -> com.example.qidk_hack2026.job.Entry(f.group.ifBlank { "Choice" }, f.label)
            }
        }

    private fun titleOf(screen: ScreenSnapshot): String =
        screen.layoutText.lineSequence().map { it.trim().replace(Regex("^[A-Z_]{2,}:\\s*"), "") }
            .firstOrNull { it.length in 3..80 && !it.startsWith("[") }?.take(60).orEmpty()
}
