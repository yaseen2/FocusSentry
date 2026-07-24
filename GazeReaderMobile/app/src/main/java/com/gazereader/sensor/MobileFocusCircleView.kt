package com.gazereader.sensor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
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
        strokeWidth = 24f
        color = Color.parseColor("#131c2e")
    }

    private val innerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#1e293b")
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 24f
        strokeCap = Paint.Cap.ROUND
    }

    private val percentTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#f8fafc")
        textSize = 68f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val durTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94a3b8")
        textSize = 34f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val statusDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10b981")
        style = Paint.Style.FILL
    }

    private val statusTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10b981")
        textSize = 26f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val rectF = RectF()
    private val innerRectF = RectF()
    private val gradientMatrix = Matrix()

    fun setProgress(activeSec: Int, targetSec: Int = 9 * 3600) {
        this.activeSeconds = activeSec
        this.targetSeconds = if (targetSec > 0) targetSec else 9 * 3600
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val size = Math.min(w, h) - 48f
        if (size <= 0) return

        val cx = w / 2f
        val cy = h / 2f

        rectF.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        innerRectF.set(cx - (size / 2f - 24f), cy - (size / 2f - 24f), cx + (size / 2f - 24f), cy + (size / 2f - 24f))

        // 1. Draw Background Track & Inset Ring Depth
        canvas.drawOval(rectF, bgPaint)
        canvas.drawOval(innerRectF, innerBorderPaint)

        // 2. Draw Sweep Gradient Progress Arc
        val percent = Math.min(1.0f, activeSeconds.toFloat() / targetSeconds.toFloat())
        if (percent > 0) {
            val colors = if (percent >= 1.0f) {
                intArrayOf(Color.parseColor("#10b981"), Color.parseColor("#34d399"), Color.parseColor("#10b981"))
            } else {
                intArrayOf(Color.parseColor("#6366f1"), Color.parseColor("#06b6d4"), Color.parseColor("#10b981"))
            }

            val sweepGradient = SweepGradient(cx, cy, colors, floatArrayOf(0.0f, 0.5f, 1.0f))
            gradientMatrix.setRotate(270f, cx, cy)
            sweepGradient.setLocalMatrix(gradientMatrix)

            progressPaint.shader = sweepGradient
            val sweepAngle = percent * 360f
            canvas.drawArc(rectF, 270f, sweepAngle, false, progressPaint)
        }

        // 3. Draw Percentage Text
        val percentStr = "${(percent * 100).toInt()}%"
        canvas.drawText(percentStr, cx, cy - 16f, percentTextPaint)

        // 4. Draw Duration String
        val hPart = activeSeconds / 3600
        val mPart = (activeSeconds % 3600) / 60
        val targetH = targetSeconds / 3600
        val durationStr = "${hPart}h ${mPart}m / ${targetH}h"
        canvas.drawText(durationStr, cx, cy + 34f, durTextPaint)

        // 5. Draw Status Badge Dot & Text
        val statusText = if (percent >= 1.0f) "QUEST COMPLETED 🏆" else "● ON TARGET TODAY"
        statusTextPaint.color = if (percent >= 1.0f) Color.parseColor("#34d399") else Color.parseColor("#6366f1")
        canvas.drawText(statusText, cx, cy + 80f, statusTextPaint)
    }
}
