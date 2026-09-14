package com.mobilemouse

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Clean digitizer pad:
 * - NO lines are drawn or kept on screen (behaves like a real graphics tablet)
 * - Shows an optional subtle touch indicator only while finger is down
 * - Supports both Relative (Touchpad) and Absolute (Tablet) modes
 */
class StylusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onStylusEvent: ((packet: ByteArray) -> Unit)? = null
    var onPressureChanged: ((pressure: Float) -> Unit)? = null
    var onEventRate: ((hz: Int) -> Unit)? = null

    // Mode: true = Relative (Touchpad/Mouse), false = Absolute (Graphics Tablet)
    var isRelativeMode: Boolean = false

    // Drawing paints (UI only, NO persistent trails)
    private val bgPaint = Paint().apply {
        color = Color.parseColor("#0D1117")
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#1F2937")
        strokeWidth = 1f
    }

    private val touchIndicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#44E94560")
        strokeWidth = 3f
    }

    private val touchDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#E94560")
    }

    // Touch indicator state
    private var isTouching = false
    private var touchX = 0f
    private var touchY = 0f
    private var touchPressure = 0f

    // Tap detection for relative mode
    private var downTime = 0L
    private var startX = 0f
    private var startY = 0f
    private var hasMoved = false

    // Event rate tracking
    private var eventCount = 0
    private var lastEventRateTime = System.currentTimeMillis()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Subtle tablet crosshair grid
        val step = 100f
        var x = step
        while (x < width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            x += step
        }
        var y = step
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            y += step
        }

        // Active touch indicator (only visible while finger is on screen, NO persistent lines!)
        if (isTouching) {
            val radius = 20f + (touchPressure * 30f)
            canvas.drawCircle(touchX, touchY, radius, touchIndicatorPaint)
            canvas.drawCircle(touchX, touchY, 6f, touchDotPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)
        val isEraser = toolType == MotionEvent.TOOL_TYPE_ERASER
        val isStylusOrFinger = toolType == MotionEvent.TOOL_TYPE_STYLUS ||
                               toolType == MotionEvent.TOOL_TYPE_FINGER

        if (!isStylusOrFinger && !isEraser) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isTouching = true
                touchX = event.x
                touchY = event.y
                touchPressure = event.pressure
                downTime = System.currentTimeMillis()
                startX = event.x
                startY = event.y
                hasMoved = false

                processPoint(
                    action = MotionEvent.ACTION_DOWN,
                    x = event.x,
                    y = event.y,
                    pressure = event.pressure,
                    tiltX = getTiltX(event),
                    tiltY = getTiltY(event),
                    twist = getTwist(event),
                    isEraser = isEraser
                )
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                isTouching = true
                touchX = event.x
                touchY = event.y
                touchPressure = event.pressure

                val dist = hypot((event.x - startX).toDouble(), (event.y - startY).toDouble()).toFloat()
                if (dist > 10f) {
                    hasMoved = true
                }

                // Process historical points for smoothness
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

                processPoint(
                    action = MotionEvent.ACTION_MOVE,
                    x = event.x,
                    y = event.y,
                    pressure = event.pressure,
                    tiltX = getTiltX(event),
                    tiltY = getTiltY(event),
                    twist = getTwist(event),
                    isEraser = isEraser
                )
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouching = false
                val elapsed = System.currentTimeMillis() - downTime

                // In relative mode: quick tap without movement = click
                if (isRelativeMode && !hasMoved && elapsed < 250) {
                    sendTapClick()
                }

                processPoint(
                    action = MotionEvent.ACTION_UP,
                    x = event.x,
                    y = event.y,
                    pressure = 0f,
                    tiltX = 0,
                    tiltY = 0,
                    twist = 0,
                    isEraser = isEraser
                )
                invalidate()
            }
        }

        return true
    }

    private fun processPoint(
        action: Int,
        x: Float, y: Float, pressure: Float,
        tiltX: Int, tiltY: Int, twist: Int,
        isEraser: Boolean
    ) {
        val normX = (x / width.toFloat()).coerceIn(0f, 1f)
        val normY = (y / height.toFloat()).coerceIn(0f, 1f)
        val clampedPressure = pressure.coerceIn(0f, 1f)

        onPressureChanged?.invoke(clampedPressure)

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

        var flags: Byte = 0
        if (isRelativeMode) {
            flags = (flags.toInt() or StylusPacket.FLAG_RELATIVE_MODE.toInt()).toByte()
        }

        val packet = StylusPacket.encode(
            type = type,
            flags = flags,
            x = normX,
            y = normY,
            pressure = clampedPressure,
            tiltX = tiltX,
            tiltY = tiltY,
            twist = twist
        )
        onStylusEvent?.invoke(packet)

        trackEventRate()
    }

    private fun sendTapClick() {
        var flags: Byte = StylusPacket.FLAG_RELATIVE_MODE
        val packet = StylusPacket.encode(
            type = StylusPacket.TYPE_MOUSE_TAP,
            flags = flags,
            x = (touchX / width.toFloat()).coerceIn(0f, 1f),
            y = (touchY / height.toFloat()).coerceIn(0f, 1f),
            pressure = 1.0f
        )
        onStylusEvent?.invoke(packet)
    }

    private fun getTiltX(event: MotionEvent): Int {
        val tilt = event.getAxisValue(MotionEvent.AXIS_TILT)
        return if (tilt != 0f) {
            (Math.toDegrees(tilt.toDouble()) - 90).toInt().coerceIn(-90, 90)
        } else 0
    }

    private fun getTiltY(event: MotionEvent): Int {
        val orient = event.getAxisValue(MotionEvent.AXIS_ORIENTATION)
        return if (orient != 0f) {
            Math.toDegrees(orient.toDouble()).toInt().coerceIn(-90, 90)
        } else 0
    }

    private fun getTwist(event: MotionEvent): Int {
        val orient = event.getAxisValue(MotionEvent.AXIS_ORIENTATION)
        return if (orient != 0f) {
            ((Math.toDegrees(orient.toDouble()) + 360) % 360).toInt()
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
}
