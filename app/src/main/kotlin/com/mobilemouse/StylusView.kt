package com.mobilemouse

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Full-screen touch pad that:
 *  - Accepts finger, stylus, and eraser inputs
 *  - Reads pressure, tilt (orientation), and twist if available
 *  - Draws a smooth visual trail with pressure-width mapping
 *  - Calls onStylusEvent for each meaningful event
 */
class StylusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onStylusEvent: ((packet: ByteArray) -> Unit)? = null
    var onPressureChanged: ((pressure: Float) -> Unit)? = null

    // -- Drawing --------------------------------------------------------------
    private val trailBitmap: Bitmap? get() = if (width > 0 && height > 0) _trailBitmap else null
    private var _trailBitmap: Bitmap? = null
    private var _trailCanvas: Canvas? = null

    private val penPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#E94560")
    }

    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#0D1117") // erase = background color
    }

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#0D1117")
    }

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#E9456033")
        strokeWidth = 1f
    }

    private var lastX = 0f
    private var lastY = 0f
    private var isDrawing = false

    // -- Event rate tracking --------------------------------------------------
    private var eventCount = 0
    private var lastEventRateTime = System.currentTimeMillis()
    var onEventRate: ((hz: Int) -> Unit)? = null

    // -- Clear button area ----------------------------------------------------
    private val clearRect = RectF()
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22E94560")
        style = Paint.Style.FILL
    }
    private val clearTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E94560")
        textSize = 30f
        textAlign = Paint.Align.CENTER
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        _trailBitmap?.recycle()
        _trailBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        _trailCanvas = Canvas(_trailBitmap!!)
        _trailCanvas!!.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        clearRect.set(w - 90f, 12f, w - 12f, 52f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        _trailBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        // Crosshair grid (subtle)
        val step = 80f
        var x = step
        while (x < width) { canvas.drawLine(x, 0f, x, height.toFloat(), crosshairPaint); x += step }
        var y = step
        while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, crosshairPaint); y += step }

        // Clear button
        canvas.drawRoundRect(clearRect, 8f, 8f, clearPaint)
        canvas.drawText("CLR", clearRect.centerX(), clearRect.centerY() + 10f, clearTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Tap on clear button?
        if (event.action == MotionEvent.ACTION_DOWN &&
            clearRect.contains(event.x, event.y)) {
            clearCanvas()
            return true
        }

        val toolType = event.getToolType(0)
        val isEraser = toolType == MotionEvent.TOOL_TYPE_ERASER
        val isStylusOrFinger = toolType == MotionEvent.TOOL_TYPE_STYLUS ||
                               toolType == MotionEvent.TOOL_TYPE_FINGER

        if (!isStylusOrFinger && !isEraser) return false

        // Process all historical batched points for smoothness
        val historySize = event.historySize
        for (h in 0 until historySize) {
            processPoint(
                action = MotionEvent.ACTION_MOVE,
                x = event.getHistoricalX(h),
                y = event.getHistoricalY(h),
                pressure = event.getHistoricalPressure(h),
                tiltX = getTiltX(event),
                tiltY = getTiltY(event),
                twist = getTwist(event),
                isEraser = isEraser
            )
        }

        // Current point
        processPoint(
            action = event.action and MotionEvent.ACTION_MASK,
            x = event.x,
            y = event.y,
            pressure = event.pressure,
            tiltX = getTiltX(event),
            tiltY = getTiltY(event),
            twist = getTwist(event),
            isEraser = isEraser
        )

        return true
    }

    private fun processPoint(
        action: Int,
        x: Float, y: Float, pressure: Float,
        tiltX: Int, tiltY: Int, twist: Int,
        isEraser: Boolean
    ) {
        val normX = x / width.toFloat()
        val normY = y / height.toFloat()

        // Clamp pressure: finger touch area gives rough 0.0..1.0 — keep as-is
        val clampedPressure = pressure.coerceIn(0f, 1f)
        onPressureChanged?.invoke(clampedPressure)

        // Determine packet type
        val type: Byte = when {
            isEraser -> when (action) {
                MotionEvent.ACTION_DOWN -> StylusPacket.TYPE_ERASER_DOWN
                MotionEvent.ACTION_MOVE -> StylusPacket.TYPE_ERASER_MOVE
                else                   -> StylusPacket.TYPE_ERASER_UP
            }
            else -> when (action) {
                MotionEvent.ACTION_DOWN -> StylusPacket.TYPE_PEN_DOWN
                MotionEvent.ACTION_MOVE -> StylusPacket.TYPE_PEN_MOVE
                else                   -> StylusPacket.TYPE_PEN_UP
            }
        }

        val packet = StylusPacket.encode(
            type = type,
            x = normX, y = normY,
            pressure = clampedPressure,
            tiltX = tiltX, tiltY = tiltY, twist = twist
        )
        onStylusEvent?.invoke(packet)

        // Draw trail
        drawTrail(action, x, y, clampedPressure, isEraser)

        // Event rate
        trackEventRate()
    }

    private fun drawTrail(action: Int, x: Float, y: Float, pressure: Float, isEraser: Boolean) {
        val canvas = _trailCanvas ?: return
        val paint = if (isEraser) eraserPaint else penPaint

        val strokeWidth = if (isEraser) 40f else (2f + pressure * 28f)
        paint.strokeWidth = strokeWidth
        paint.alpha = if (isEraser) 255 else (180 + (pressure * 75).toInt()).coerceIn(0, 255)

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                isDrawing = true
                lastX = x; lastY = y
                canvas.drawPoint(x, y, paint)
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDrawing) {
                    canvas.drawLine(lastX, lastY, x, y, paint)
                    lastX = x; lastY = y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDrawing = false
            }
        }
        invalidate()
    }

    private fun getTiltX(event: MotionEvent): Int {
        return if (event.axisCount > MotionEvent.AXIS_TILT) {
            (Math.toDegrees(event.getAxisValue(MotionEvent.AXIS_TILT).toDouble()) - 90).toInt()
        } else 0
    }

    private fun getTiltY(event: MotionEvent): Int {
        return if (event.axisCount > MotionEvent.AXIS_ORIENTATION) {
            Math.toDegrees(event.getAxisValue(MotionEvent.AXIS_ORIENTATION).toDouble()).toInt()
        } else 0
    }

    private fun getTwist(event: MotionEvent): Int {
        return if (event.axisCount > MotionEvent.AXIS_ORIENTATION) {
            ((Math.toDegrees(event.getAxisValue(MotionEvent.AXIS_ORIENTATION).toDouble()) + 360) % 360).toInt()
        } else 0
    }

    private fun trackEventRate() {
        eventCount++
        val now = System.currentTimeMillis()
        if (now - lastEventRateTime >= 1000L) {
            onEventRate?.invoke(eventCount)
            eventCount = 0
            lastEventRateTime = now
        }
    }

    fun clearCanvas() {
        _trailCanvas?.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        invalidate()
    }
}
