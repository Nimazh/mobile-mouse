package com.mobilemouse

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Dual-Mode Digitizer & Laptop Trackpad:
 * - Tablet Mode: 1:1 absolute digitizer, pressure sensitive, active Palm Rejection.
 * - Trackpad Mode: Authentic laptop trackpad with 1-finger move/tap, 2-finger scroll,
 *   2-finger right-click, 3-finger middle-click, and on-screen Left/Right click buttons.
 */
class StylusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onStylusEvent: ((packet: ByteArray) -> Unit)? = null
    var onPressureChanged: ((pressure: Float) -> Unit)? = null
    var onEventRate: ((hz: Int) -> Unit)? = null

    // Mode: false = Tablet (Drawing/Pen), true = Trackpad (Laptop Mouse)
    var isRelativeMode: Boolean = false
        set(value) {
            field = value
            activePointerId = MotionEvent.INVALID_POINTER_ID
            isTouching = false
            invalidate()
        }

    // Palm Rejection configuration (active in Tablet mode)
    var isPalmRejectionEnabled: Boolean = true
    var palmSizeThreshold: Float = 0.28f          // Normalized contact area (0.0 .. 1.0)
    var palmTouchMajorThresholdDp: Float = 36f    // Major axis threshold in DP

    // Full-screen and Trackpad button visibility
    var isFullScreen: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var showTrackpadButtons: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    // Active pointer tracking
    private var activePointerId: Int = MotionEvent.INVALID_POINTER_ID
    private var isTrackingStylus: Boolean = false

    // Trackpad gesture state
    private var pointerCountMax: Int = 0
    private var isTwoFingerScrolling: Boolean = false
    private var lastTwoFingerX: Float = 0f
    private var lastTwoFingerY: Float = 0f
    private var lastRelativeX: Float = 0f
    private var lastRelativeY: Float = 0f
    private var isLeftButtonPressed: Boolean = false
    private var isRightButtonPressed: Boolean = false
    private val buttonHeightDp: Float = 60f

    // Drawing paints
    private val bgPaint = Paint().apply {
        color = Color.parseColor("#0A0E17")
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#1B2333")
        strokeWidth = 1f
    }

    private val touchIndicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#55E94560")
        strokeWidth = 3f
    }

    private val touchDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#E94560")
    }

    // Trackpad styling
    private val trackpadSurfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#121926")
    }

    private val trackpadBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#26344B")
        strokeWidth = 2f
    }

    private val buttonNormalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1A2336")
    }

    private val buttonPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0F3460")
    }

    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textSize = 34f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val gestureHintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#475569")
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    // Touch indicator state
    private var isTouching = false
    private var touchX = 0f
    private var touchY = 0f
    private var touchPressure = 0f

    // Tap detection
    private var downTime = 0L
    private var startX = 0f
    private var startY = 0f
    private var hasMoved = false

    // Event rate tracking
    private var eventCount = 0
    private var lastEventRateTime = System.currentTimeMillis()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!isRelativeMode) {
            // == 1. TABLET MODE (1:1 Clean Digitizer) ==
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

            if (isTouching) {
                val radius = 20f + (touchPressure * 30f)
                canvas.drawCircle(touchX, touchY, radius, touchIndicatorPaint)
                canvas.drawCircle(touchX, touchY, 6f, touchDotPaint)
            }
        } else {
            // == 2. LAPTOP TRACKPAD MODE ==
            val density = resources.displayMetrics.density
            val btnHeight = if (showTrackpadButtons) buttonHeightDp * density else 0f
            val padMargin = if (isFullScreen) 0f else (12f * density)
            val trackpadBottom = if (showTrackpadButtons) {
                height - btnHeight - (if (isFullScreen) (6f * density) else (padMargin * 1.5f))
            } else {
                height.toFloat()
            }

            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

            // Trackpad main surface
            val padRect = RectF(padMargin, padMargin, width - padMargin, trackpadBottom)
            if (isFullScreen && !showTrackpadButtons) {
                canvas.drawRect(padRect, trackpadSurfacePaint)
            } else {
                canvas.drawRoundRect(padRect, 24f, 24f, trackpadSurfacePaint)
                canvas.drawRoundRect(padRect, 24f, 24f, trackpadBorderPaint)
            }

            // Center guide dot & gesture hints
            val cx = width / 2f
            canvas.drawCircle(cx, padRect.centerY(), 5f, gridPaint)
            val hintText = if (showTrackpadButtons) {
                "1-Finger Move | 2-Finger Scroll & Right Click"
            } else {
                "Full Screen Trackpad: Tap=Click | 2-Finger Scroll & Right Click"
            }
            canvas.drawText(hintText, cx, padRect.centerY() + 48f, gestureHintPaint)

            // Bottom Buttons: Left and Right click
            if (showTrackpadButtons) {
                val btnTop = trackpadBottom + (if (isFullScreen) (4f * density) else (padMargin * 0.5f))
                val btnBottom = height - (if (isFullScreen) (4f * density) else padMargin)
                val btnSide = if (isFullScreen) (8f * density) else padMargin

                // Left Button
                val leftBtnRect = RectF(btnSide, btnTop, cx - (4f * density), btnBottom)
                val leftPaint = if (isLeftButtonPressed) buttonPressedPaint else buttonNormalPaint
                canvas.drawRoundRect(leftBtnRect, 18f, 18f, leftPaint)
                canvas.drawRoundRect(leftBtnRect, 18f, 18f, trackpadBorderPaint)
                canvas.drawText("LEFT CLICK", leftBtnRect.centerX(), leftBtnRect.centerY() + 11f, buttonTextPaint)

                // Right Button
                val rightBtnRect = RectF(cx + (4f * density), btnTop, width - btnSide, btnBottom)
                val rightPaint = if (isRightButtonPressed) buttonPressedPaint else buttonNormalPaint
                canvas.drawRoundRect(rightBtnRect, 18f, 18f, rightPaint)
                canvas.drawRoundRect(rightBtnRect, 18f, 18f, trackpadBorderPaint)
                canvas.drawText("RIGHT CLICK", rightBtnRect.centerX(), rightBtnRect.centerY() + 11f, buttonTextPaint)
            }

            // Active touch feedback
            if (isTouching) {
                canvas.drawCircle(touchX, touchY, 26f, touchIndicatorPaint)
                canvas.drawCircle(touchX, touchY, 8f, touchDotPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return if (isRelativeMode) {
            onTouchTrackpad(event)
        } else {
            onTouchTablet(event)
        }
    }

    // =========================================================================
    // LAPTOP TRACKPAD GESTURE ENGINE
    // =========================================================================
    private fun onTouchTrackpad(event: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        val btnHeight = if (showTrackpadButtons) buttonHeightDp * density else 0f
        val padMargin = if (isFullScreen) 0f else (12f * density)
        val trackpadBottom = if (showTrackpadButtons) {
            height - btnHeight - (if (isFullScreen) (6f * density) else (padMargin * 1.5f))
        } else {
            height.toFloat()
        }
        val cx = width / 2f

        // Check if initial touch lands on Bottom Buttons
        if (showTrackpadButtons && event.actionMasked == MotionEvent.ACTION_DOWN && event.y > trackpadBottom) {
            if (event.x < cx) {
                isLeftButtonPressed = true
                sendTapClick()
            } else {
                isRightButtonPressed = true
                sendRightTapClick()
            }
            invalidate()
            return true
        }

        if (isLeftButtonPressed || isRightButtonPressed) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                isLeftButtonPressed = false
                isRightButtonPressed = false
                invalidate()
            }
            return true
        }

        // Main Trackpad Surface Gestures
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerCountMax = 1
                isTwoFingerScrolling = false
                downTime = System.currentTimeMillis()
                startX = event.x
                startY = event.y
                touchX = event.x
                touchY = event.y
                lastRelativeX = event.x
                lastRelativeY = event.y
                isTouching = true
                hasMoved = false
                invalidate()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                pointerCountMax = max(pointerCountMax, event.pointerCount)
                if (event.pointerCount == 2) {
                    lastTwoFingerX = (event.getX(0) + event.getX(1)) / 2f
                    lastTwoFingerY = (event.getY(0) + event.getY(1)) / 2f
                    isTwoFingerScrolling = false
                }
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                pointerCountMax = max(pointerCountMax, event.pointerCount)

                if (event.pointerCount >= 2) {
                    // Two-finger scroll gesture
                    val midX = (event.getX(0) + event.getX(1)) / 2f
                    val midY = (event.getY(0) + event.getY(1)) / 2f
                    val dx = midX - lastTwoFingerX
                    val dy = midY - lastTwoFingerY

                    if (abs(dx) > 3f || abs(dy) > 3f) {
                        isTwoFingerScrolling = true
                        // Natural scroll: swipe up = scroll up (+dy), swipe down = scroll down (-dy)
                        val normDx = (dx / width.toFloat()).coerceIn(-0.5f, 0.5f)
                        val normDy = (-dy / height.toFloat()).coerceIn(-0.5f, 0.5f)
                        sendScroll(normDx, normDy)
                        lastTwoFingerX = midX
                        lastTwoFingerY = midY
                    }
                    touchX = midX
                    touchY = midY
                    invalidate()
                } else if (event.pointerCount == 1 && !isTwoFingerScrolling) {
                    // Single-finger relative mouse movement
                    val curX = event.x
                    val curY = event.y
                    val dist = hypot((curX - startX).toDouble(), (curY - startY).toDouble()).toFloat()
                    if (dist > 8f) {
                        hasMoved = true
                    }

                    touchX = curX
                    touchY = curY
                    processPoint(
                        action = MotionEvent.ACTION_MOVE,
                        x = curX,
                        y = curY,
                        pressure = 1.0f,
                        tiltX = 0,
                        tiltY = 0,
                        twist = 0,
                        isEraser = false
                    )
                    lastRelativeX = curX
                    lastRelativeY = curY
                    invalidate()
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouching = false
                val elapsed = System.currentTimeMillis() - downTime

                if (pointerCountMax == 3 && elapsed < 350 && !hasMoved) {
                    // Three-finger tap -> Middle Click
                    sendMiddleTapClick()
                } else if (pointerCountMax == 2 && elapsed < 350 && !isTwoFingerScrolling) {
                    // Two-finger tap -> Right Click
                    sendRightTapClick()
                } else if (pointerCountMax == 1 && elapsed < 250 && !hasMoved) {
                    // Single-finger tap -> Left Click
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
                    isEraser = false
                )

                pointerCountMax = 0
                isTwoFingerScrolling = false
                invalidate()
            }
        }
        return true
    }

    // =========================================================================
    // GRAPHICS TABLET ENGINE (With 3-Layer Hybrid Palm Rejection)
    // =========================================================================
    private fun onTouchTablet(event: MotionEvent): Boolean {
        if (hasActiveStylus(event)) {
            isTrackingStylus = true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
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
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) {
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

    private fun isPointerPalm(event: MotionEvent, pointerIndex: Int): Boolean {
        if (!isPalmRejectionEnabled) return false

        val toolType = event.getToolType(pointerIndex)
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
            return false
        }

        if (isTrackingStylus && toolType == MotionEvent.TOOL_TYPE_FINGER) {
            return true
        }

        val size = event.getSize(pointerIndex)
        if (size > palmSizeThreshold) {
            return true
        }

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

    private fun finishTouch(x: Float, y: Float, isEraser: Boolean) {
        isTouching = false
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
        val packet = StylusPacket.encode(
            type = StylusPacket.TYPE_MOUSE_TAP,
            flags = StylusPacket.FLAG_RELATIVE_MODE,
            x = (touchX / width.toFloat()).coerceIn(0f, 1f),
            y = (touchY / height.toFloat()).coerceIn(0f, 1f),
            pressure = 1.0f
        )
        onStylusEvent?.invoke(packet)
        trackEventRate()
    }

    private fun sendRightTapClick() {
        val packet = StylusPacket.encode(
            type = StylusPacket.TYPE_MOUSE_RIGHT_TAP,
            flags = StylusPacket.FLAG_RELATIVE_MODE,
            x = (touchX / width.toFloat()).coerceIn(0f, 1f),
            y = (touchY / height.toFloat()).coerceIn(0f, 1f),
            pressure = 1.0f
        )
        onStylusEvent?.invoke(packet)
        trackEventRate()
    }

    private fun sendMiddleTapClick() {
        val packet = StylusPacket.encode(
            type = StylusPacket.TYPE_MOUSE_MIDDLE_TAP,
            flags = StylusPacket.FLAG_RELATIVE_MODE,
            x = (touchX / width.toFloat()).coerceIn(0f, 1f),
            y = (touchY / height.toFloat()).coerceIn(0f, 1f),
            pressure = 1.0f
        )
        onStylusEvent?.invoke(packet)
        trackEventRate()
    }

    private fun sendScroll(dx: Float, dy: Float) {
        val encX = (0.5f + (dx * 0.5f)).coerceIn(0f, 1f)
        val encY = (0.5f + (dy * 0.5f)).coerceIn(0f, 1f)

        val packet = StylusPacket.encode(
            type = StylusPacket.TYPE_MOUSE_SCROLL,
            flags = StylusPacket.FLAG_RELATIVE_MODE,
            x = encX,
            y = encY,
            pressure = 1.0f
        )
        onStylusEvent?.invoke(packet)
        trackEventRate()
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
