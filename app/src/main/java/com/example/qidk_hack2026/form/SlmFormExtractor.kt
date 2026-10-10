package com.example.qidk_hack2026.form

import android.content.Context
import android.util.Log
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.ComputeUnitValue
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.ModelPullInput
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Real [FormExtractor]: a small on-device language model reads the transcript plus whatever
 * fields [FormAccessibilityService.scan] found and decides what to type/click, instead of
 * [RuleBasedExtractor]'s "find the label, take the words after it" heuristic.
 *
 * Model is Qwen3-0.6B-GGUF over GenieX's llama_cpp runtime on GPU for now — swap [MODEL_NAME] and
 * the `compute_unit`/`runtime_id` below for a better model later; nothing else in the form-fill
 * path (FormPipeline, WhisperIME, the debug receivers) needs to change.
 *
 * Falls back to [RuleBasedExtractor] whenever the model isn't loaded yet, generation fails, or its
 * output can't be parsed, so the form-fill path never dead-ends on the SLM.
 */
class SlmFormExtractor private constructor(private val appContext: Context) : FormExtractor {

    /** True when the most recent [plan] call was actually answered by the model, not the
     *  [RuleBasedExtractor] fallback. Read by [FormPipeline] right after calling [plan], so a UI
     *  can show which one actually produced a given fill instead of that being log-only. */
    @Volatile
    var lastUsedSlm: Boolean = false
        private set

    override suspend fun plan(transcript: String, screen: ScreenSnapshot): List<FormAction> {
        lastUsedSlm = false
        val llm = runCatching { ensureLoaded() }.getOrElse {
            Log.w(TAG, "SLM unavailable, falling back to rule-based extractor", it)
            return RuleBasedExtractor.plan(transcript, screen)
        }

        val raw = runCatching { complete(llm, buildPrompt(transcript, screen)) }.getOrElse {
            Log.e(TAG, "SLM generation failed, falling back to rule-based extractor", it)
            return RuleBasedExtractor.plan(transcript, screen)
        }

        val actions = runCatching { parseActions(raw, screen) }.getOrElse {
            Log.e(TAG, "Couldn't parse SLM output: $raw", it)
            return RuleBasedExtractor.plan(transcript, screen)
        }
        if (actions.isEmpty()) return RuleBasedExtractor.plan(transcript, screen)
        lastUsedSlm = true
        return actions
    }

    // ---- model lifecycle ----

    private suspend fun ensureLoaded(): LlmWrapper {
        // A second form-fill attempt while the model is still downloading/loading is the common
        // case here (the user retrying because nothing happened yet), not a rare race. Without
        // this mutex each attempt started its own ModelManagerWrapper.pullFlow() for the same
        // model, and the resulting concurrent downloads fought over GenieX's on-disk lock file —
        // observed on-device as the download appearing to stall indefinitely.
        cached?.let { return it }
        return loadMutex.withLock {
            cached ?: loadModel().also { cached = it }
        }
    }

    private suspend fun loadModel(): LlmWrapper {
        initSdkOnce()
        var paths = ModelManagerWrapper.getPaths(MODEL_NAME)
        if (paths == null) {
            download()
            paths = ModelManagerWrapper.getPaths(MODEL_NAME)
        }
        checkNotNull(paths) { "Model paths unavailable after download" }

        val input = LlmCreateInput(
            model_name = paths.model_name,
            model_path = paths.model_path,
            tokenizer_path = paths.tokenizer_path,
            config = ModelConfig(nCtx = 2048, nGpuLayers = GPU_LAYERS, enable_thinking = false),
            runtime_id = paths.runtime_id.ifEmpty { "llama_cpp" },
            compute_unit = ComputeUnitValue.GPU.value,
        )
        return LlmWrapper.builder().llmCreateInput(input).build().getOrThrow()
    }

    private suspend fun download() {
        val input = ModelPullInput(
            model_name = MODEL_NAME,
            precision = "Q4_0",
            hub = HubSource.HUGGINGFACE,
            display_name = "Qwen3-0.6B-GGUF",
        )
        var failure: ModelManagerWrapper.PullEvent.Error? = null
        ModelManagerWrapper.pullFlow(input).collect { event ->
            if (event is ModelManagerWrapper.PullEvent.Error) failure = event
        }
        failure?.let { error("Model download failed (${it.code}): ${it.message}") }
    }

