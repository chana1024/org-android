package com.orgutil.pomodoro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The floating break overlay: a small `TYPE_APPLICATION_OVERLAY` card shown
 * from the moment a focus interval ends until its break finishes (or the user
 * stops/dismisses it). It is plain View code — a ComposeView in an overlay
 * window needs a hand-rolled lifecycle owner, and this surface is exactly
 * three texts and two buttons.
 *
 * The per-second countdown ticks on the main-thread [Handler] only while the
 * card is attached; that is visible UI, not a background polling loop — the
 * timer's wake-up remains the single scheduled alarm.
 */
@Singleton
class PomodoroOverlay @Inject constructor() {

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val view = view ?: return
            val session = session ?: return
            val remaining = session.endAtMillis - System.currentTimeMillis()
            if (remaining <= 0L) {
                onEnded?.invoke()
                return
            }
            countdownText?.text = formatRemaining(remaining)
            handler.postDelayed(this, TICK_MS)
        }
    }

    private var view: LinearLayout? = null
    private var countdownText: TextView? = null
    private var session: PomodoroSession? = null
    private var onEnded: (() -> Unit)? = null
    private var windowManager: WindowManager? = null

    fun isShowing(): Boolean = view != null

    /**
     * Shows the card. Requires `Settings.canDrawOverlays(context)` — callers
     * must gate on it and fall back to notifications otherwise.
     *
     * All View creation and `WindowManager.addView` run on the main thread
     * (`withContext(Dispatchers.Main)`): Android Views must never be touched
     * from another thread, and callers run on Dispatchers.Default. Returns
     * false when the window could not be added (bad token, window leak, …)
     * — logged, never swallowed — so the caller can fall back to the
     * notification surface.
     */
    @SuppressLint("InflateParams")
    suspend fun show(
        context: Context,
        session: PomodoroSession,
        onStop: () -> Unit,
        onDismiss: () -> Unit,
        onEnded: () -> Unit
    ): Boolean = withContext(Dispatchers.Main) {
        if (view != null) hide()
        this@PomodoroOverlay.session = session
        this@PomodoroOverlay.onEnded = onEnded

        val dp = { value: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
            ).toInt()
        }
        // Sky-blue translucent card: dark navy ink on ~85% sky blue keeps the
        // text readable over whatever the overlay floats above; the teal Stop
        // button stays from the app palette. The card is deliberately larger
        // than the old compact toast — it is the one surface asking "rest?".
        val ink = Color.parseColor(TEXT_INK)
        val inkSoft = Color.parseColor(TEXT_INK_SOFT)
        val teal = Color.parseColor(PRIMARY_TEAL)

        val title = TextView(context).apply {
            text = "☕ " + if (session.breakIsLong) "Long break" else "Break"
            setTextColor(ink)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }
        val task = TextView(context).apply {
            text = session.taskTitle
            setTextColor(inkSoft)
            textSize = 14f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        countdownText = TextView(context).apply {
            text = formatRemaining(session.endAtMillis - System.currentTimeMillis())
            setTextColor(ink)
            textSize = 46f
            typeface = Typeface.MONOSPACE
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        // Instant stop feedback at the gesture layer: the button drops to a
        // pressed look the moment it is tapped, before the controller's
        // teardown even starts, so the card never looks like it ignored the
        // tap while the CLOCK write is still in flight.
        val stop = Button(context).apply {
            text = "Stop"
            setTextColor(Color.WHITE)
            textSize = 15f
            background = GradientDrawable().apply {
                setColor(teal)
                cornerRadius = dp(20).toFloat()
            }
            setPadding(dp(26), dp(10), dp(26), dp(10))
            setOnClickListener {
                isEnabled = false
                alpha = 0.55f
                onStop()
            }
        }
        val dismiss = Button(context).apply {
            text = "Dismiss"
            setTextColor(ink)
            textSize = 14f
            background = GradientDrawable().apply {
                setColor(Color.parseColor(CHIP_BG_ON_SKY))
                cornerRadius = dp(20).toFloat()
            }
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setOnClickListener { onDismiss() }
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(22), dp(28), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(CARD_BG_SKY))
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), Color.parseColor(CARD_STROKE_SKY))
            }
            addView(title)
            addView(
                task,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            )
            addView(
                countdownText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8); gravity = Gravity.CENTER_HORIZONTAL }
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(stop)
                    addView(dismiss)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(12) }
            )
        }

        // Safe margins: the card never spans edge to edge — 20dp clear on each
        // side (capped so it cannot grow absurdly wide on tablets/landscape).
        val screen = context.resources.displayMetrics.widthPixels
        val cardWidth = (screen - 2 * dp(20)).coerceAtMost(dp(560))
        val attrs = WindowManager.LayoutParams(
            cardWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(56)
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            wm.addView(card, attrs)
            // Remember the manager that owns the window: hide() removes the
            // view through it. This assignment was missing before, so every
            // hide() no-op'd on a null field and the "hidden" card stayed
            // attached forever — the stuck overlay users saw on Stop.
            windowManager = wm
            view = card
            handler.post(tick)
            true
        } catch (error: Exception) {
            // Visible failure: the caller keeps its notification fallback.
            Log.e(TAG, "addView for pomodoro overlay failed", error)
            view = null
            windowManager = null
            false
        }
    }

    /** Removes the card, on the main thread. Safe when not showing. */
    suspend fun hide() = withContext(Dispatchers.Main) {
        handler.removeCallbacks(tick)
        val current = view
        view = null
        countdownText = null
        if (current != null) {
            runCatching { windowManager?.removeView(current) }
        }
        windowManager = null
        Unit
    }

    private fun formatRemaining(millis: Long): String {
        val totalSeconds = (millis + 999) / 1000
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    private companion object {
        const val TAG = "PomodoroOverlay"
        const val TICK_MS = 1000L

        /** "OrgUtil Teal Light" — the app's fixed palette. */
        const val PRIMARY_TEAL = "#2AA198"

        /** Sky blue at ~85% alpha — translucent yet opaque enough for ink. */
        const val CARD_BG_SKY = "#D987CEEB"
        const val CARD_STROKE_SKY = "#99FFFFFF"

        /** Dark navy ink: readable over the sky-blue card in any lighting. */
        const val TEXT_INK = "#0E3A50"
        const val TEXT_INK_SOFT = "#263B47"

        /** Dismiss chip on sky blue: frosted white, dark ink text. */
        const val CHIP_BG_ON_SKY = "#66FFFFFF"
    }
}
