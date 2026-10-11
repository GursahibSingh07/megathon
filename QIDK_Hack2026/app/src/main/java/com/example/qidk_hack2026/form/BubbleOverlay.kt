package com.example.qidk_hack2026.form

import android.accessibilityservice.AccessibilityService
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.example.qidk_hack2026.R
import com.example.qidk_hack2026.job.JobController
import com.example.qidk_hack2026.job.JobStore
import com.example.qidk_hack2026.job.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The floating bubble that stands in for the keyboard when the user leaves the form: red while the
 * speech is being worked on, green once it is ready. Tapping a green bubble, back on the form, fills
 * it and brings the keyboard up. Hosted by the accessibility service, whose overlay window needs no
 * extra permission.
 */
class BubbleOverlay(private val service: AccessibilityService) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dp = service.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()
    private fun color(id: Int) = ContextCompat.getColor(service, id)

    private val halo = View(service)
    private val core = View(service)
    private val root = FrameLayout(service).apply {
        addView(halo, FrameLayout.LayoutParams(px(60), px(60), Gravity.CENTER))
        addView(core, FrameLayout.LayoutParams(px(42), px(42), Gravity.CENTER))
        contentDescription = "Voice Fill"
    }
    private val params = WindowManager.LayoutParams(
        px(60), px(60),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = service.resources.displayMetrics.widthPixels - px(60) - px(8)
        y = px(220)
    }

    private var attached = false
    private var shownColor = 0
    private var pulse: ValueAnimator? = null
    private var tint: ValueAnimator? = null
    private var busy = false

    init {
        root.setOnTouchListener(DragTap())
        scope.launch {
            combine(JobStore.job, JobStore.imeVisible) { job, ime -> job.phase to ime }.collect { (phase, ime) ->
                val wanted = !ime && phase in SHOWN
                if (wanted) show(if (phase == Phase.READY) GREEN else RED, phase != Phase.READY) else hide()
            }
        }
    }

    fun destroy() {
        hide(immediately = true)
        scope.cancel()
    }

    private fun show(kind: Int, working: Boolean) {
        val target = if (kind == GREEN) color(R.color.ime_good) else color(R.color.ime_record)
        val end = if (kind == GREEN) color(R.color.ime_good_end) else color(R.color.ime_record_end)
        if (!attached) {
            runCatching { wm.addView(root, params) }.onFailure { return }
            attached = true
            root.scaleX = 0f; root.scaleY = 0f; root.alpha = 0f
            root.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(380).setInterpolator(OvershootInterpolator(1.6f)).start()
            paint(target, end)
        } else if (target != shownColor) {
            tint?.cancel()
            val from = shownColor
            tint = ValueAnimator.ofObject(ArgbEvaluator(), from, target).apply {
                duration = 360
                addUpdateListener { paint(it.animatedValue as Int, end) }
                start()
            }
            // A small pop when it turns green: the speech is ready.
            root.animate().scaleX(1.18f).scaleY(1.18f).setDuration(140).withEndAction {
                root.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(2f)).start()
            }.start()
        }
        busy = working
        if (working) startPulse() else stopPulse()
    }

    private fun hide(immediately: Boolean = false) {
        if (!attached) return
        stopPulse()
        val remove = { if (attached) { runCatching { wm.removeView(root) }; attached = false } }
        if (immediately) remove()
        else root.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).withEndAction(remove).start()
    }

    private fun paint(start: Int, end: Int) {
        shownColor = start
        val bead = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start, end)).apply {
            shape = GradientDrawable.OVAL
            setStroke(px(1), 0x99FFFFFF.toInt())
        }
        val sheen = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x88FFFFFF.toInt(), 0x00FFFFFF)).apply { shape = GradientDrawable.OVAL }
        core.background = LayerDrawable(arrayOf(bead, sheen)).apply {
            setLayerInset(1, px(7), px(3), px(7), px(20))
        }
        halo.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(start)
        }
        halo.alpha = 0.22f
    }

    private fun startPulse() {
        if (pulse != null || !ValueAnimator.areAnimatorsEnabled()) return
        pulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1300
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedFraction
                halo.scaleX = 0.75f + 0.25f * t; halo.scaleY = halo.scaleX
                halo.alpha = 0.34f * (1f - t)
            }
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel(); pulse = null
        halo.scaleX = 1f; halo.scaleY = 1f; halo.alpha = 0.22f
    }

    private fun onTap() {
        if (JobStore.job.value.phase != Phase.READY) return
        scope.launch { JobController.fillFromBubble(service) }
    }

    /** Drags the bubble around; a touch that barely moves is a tap. */
    private inner class DragTap : View.OnTouchListener {
        private var downX = 0f; private var downY = 0f
        private var startX = 0; private var startY = 0
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false
                    root.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > px(6) || abs(dy) > px(6)) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt(); params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(root, params) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    root.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(OvershootInterpolator(2.5f)).start()
                    if (e.actionMasked == MotionEvent.ACTION_UP) {
                        if (!moved) onTap() else snapToEdge()
                    }
                }
            }
            return true
        }
    }

    private fun snapToEdge() {
        val w = service.resources.displayMetrics.widthPixels
        val targetX = if (params.x + px(30) < w / 2) px(8) else w - px(60) - px(8)
        ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener { params.x = it.animatedValue as Int; runCatching { wm.updateViewLayout(root, params) } }
            start()
        }
    }

    private companion object {
        val SHOWN = setOf(Phase.PROCESSING, Phase.FILLING, Phase.READY)
        const val RED = 0
        const val GREEN = 1
    }
}
