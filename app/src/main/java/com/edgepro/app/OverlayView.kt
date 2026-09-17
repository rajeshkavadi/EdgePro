package com.edgepro.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import kotlin.math.max

/**
 * Draws bounding boxes over the camera preview.
 *
 * Coordinate handling: the detector runs on the model's input resolution
 * (letterboxed camera frame). We scale results to view pixels with a single
 * uniform factor + centring offset so boxes line up with what the user sees.
 */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var results: ObjectDetectorResult? = null
    private var inputWidth = 1
    private var inputHeight = 1
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private val boxPaint = Paint().apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val textBg = Paint().apply { color = Color.parseColor("#CC000000") }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 42f
        isAntiAlias = true
    }

    fun setResults(result: ObjectDetectorResult, inW: Int, inH: Int) {
        results = result
        inputWidth = inW
        inputHeight = inH
        // PreviewView uses FILL_CENTER; match it with max() so boxes track the crop.
        scale = max(width * 1f / inW, height * 1f / inH)
        offsetX = (width - inW * scale) / 2f
        offsetY = (height - inH * scale) / 2f
        invalidate()
    }

    fun clear() {
        results = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val res = results ?: return
        for (detection in res.detections()) {
            val b = detection.boundingBox()
            val rect = RectF(
                b.left * scale + offsetX,
                b.top * scale + offsetY,
                b.right * scale + offsetX,
                b.bottom * scale + offsetY,
            )
            canvas.drawRect(rect, boxPaint)

            val cat = detection.categories().firstOrNull() ?: continue
            val label = "${cat.categoryName()} ${(cat.score() * 100).toInt()}%"
            val tw = textPaint.measureText(label)
            canvas.drawRect(
                rect.left, rect.top - 50f, rect.left + tw + 16f, rect.top, textBg
            )
            canvas.drawText(label, rect.left + 8f, rect.top - 12f, textPaint)
        }
    }
}
