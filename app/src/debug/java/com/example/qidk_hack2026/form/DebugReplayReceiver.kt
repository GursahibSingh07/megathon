package com.example.qidk_hack2026.form

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * Debug-only. Replays a NodeScanner-style action list (`[{"action":"type","target_id":8,"value":"x"},
 * {"action":"click","target_id":9}]`) against the form on screen, then reads the form back to check it.
 *
 * Those ids come from another scan of the form, so they cannot be used as-is. They are positions: the
 * lowest `type` id is the form's first text field, and each id after it is the next control (text field
 * or real radio/checkbox) in page order. This maps the ids onto the controls found now and prints what
 * each one resolved to, so a wrong mapping is visible before anything is applied.
 *
 * adb shell am broadcast -n com.example.qidk_hack2026/.form.DebugReplayReceiver --es json_b64 <base64> [--ez apply true]
 */
class DebugReplayReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val b64 = intent.getStringExtra("json_b64") ?: return
        val apply = intent.getBooleanExtra("apply", false)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                replay(String(Base64.decode(b64, Base64.DEFAULT)), apply)
            } catch (e: Exception) {
                Log.e(TAG, "replay threw", e)
            } finally {
                pending.finish()
            }
        }
    }

    private class Step(val action: String, val jsonId: Int, val value: String?)

    private suspend fun replay(json: String, apply: Boolean) {
        val service = FormAccessibilityService.instance ?: return log("FAIL service is not running")
        val before = service.scan() ?: return log("FAIL nothing to scan")

        val controls = before.fields.filter { it.kind == FieldKind.INPUT || it.choice }
        log("controls found: ${controls.size}")
        controls.forEachIndexed { i, c -> log("  #$i id=${c.id} ${c.kind} '${c.label.take(60)}' selected=${c.selected} current='${c.current.take(30)}'") }

        val arr = JSONArray(json)
        val steps = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Step(o.getString("action").lowercase(), o.getInt("target_id"), if (o.has("value")) o.getString("value") else null)
        }
        val base = steps.filter { it.action == "type" }.minOfOrNull { it.jsonId } ?: return log("FAIL json has no type action to anchor on")

        val plan = mutableListOf<Pair<Step, ScreenField>>()
        for (s in steps) {
            val control = controls.getOrNull(s.jsonId - base)
            val ok = control != null && when (s.action) {
                "type" -> control.kind == FieldKind.INPUT
                "click" -> control.choice
                else -> false
            }
            log("json ${s.action} id=${s.jsonId} -> control #${s.jsonId - base} ${control?.let { "'${it.label.take(60)}' (${it.kind})" } ?: "none"} ${if (ok) "OK" else "MISMATCH"}")
            if (ok) plan += s to control!!
        }
        if (plan.size != steps.size) return log("FAIL ${steps.size - plan.size} step(s) do not fit this form; nothing applied")
        if (!apply) return log("dry run only (pass --ez apply true to apply)")

        val actions = plan.map { (s, c) ->
            if (s.action == "type") FormAction.Type(c.id, s.value ?: "") else FormAction.Click(c.id)
        }
        val report = service.execute(actions)
        log("executed ${report.attempted}, succeeded ${report.succeeded}, failed ids ${report.failedIds}")

        // The browser refreshes its accessibility tree a moment after a change, and element ids can
        // shift, so re-read until it settles and compare controls by their position in the form.
        var pass = 0
        repeat(6) { attempt ->
            delay(1_000)
            val now = service.scan()?.fields?.filter { it.kind == FieldKind.INPUT || it.choice }
            if (now == null || now.size != controls.size) {
                log("re-read ${attempt + 1}: ${now?.size} controls (expected ${controls.size})")
                return@repeat
            }
            val results = plan.map { (s, c) ->
                val cur = now[controls.indexOf(c)]
                val good = if (s.action == "type") cur.current == s.value else cur.selected
                Triple(s, c, good to (if (s.action == "type") "'${cur.current}'" else "selected=${cur.selected}"))
            }
            pass = results.count { it.third.first }
            if (pass == plan.size || attempt == 5) {
                results.forEach { (s, c, r) ->
                    log("${if (r.first) "PASS" else "FAIL"} ${s.action} json-id=${s.jsonId} '${c.label.take(40)}' now=${r.second}")
                }
                return log("VERIFY $pass/${plan.size} passed (read ${attempt + 1})")
            }
        }
        log("VERIFY inconclusive: $pass/${plan.size}")
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
    }

    private companion object {
        const val TAG = "FormReplay"
    }
}
