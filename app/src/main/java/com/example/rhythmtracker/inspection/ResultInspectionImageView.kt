package com.example.rhythmtracker.inspection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.example.rhythmtracker.vision.DebugRegion
import kotlin.math.min

/** Draws an imported screenshot plus the exact normalized regions the parser accepted. */
class ResultInspectionImageView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private var regions: List<DebugRegion> = emptyList()

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(64, 220, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(11f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val labelBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC101114.toInt()
        style = Paint.Style.FILL
    }

    fun setInspectionBitmap(value: Bitmap, valueRegions: List<DebugRegion>) {
        bitmap?.takeIf { it !== value }?.recycle()
        bitmap = value
        regions = valueRegions
        requestLayout()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        bitmap?.recycle()
        bitmap = null
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(dp(240f).toInt())
        val source = bitmap
        val desired = if (source == null || source.width <= 0) {
            dp(180f).toInt()
        } else {
            (width.toFloat() * source.height / source.width)
                .toInt()
                .coerceIn(dp(150f).toInt(), dp(430f).toInt())
        }
        setMeasuredDimension(width, resolveSize(desired, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(18, 20, 24))
        val source = bitmap ?: return

        val scale = min(width.toFloat() / source.width, height.toFloat() / source.height)
        val drawnWidth = source.width * scale
        val drawnHeight = source.height * scale
        val left = (width - drawnWidth) * 0.5f
        val top = (height - drawnHeight) * 0.5f
        val destination = RectF(left, top, left + drawnWidth, top + drawnHeight)
        canvas.drawBitmap(source, null, destination, imagePaint)

        regions.forEach { region ->
            val box = RectF(
                left + region.bounds.left * drawnWidth,
                top + region.bounds.top * drawnHeight,
                left + region.bounds.right * drawnWidth,
                top + region.bounds.bottom * drawnHeight
            )
            canvas.drawRect(box, boxPaint)

            val label = region.key.uppercase()
            val textWidth = labelPaint.measureText(label)
            val textHeight = labelPaint.fontMetrics.let { it.descent - it.ascent }
            val labelTop = (box.top - textHeight - dp(4f)).coerceAtLeast(top)
            val labelBox = RectF(
                box.left,
                labelTop,
                box.left + textWidth + dp(8f),
                labelTop + textHeight + dp(4f)
            )
            canvas.drawRect(labelBox, labelBackground)
            canvas.drawText(
                label,
                labelBox.left + dp(4f),
                labelBox.bottom - dp(3f) - labelPaint.fontMetrics.descent,
                labelPaint
            )
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
