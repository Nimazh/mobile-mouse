package com.mobilemouse


/**
 * Binary packet format (16 bytes) for minimum-latency transmission:
 * [type:1][flags:1][x_hi:1][x_lo:1][y_hi:1][y_lo:1][pressure_hi:1][pressure_lo:1]
 * [tilt_x:1][tilt_y:1][twist:1][reserved:1][timestamp_ms: 4 bytes big-endian]
 *
 * type:
 *   0x01 = PEN_DOWN
 *   0x02 = PEN_MOVE
 *   0x03 = PEN_UP
 *   0x04 = PEN_HOVER
 *   0x05 = ERASER_DOWN
 *   0x06 = ERASER_MOVE
 *   0x07 = ERASER_UP
 *
 * x, y: 0..65535 (normalized from view size)
 * pressure: 0..65535 (normalized from 0.0..1.0)
 * tilt_x, tilt_y: signed byte, degrees (-90..90)
 * twist: 0..359 degrees
 */
object StylusPacket {
    const val TYPE_PEN_DOWN: Byte    = 0x01
    const val TYPE_PEN_MOVE: Byte    = 0x02
    const val TYPE_PEN_UP: Byte      = 0x03
    const val TYPE_PEN_HOVER: Byte   = 0x04
    const val TYPE_ERASER_DOWN: Byte = 0x05
    const val TYPE_ERASER_MOVE: Byte = 0x06
    const val TYPE_ERASER_UP: Byte   = 0x07

    const val FLAG_BARREL_BUTTON: Byte = 0x01
    const val FLAG_IN_RANGE: Byte      = 0x02

    fun encode(
        type: Byte,
        flags: Byte = 0,
        x: Float,       // 0.0 .. 1.0
        y: Float,       // 0.0 .. 1.0
        pressure: Float, // 0.0 .. 1.0
        tiltX: Int = 0,  // -90 .. 90 degrees
        tiltY: Int = 0,
        twist: Int = 0,  // 0 .. 359
        timestampMs: Long = System.currentTimeMillis()
    ): ByteArray {
        val buf = ByteArray(16)
        val xInt = (x.coerceIn(0f, 1f) * 65535).toInt()
        val yInt = (y.coerceIn(0f, 1f) * 65535).toInt()
        val pInt = (pressure.coerceIn(0f, 1f) * 65535).toInt()

        buf[0]  = type
        buf[1]  = flags
        buf[2]  = (xInt shr 8).toByte()
        buf[3]  = (xInt and 0xFF).toByte()
        buf[4]  = (yInt shr 8).toByte()
        buf[5]  = (yInt and 0xFF).toByte()
        buf[6]  = (pInt shr 8).toByte()
        buf[7]  = (pInt and 0xFF).toByte()
        buf[8]  = tiltX.coerceIn(-90, 90).toByte()
        buf[9]  = tiltY.coerceIn(-90, 90).toByte()
        buf[10] = (twist % 360).toByte()
        buf[11] = 0 // reserved
        // 4-byte timestamp big-endian
        val ts = (timestampMs and 0xFFFFFFFFL)
        buf[12] = (ts shr 24).toByte()
        buf[13] = (ts shr 16).toByte()
        buf[14] = (ts shr 8).toByte()
        buf[15] = (ts and 0xFF).toByte()
        return buf
    }
}
