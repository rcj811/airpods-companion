package com.rcj811.podscompanion.popup

import android.animation.ValueAnimator
import android.app.NotificationManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.rcj811.podscompanion.Pod
import com.rcj811.podscompanion.PodsState
import com.rcj811.podscompanion.R
import com.rcj811.podscompanion.service.PodsService

/**
 * iPhone/iPad-style card that slides up from the bottom when AirPods connect.
 * Uses a system overlay window (needs "display over other apps"); otherwise falls back to a
 * heads-up notification.
 */
class BatteryPopup(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var title: TextView? = null
    private var row: LinearLayout? = null
    private var footer: TextView? = null
    private val dismissRunnable = Runnable { dismiss() }

    fun show(state: PodsState, seconds: Int) {
        if (!Settings.canDrawOverlays(context)) {
            showNotificationFallback(state)
            return
        }
        handler.removeCallbacks(dismissRunnable)
        if (root == null) {
            val view = build()
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM
                y = dp(16)
            }
            try {
                wm.addView(view, lp)
            } catch (e: Exception) {
                showNotificationFallback(state)
                return
            }
            root = view
            view.translationY = dp(320).toFloat()
            view.animate().translationY(0f).setDuration(380).setInterpolator(DecelerateInterpolator(2f)).start()
        }
        update(state)
        handler.postDelayed(dismissRunnable, seconds.coerceIn(3, 30) * 1000L)
    }

    fun update(state: PodsState) {
        val r = row ?: return
        title?.text = state.deviceName ?: state.model.label
        r.removeAllViews()
        if (!state.hasBattery && state.systemLevel in 0..100) {
            r.addView(item("耳機", Pod(state.systemLevel)))
        } else if (state.model.overEar) {
            r.addView(item("電量", state.single))
        } else {
            r.addView(item("左耳", state.left))
            r.addView(item("右耳", state.right))
            r.addView(item("充電盒", state.case))
        }
        footer?.text = buildString {
            append(if (state.hasBattery) "已連線" else "讀取電量中…")
            state.listeningMode?.let { append("  ·  ${it.label}") }
        }
    }

    fun dismiss() {
        handler.removeCallbacks(dismissRunnable)
        val v = root ?: return
        root = null
        v.animate().translationY(dp(320).toFloat()).setDuration(250).withEndAction {
            runCatching { wm.removeView(v) }
        }.start()
    }

    private fun build(): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(32).toFloat()
                setColor(Color.parseColor("#F21C1C1E"))
            }
            elevation = dp(12).toFloat()
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_OUTSIDE) dismiss()
                true
            }
        }
        val handle = View(context).apply {
            background = GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(Color.parseColor("#55FFFFFF")) }
        }
        card.addView(handle, LinearLayout.LayoutParams(dp(40), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(14) })

        title = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        card.addView(title, LinearLayout.LayoutParams(-1, -2))

        row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        card.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18); bottomMargin = dp(12) })

        footer = TextView(context).apply {
            setTextColor(Color.parseColor("#99FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
        }
        card.addView(footer, LinearLayout.LayoutParams(-1, -2))

        return LinearLayout(context).apply {
            setPadding(dp(12), 0, dp(12), 0)
            addView(card, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun item(label: String, pod: Pod): View {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        col.addView(BatteryRing(context, pod), LinearLayout.LayoutParams(dp(76), dp(76)))
        col.addView(TextView(context).apply {
            text = label
            setTextColor(Color.parseColor("#CCFFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        return col.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
    }

    private fun showNotificationFallback(state: PodsState) {
        val n = NotificationCompat.Builder(context, PodsService.CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_pods)
            .setContentTitle(state.deviceName ?: state.model.label)
            .setContentText(PodsService.batteryLine(state))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(8000)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(3, n)
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    /** Circular battery gauge, green / yellow / red, with a bolt when charging. */
    private class BatteryRing(context: Context, private val pod: Pod) : View(context) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.parseColor("#33FFFFFF"); strokeCap = Paint.Cap.ROUND
        }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
        }
        private var progress = 0f
        private val rect = RectF()

        init {
            if (pod.known) {
                ValueAnimator.ofFloat(0f, pod.level / 100f).apply {
                    duration = 700
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { progress = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        }

        override fun onDraw(c: Canvas) {
            val sw = width * 0.09f
            track.strokeWidth = sw; arc.strokeWidth = sw
            rect.set(sw, sw, width - sw, height - sw)
            c.drawArc(rect, 0f, 360f, false, track)
            arc.color = when {
                pod.charging -> Color.parseColor("#34C759")
                !pod.known -> Color.TRANSPARENT
                pod.level <= 20 -> Color.parseColor("#FF3B30")
                pod.level <= 40 -> Color.parseColor("#FFCC00")
                else -> Color.parseColor("#34C759")
            }
            c.drawArc(rect, -90f, 360f * progress, false, arc)
            text.textSize = width * 0.24f
            val label = if (pod.known) "${pod.level}%" else "--"
            val y = height / 2f - (text.descent() + text.ascent()) / 2f
            c.drawText(label, width / 2f, y, text)
            if (pod.charging) {
                text.textSize = width * 0.16f
                c.drawText("⚡", width / 2f, height * 0.8f, text)
            }
        }
    }
}
