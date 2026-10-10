package com.example.qidk_hack2026.ime

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.qidk_hack2026.R

/**
 * The keyboard's panel: a mode switch on top, one big mic button in the middle that pulses while
 * listening, and a single line of status underneath. Colors come from res/values(-night)/ime_colors.xml.
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
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(color(R.color.ime_on_surface))
        minLines = 2
        maxLines = 3
    }
    private val hint = TextView(context).apply {
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(color(R.color.ime_on_primary))
        setPadding(px(18), px(8), px(18), px(8))
        background = pill(color(R.color.ime_primary))
        visibility = View.GONE
        setOnClickListener { onHint() }
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
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(px(20), px(12), px(20), px(16))
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(color(R.color.ime_surface_top), color(R.color.ime_surface_bottom)),
        ).apply {
            val r = px(28).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setStroke(px(1), color(R.color.ime_stroke))
        }

        addView(topRow(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(micStack(), LinearLayout.LayoutParams(px(104), px(104)).apply { topMargin = px(10) })
        addView(statusView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6) })
        addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8) })
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
        mic.background = disc(start, end)
        ringShape.setColor(start)
        mic.alpha = if (state == State.WORKING) 0.45f else 1f
        if (listening) startPulse() else restGlow()
        if (state != State.IDLE) hint.visibility = View.GONE
    }

    /** Sets the status line. [action] shows a button under it, for states that need the user to do something. */
    fun setStatus(message: String, action: String? = null) {
        statusView.text = message
        hint.text = action
        hint.visibility = if (action == null) View.GONE else View.VISIBLE
    }

    private fun selectMode(form: Boolean) {
        if (modeLocked()) return
        setMode(form)
        onModeChanged(form)
    }

    private fun topRow() = FrameLayout(context).apply {
        val track = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = pill(color(R.color.ime_track))
            setPadding(px(3), px(3), px(3), px(3))
            addView(dictate); addView(fillForm)
        }
        addView(track, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        val back = ImageButton(context).apply {
            setImageResource(R.drawable.ic_keyboard)
            setColorFilter(color(R.color.ime_muted))
            contentDescription = "Back to keyboard"
            background = ripple(Color_TRANSPARENT, circle = true)
            setOnClickListener { onBack() }
        }
        addView(back, FrameLayout.LayoutParams(px(44), px(44), Gravity.END or Gravity.CENTER_VERTICAL))
    }

    private fun micStack() = FrameLayout(context).apply {
        addView(ring, FrameLayout.LayoutParams(px(72), px(72), Gravity.CENTER))
        addView(spinner, FrameLayout.LayoutParams(px(96), px(96), Gravity.CENTER))
        addView(mic, FrameLayout.LayoutParams(px(72), px(72), Gravity.CENTER))
    }

    private fun chip(label: String) = TextView(context).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        minWidth = px(104)
        setPadding(px(14), px(8), px(14), px(8))
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

    /** A gradient disc with a thin highlight edge, so the button reads as a glass bead. */
    private fun disc(start: Int, end: Int): RippleDrawable {
        val shape = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start, end)).apply {
            this.shape = GradientDrawable.OVAL
            setStroke(px(1), 0x66FFFFFF)
        }
        return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, shape)
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
        ring.scaleX = 1.28f; ring.scaleY = 1.28f
        ring.alpha = 0.13f
    }

    private fun startPulse() {
        if (!ValueAnimator.areAnimatorsEnabled()) { ring.alpha = 0.18f; return }
        pulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedFraction
                val scale = 1f + 0.4f * t
                ring.scaleX = scale; ring.scaleY = scale
                ring.alpha = 0.32f * (1f - t)
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

    private companion object {
        const val Color_TRANSPARENT = 0x00000000
    }
}