    private suspend fun initSdkOnce() {
        if (sdkReady) return
        suspendCancellableCoroutine { cont ->
            GenieXSdk.getInstance().init(
                appContext,
                object : GenieXSdk.InitCallback {
                    override fun onSuccess() {
                        sdkReady = true
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onFailure(reason: String) {
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("GenieX init failed: $reason"))
                    }
                },
            )
        }
    }

    // ---- prompting ----

    private fun buildPrompt(transcript: String, screen: ScreenSnapshot): String {
        val fields = JSONArray()
        for (f in screen.fields) {
            fields.put(
                JSONObject()
                    .put("id", f.id)
                    .put("kind", f.kind.name)
                    .put("label", f.label)
                    .put("group", f.group)
                    .put("current", f.current)
                    .put("selected", f.selected),
            )
        }
        return """
            You fill in on-screen form fields from spoken narration. Reply with ONLY a JSON array,
            no prose, no markdown fences, no explanation. Use exactly these two action values —
            never any other word:
            {"id": <id>, "action": "type", "value": "<text>"} for an INPUT field, or
            {"id": <id>, "action": "click"} for an OPTION the narration selects.
            Omit fields the narration doesn't mention. Never invent a value that wasn't said or
            clearly implied by it.

            Example: given fields [{"id":5,"kind":"INPUT","label":"Email"},
            {"id":9,"kind":"OPTION","label":"Yes","group":"Subscribe?"}] and narration
            "email is jane at example dot com, yes subscribe me", reply exactly:
            [{"id":5,"action":"type","value":"jane@example.com"},{"id":9,"action":"click"}]

            Fields on screen: $fields

            Narration: ${JSONObject.quote(transcript)}
        """.trimIndent()
    }

    private suspend fun complete(llm: LlmWrapper, prompt: String): String {
        val template = llm.applyChatTemplate(arrayOf(ChatMessage(role = "user", prompt)), null, false).getOrThrow()
        val sb = StringBuilder()
        val config = GenerationConfig(
            maxTokens = MAX_OUTPUT_TOKENS,
            stopWords = null,
            stopCount = 0,
            nPast = 0,
            imagePaths = null,
            imageCount = 0,
            audioPaths = null,
            audioCount = 0,
        )
        llm.generateStreamFlow(template.formattedText, config).collect { result ->
            when (result) {
                is LlmStreamResult.Token -> sb.append(result.text)
                is LlmStreamResult.Error -> throw result.throwable
                is LlmStreamResult.Completed -> Unit
            }
        }
        return sb.toString()
    }

    // ---- parsing ----

    private fun parseActions(raw: String, screen: ScreenSnapshot): List<FormAction> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val array = JSONArray(raw.substring(start, end + 1))

        val byId = screen.fields.associateBy { it.id }
        val actions = mutableListOf<FormAction>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optInt("id", -1)
            val field = byId[id] ?: continue
            when (obj.optString("action")) {
                "type" -> if (field.kind == FieldKind.INPUT) {
                    val value = obj.optString("value").trim()
                    if (value.isNotEmpty()) actions += FormAction.Type(id, value)
                }
                "click" -> if (field.kind == FieldKind.OPTION) actions += FormAction.Click(id)
            }
        }
        return actions
    }

    companion object {
        private const val TAG = "SlmFormExtractor"
        private const val MODEL_NAME = "unsloth/Qwen3-0.6B-GGUF"
        private const val GPU_LAYERS = 999
        private const val MAX_OUTPUT_TOKENS = 512

        private val lock = Any()
        private val loadMutex = Mutex()

        @Volatile
        private var cached: LlmWrapper? = null

        @Volatile
        private var sdkReady = false

        @Volatile
        private var instance: SlmFormExtractor? = null

        fun get(context: Context): SlmFormExtractor =
            instance ?: synchronized(lock) {
                instance ?: SlmFormExtractor(context.applicationContext).also { instance = it }
            }
    }
}
