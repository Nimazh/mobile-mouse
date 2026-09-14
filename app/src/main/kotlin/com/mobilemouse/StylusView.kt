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

    // Palm Rejection configuration
    var isPalmRejectionEnabled: Boolean = true
    var palmSizeThreshold: Float = 0.28f          // Normalized contact area (0.0 .. 1.0)
    var palmTouchMajorThresholdDp: Float = 36f    // Major axis threshold in DP

    // Active pointer tracking
    private var activePointerId: Int = MotionEvent.INVALID_POINTER_ID
    private var isTrackingStylus: Boolean = false

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

    private fun isPointerPalm(event: MotionEvent, pointerIndex: Int): Boolean {
        if (!isPalmRejectionEnabled) return false

        val toolType = event.getToolType(pointerIndex)
        // Active stylus or eraser is never a palm
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
            return false
        }

        // If an active stylus is present, ignore all finger touches completely
        if (isTrackingStylus && toolType == MotionEvent.TOOL_TYPE_FINGER) {
            return true
        }

        // Contact size check (palms have a much larger touch area than a fingertip)
        val size = event.getSize(pointerIndex)
        if (size > palmSizeThreshold) {
            return true
        }

        // Major touch axis check
        val major = event.getTouchMajor(pointerIndex)
        val density = resources.displayMetrics.density
        if (major > (palmTouchMajorThresholdDp * density)) {
            return true
        }

        return false
    }

    private fun hasActiveStylus(event: MotionEvent): Boolean {
        for (i in 0 until event.pointerCount) {
            val toolType = event.getToolType(i)
            if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
                return true
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Check if an active stylus is detected anywhere in this touch event
        if (hasActiveStylus(event)) {
            isTrackingStylus = true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // If palm rejection is enabled and this touch is a palm, ignore it
                if (isPointerPalm(event, 0)) {
                    return true
                }

                activePointerId = event.getPointerId(0)
                isTouching = true
                touchX = event.x
                touchY = event.y
                touchPressure = event.pressure
                downTime = System.currentTimeMillis()
                startX = event.x
                startY = event.y
                hasMoved = false

                val isEraser = event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
                processPoint(
                    action = MotionEvent.ACTION_DOWN,
                    x = event.x,
                    y = event.y,
                    pressure = event.pressure,
                    tiltX = getTiltX(event, 0),
                    tiltY = getTiltY(event, 0),
                    twist = getTwist(event, 0),
                    isEraser = isEraser
                )
                invalidate()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val actionIndex = event.actionIndex
                val pointerId = event.getPointerId(actionIndex)
                val toolType = event.getToolType(actionIndex)

                if (activePointerId == MotionEvent.INVALID_POINTER_ID) {
                    // Initial touch was rejected as palm; check if this second pointer is the real finger/pen
                    if (!isPointerPalm(event, actionIndex)) {
                        activePointerId = pointerId
                        isTouching = true
                        touchX = event.getX(actionIndex)
                        touchY = event.getY(actionIndex)
                        touchPressure = event.getPressure(actionIndex)
                        downTime = System.currentTimeMillis()
                        startX = touchX
                        startY = touchY
                        hasMoved = false

                        val isEraser = toolType == MotionEvent.TOOL_TYPE_ERASER
                        processPoint(
                            action = MotionEvent.ACTION_DOWN,
                            x = touchX,
                            y = touchY,
                            pressure = touchPressure,
                            tiltX = getTiltX(event, actionIndex),
                            tiltY = getTiltY(event, actionIndex),
                            twist = getTwist(event, actionIndex),
                            isEraser = isEraser
                        )
                        invalidate()
                    }
                } else {
                    // We already have an active pointer.
                    // If the new pointer is an active stylus and current pointer is finger, upgrade to stylus!
                    if (isPalmRejectionEnabled && (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER)) {
                        val currentIdx = event.findPointerIndex(activePointerId)
                        if (currentIdx != -1 && event.getToolType(currentIdx) == MotionEvent.TOOL_TYPE_FINGER) {
                            activePointerId = pointerId
                            touchX = event.getX(actionIndex)
                            touchY = event.getY(actionIndex)
                            touchPressure = event.getPressure(actionIndex)
                            invalidate()
                        }
                    }
                    // Otherwise, ignore secondary touches (palm resting down while drawing)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) {
                    // Search for a valid non-palm pointer
                    for (i in 0 until event.pointerCount) {
                        if (!isPointerPalm(event, i)) {
                            activePointerId = event.getPointerId(i)
                            break
                        }
                    }
                    if (activePointerId == MotionEvent.INVALID_POINTER_ID) return true
                }

                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex == -1) return true

                // If active pointer itself has expanded into a palm (hand rested), suppress it
                if (isPointerPalm(event, pointerIndex)) {
                    return true
                }

                val curX = event.getX(pointerIndex)
                val curY = event.getY(pointerIndex)
                val curP = event.getPressure(pointerIndex)
                val isEraser = event.getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_ERASER

                isTouching = true
                touchX = curX
                touchY = curY
                touchPressure = curP

                val dist = hypot((curX - startX).toDouble(), (curY - startY).toDouble()).toFloat()
                if (dist > 10f) {
                    hasMoved = true
                }

                // Process historical points for the active pointer
                val historySize = event.historySize
                for (h in 0 until historySize) {
                    processPoint(
                        action = MotionEvent.ACTION_MOVE,
                        x = event.getHistoricalX(pointerIndex, h),
                        y = event.getHistoricalY(pointerIndex, h),
                        pressure = event.getHistoricalPressure(pointerIndex, h),
                        tiltX = getTiltX(event, pointerIndex),
                        tiltY = getTiltY(event, pointerIndex),
                        twist = getTwist(event, pointerIndex),
                        isEraser = isEraser
                    )
                }

                processPoint(
                    action = MotionEvent.ACTION_MOVE,
                    x = curX,
                    y = curY,
                    pressure = curP,
                    tiltX = getTiltX(event, pointerIndex),
                    tiltY = getTiltY(event, pointerIndex),
                    twist = getTwist(event, pointerIndex),
                    isEraser = isEraser
                )
                invalidate()
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val actionIndex = event.actionIndex
                val liftedId = event.getPointerId(actionIndex)

                if (liftedId == activePointerId) {
                    // Active pointer was lifted. Look for another valid pointer, or end stroke
                    var nextValidId = MotionEvent.INVALID_POINTER_ID
                    var nextValidIdx = -1

                    for (i in 0 until event.pointerCount) {
                        if (i != actionIndex && !isPointerPalm(event, i)) {
                            nextValidId = event.getPointerId(i)
                            nextValidIdx = i
                            break
                        }
                    }

                    if (nextValidId != MotionEvent.INVALID_POINTER_ID && nextValidIdx != -1) {
                        activePointerId = nextValidId
                        touchX = event.getX(nextValidIdx)
                        touchY = event.getY(nextValidIdx)
                        touchPressure = event.getPressure(nextValidIdx)
                    } else {
                        finishTouch(event.getX(actionIndex), event.getY(actionIndex), event.getToolType(actionIndex) == MotionEvent.TOOL_TYPE_ERASER)
                        activePointerId = MotionEvent.INVALID_POINTER_ID
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val pointerIndex = if (activePointerId != MotionEvent.INVALID_POINTER_ID) {
                    event.findPointerIndex(activePointerId).takeIf { it != -1 } ?: 0
                } else 0

                val isEraser = event.getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_ERASER
                finishTouch(event.getX(pointerIndex), event.getY(pointerIndex), isEraser)
                activePointerId = MotionEvent.INVALID_POINTER_ID
                isTrackingStylus = false
            }
        }

        return true
    }

    private fun finishTouch(x: Float, y: Float, isEraser: Boolean) {
        isTouching = false
        val elapsed = System.currentTimeMillis() - downTime

        // In relative mode: quick tap without movement = click
        if (isRelativeMode && !hasMoved && elapsed < 250) {
            sendTapClick()
        }

        processPoint(
            action = MotionEvent.ACTION_UP,
            x = x,
            y = y,
            pressure = 0f,
            tiltX = 0,
            tiltY = 0,
            twist = 0,
            isEraser = isEraser
        )
        invalidate()
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
        val flags: Byte = StylusPacket.FLAG_RELATIVE_MODE
        val packet = StylusPacket.encode(
            type = StylusPacket.TYPE_MOUSE_TAP,
            flags = flags,
            x = (touchX / width.toFloat()).coerceIn(0f, 1f),
            y = (touchY / height.toFloat()).coerceIn(0f, 1f),
            pressure = 1.0f
        )
        onStylusEvent?.invoke(packet)
    }

    private fun getTiltX(event: MotionEvent, pointerIndex: Int = 0): Int {
        val tilt = event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex)
        return if (tilt != 0f) {
            (Math.toDegrees(tilt.toDouble()) - 90).toInt().coerceIn(-90, 90)
        } else 0
    }

    private fun getTiltY(event: MotionEvent, pointerIndex: Int = 0): Int {
        val orient = event.getAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex)
        return if (orient != 0f) {
            Math.toDegrees(orient.toDouble()).toInt().coerceIn(-90, 90)
        } else 0
    }

    private fun getTwist(event: MotionEvent, pointerIndex: Int = 0): Int {
        val orient = event.getAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex)
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
