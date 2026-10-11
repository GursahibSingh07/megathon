package com.example.qidk_hack2026.ime

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.qidk_hack2026.R

/**
 * The voice keyboard's panel, kept small so the app behind stays visible: one glass strip with the
 * mic bead on the left, a status line and the Dictate / Fill form switch beside it, and a button to
 * go back to the typing keyboard. Colors come from res/values(-night)/ime_colors.xml.
 */
class KeyboardPanel(private val context: Context) {
    enum class State { IDLE, LISTENING, WORKING }

    var onMic: () -> Unit = {}
    /** The button under the status line (open settings, open app, grant permission). */
    var onHint: () -> Unit = {}
    var onModeChanged: (formMode: Boolean) -> Unit = {}
    var onBack: () -> Unit = {}
    /** Mode changes are ignored while this returns true (recording or transcribing). */
    var modeLocked: () -> Boolean = { false }

    private val dp = context.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()
    private fun color(id: Int) = ContextCompat.getColor(context, id)

    private val dictate = chip("Dictate")
    private val fillForm = chip("Fill form")
    private val statusView = TextView(context).apply {
        textSize = 14f
        setTextColor(color(R.color.ime_on_surface))
        minLines = 2
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val hint = TextView(context).apply {
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(color(R.color.ime_on_primary))
        setPadding(px(16), px(6), px(16), px(6))
        background = pill(color(R.color.ime_primary))
        visibility = View.GONE
        setOnClickListener { onHint() }
    }
    private val segment = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        background = pill(color(R.color.ime_track))
        setPadding(px(2), px(2), px(2), px(2))
        addView(dictate); addView(fillForm)
    }
    private val ringShape = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private val ring = View(context).apply {
        background = ringShape
        alpha = 0f
    }
    private val spinner = ProgressBar(context).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(color(R.color.ime_primary))
        visibility = View.GONE
    }
    private val mic = ImageButton(context).apply {
        scaleType = android.widget.ImageView.ScaleType.CENTER
        contentDescription = "Start or stop dictation"
        setOnClickListener { onMic() }
    }
    private var pulse: ValueAnimator? = null

    val root: View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(16), px(12), px(8), px(12))
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(color(R.color.ime_surface_top), color(R.color.ime_surface_bottom)),
        ).apply {
            val r = px(26).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setStroke(px(1), color(R.color.ime_stroke))
        }

        addView(micStack(), LinearLayout.LayoutParams(px(72), px(72)))
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(statusView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(segment, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6) })
                addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6) })
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(8) },
        )
        addView(
            ImageButton(context).apply {
                setImageResource(R.drawable.ic_keyboard)
                setColorFilter(color(R.color.ime_muted))
                contentDescription = "Back to keyboard"
                background = ripple(0x00000000, circle = true)
                setOnClickListener { onBack() }
            },
            LinearLayout.LayoutParams(px(44), px(44)),
        )
    }

    init {
        dictate.setOnClickListener { selectMode(false) }
        fillForm.setOnClickListener { selectMode(true) }
        setMode(false)
        setStatus("Tap the mic to speak")
        setState(State.IDLE)
    }

    fun setMode(form: Boolean) {
        style(dictate, !form)
        style(fillForm, form)
    }

    fun setState(state: State) {
        stopPulse()
        spinner.visibility = if (state == State.WORKING) View.VISIBLE else View.GONE
        mic.isEnabled = state != State.WORKING
        val listening = state == State.LISTENING
        mic.setImageResource(if (listening) R.drawable.ic_stop else R.drawable.ic_mic)
        mic.setColorFilter(color(R.color.ime_on_primary))
        val start = color(if (listening) R.color.ime_record else R.color.ime_primary)
        val end = color(if (listening) R.color.ime_record_end else R.color.ime_primary_end)
        mic.background = bead(start, end)
        ringShape.setColor(start)
        mic.alpha = if (state == State.WORKING) 0.45f else 1f
        if (listening) startPulse() else restGlow()
        if (state != State.IDLE) setHint(null)
        // A small pop whenever the bead changes meaning.
        mic.animate().cancel()
        mic.scaleX = 0.88f; mic.scaleY = 0.88f
        mic.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(2.2f)).start()
    }

    /** Sets the status line. [action] shows a button under it, for states that need the user to do something. */
    fun setStatus(message: String, action: String? = null) {
        if (statusView.text.toString() != message) {
            statusView.alpha = 0f
            statusView.text = message
            statusView.animate().alpha(1f).setDuration(180).start()
        }
        setHint(action)
    }

    private fun setHint(action: String?) {
        hint.text = action
        hint.visibility = if (action == null) View.GONE else View.VISIBLE
        segment.visibility = if (action == null) View.VISIBLE else View.GONE
    }

    private fun selectMode(form: Boolean) {
        if (modeLocked()) return
        setMode(form)
        onModeChanged(form)
    }

    private fun micStack() = FrameLayout(context).apply {
        addView(ring, FrameLayout.LayoutParams(px(52), px(52), Gravity.CENTER))
        addView(spinner, FrameLayout.LayoutParams(px(68), px(68), Gravity.CENTER))
        addView(mic, FrameLayout.LayoutParams(px(52), px(52), Gravity.CENTER))
    }

    private fun chip(label: String) = TextView(context).apply {
        text = label
        textSize = 12.5f
        gravity = Gravity.CENTER
        minWidth = px(84)
        setPadding(px(12), px(5), px(12), px(5))
    }

    private fun style(view: TextView, selected: Boolean) {
        view.background = if (selected) pill(color(R.color.ime_track_selected)).apply { setStroke(px(1), color(R.color.ime_stroke)) } else null
        view.setTextColor(color(if (selected) R.color.ime_on_surface else R.color.ime_muted))
        view.typeface = if (selected) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
    }

    private fun pill(fill: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = px(100).toFloat()
    }

    /** A gradient bead with a bright rim and a curved highlight on top, so it reads as a drop of glass. */
    private fun bead(start: Int, end: Int): RippleDrawable {
        val body = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start, end)).apply {
            shape = GradientDrawable.OVAL
            setStroke(px(1), 0x88FFFFFF.toInt())
        }
        val sheen = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x77FFFFFF, 0x00FFFFFF)).apply { shape = GradientDrawable.OVAL }
        val layers = LayerDrawable(arrayOf(body, sheen)).apply { setLayerInset(1, px(9), px(3), px(9), px(26)) }
        return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), layers, body)
    }

    private fun ripple(fill: Int, circle: Boolean): RippleDrawable {
        val shape = GradientDrawable().apply {
            this.shape = if (circle) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
            setColor(fill)
        }
        return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, shape)
    }

    /** A soft halo behind the idle mic. */
    private fun restGlow() {
        ring.scaleX = 1.3f; ring.scaleY = 1.3f
        ring.alpha = 0.16f
    }

    private fun startPulse() {
        if (!ValueAnimator.areAnimatorsEnabled()) { ring.alpha = 0.2f; return }
        pulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedFraction
                val scale = 1f + 0.45f * t
                ring.scaleX = scale; ring.scaleY = scale
                ring.alpha = 0.34f * (1f - t)
            }
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        ring.alpha = 0f
        ring.scaleX = 1f; ring.scaleY = 1f
    }
}
