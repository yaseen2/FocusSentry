package com.gazereader.sensor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class MobileFocusCircleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var activeSeconds: Int = 0
    private var targetSeconds: Int = 9 * 3600

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 20f
        color = Color.parseColor("#1e293b")
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 20f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#6366f1")
    }

    private val percentTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#f8fafc")
        textSize = 56f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val durTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94a3b8")
        textSize = 32f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val rectF = RectF()

    fun setProgress(activeSec: Int, targetSec: Int = 9 * 3600) {
        this.activeSeconds = activeSec
        this.targetSeconds = if (targetSec > 0) targetSec else 9 * 3600
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val size = Math.min(w, h) - 40f
        if (size <= 0) return

        val cx = w / 2f
        val cy = h / 2f

        rectF.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)

        // 1. Draw Background Track
        canvas.drawOval(rectF, bgPaint)

        // 2. Draw Progress Arc
        val percent = Math.min(1.0f, activeSeconds.toFloat() / targetSeconds.toFloat())
        if (percent > 0) {
            progressPaint.color = if (percent < 1.0f) Color.parseColor("#6366f1") else Color.parseColor("#10b981")
            val sweepAngle = percent * 360f
            // Start at top (270 degrees)
            canvas.drawArc(rectF, 270f, sweepAngle, false, progressPaint)
        }

        // 3. Draw Percentage Text
        val percentStr = "${(percent * 100).toInt()}%"
        canvas.drawText(percentStr, cx, cy - 10f, percentTextPaint)

        // 4. Draw Duration String
        val hPart = activeSeconds / 3600
        val mPart = (activeSeconds % 3600) / 60
        val targetH = targetSeconds / 3600
        val durationStr = "${hPart}h ${mPart}m / ${targetH}h"
        canvas.drawText(durationStr, cx, cy + 40f, durTextPaint)
    }
}
