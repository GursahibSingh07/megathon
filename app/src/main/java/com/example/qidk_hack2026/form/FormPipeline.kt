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
    /** True when [SlmFormExtractor]'s model actually produced the actions, false for the
     *  [RuleBasedExtractor] fallback (model unavailable, generation failed, or output unusable). */
    val usedSlm: Boolean = false,
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

        val filledFields = (
            actions.filterIsInstance<FormAction.Type>().map { it.targetId } +
                actions.filterIsInstance<FormAction.Select>().map { it.targetId }
            ).toSet() - report.failedIds.toSet()
        val missing = (screen.inputs + screen.dropdowns)
            .filter { it.current.isBlank() && it.id !in filledFields }
            .map { it.label.ifBlank { "field ${it.id}" } }

        return FillResult(
            status = if (actions.isEmpty()) FillResult.Status.NOTHING_MATCHED else FillResult.Status.DONE,
            filled = report.succeeded,
            failed = report.failedIds.size,
            missing = missing,
            usedSlm = (extractor as? SlmFormExtractor)?.lastUsedSlm ?: false,
        )
    }
}
