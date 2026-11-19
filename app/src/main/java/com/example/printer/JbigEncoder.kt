package com.example.printer

import java.io.ByteArrayOutputStream

/**
 * Pure-Kotlin ITU-T T.82 (JBIG1) single-resolution bi-level image encoder.
 * Produces a standard BIH (20-byte header) + QM-arithmetic-coded stripe payload
 * terminated with SDNORM (0xFF 0x02), compatible with Samsung QPDL/SPL comp=0x13.
 */
object JbigEncoder {

    // ITU-T T.82 Table 24 QM-coder probability estimation state table
    private val LSZ = intArrayOf(
        0x5A1D, 0x2586, 0x1114, 0x080B, 0x03D8, 0x01DA, 0x00E5, 0x006F,
        0x0036, 0x001A, 0x000D, 0x0006, 0x0003, 0x0001, 0x5A7F, 0x3F25,
        0x2CF2, 0x207C, 0x17B9, 0x1182, 0x0CEF, 0x09A1, 0x072F, 0x055C,
        0x0406, 0x0303, 0x0240, 0x01B1, 0x0144, 0x00F5, 0x00B7, 0x008A,
        0x0068, 0x004E, 0x003B, 0x002C, 0x0021, 0x0019, 0x0013, 0x000E,
        0x000B, 0x0008, 0x0006, 0x0005, 0x0004, 0x0003, 0x0002, 0x0001,
        0x5A7F, 0x48E8, 0x3A27, 0x2E71, 0x254E, 0x1E0D, 0x1820, 0x1338,
        0x0F68, 0x0C48, 0x09CE, 0x07D9, 0x0646, 0x0505, 0x0402, 0x0334,
        0x028E, 0x020A, 0x01A1, 0x014E, 0x010B, 0x00D6, 0x00AB, 0x0089,
        0x006D, 0x0057, 0x0046, 0x0038, 0x002D, 0x0024, 0x001D, 0x0017,
        0x0012, 0x000F, 0x000C, 0x0009, 0x0007, 0x0006, 0x0005, 0x0004,
        0x0003, 0x0002, 0x0001, 0x5A7F, 0x4E8E, 0x4360, 0x3976, 0x3114,
        0x29DF, 0x23C1, 0x1E8F, 0x1A21, 0x1651, 0x130D, 0x1041, 0x0DDA,
        0x0BC3, 0x09F0, 0x085A, 0x06FC, 0x05D2, 0x04D9, 0x0408, 0x035E,
        0x02D1
    )

    private val NMPS = intArrayOf(
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 13,
        15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28,
        29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42,
        43, 44, 45, 46, 47, 47, 49, 50, 51, 52, 53, 54, 55, 56,
        57, 58, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70,
        71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84,
        85, 86, 87, 88, 89, 90, 90, 92, 93, 94, 95, 96, 97, 98,
        99, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112,
        112
    )

    private val NLPS = intArrayOf(
        1, 14, 16, 18, 20, 23, 25, 28, 30, 33, 35, 9, 10, 12,
        15, 36, 38, 39, 40, 42, 43, 45, 46, 48, 49, 51, 52, 54,
        56, 57, 59, 60, 62, 63, 65, 66, 68, 69, 71, 72, 74, 75,
        77, 78, 80, 81, 83, 84, 49, 86, 88, 89, 91, 92, 94, 95,
        97, 98, 100, 101, 103, 104, 106, 107, 109, 110, 112, 67, 68, 70,
        71, 73, 74, 76, 77, 79, 80, 82, 83, 85, 86, 88, 89, 91,
        92, 94, 95, 97, 98, 100, 101, 92, 93, 94, 95, 96, 97, 98,
        99, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112,
        112
    )

    private val SWITCH_MPS = booleanArrayOf(
        true, false, false, false, false, false, false, false, false, false, false, false, false, false,
        true, false, false, false, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, true, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, true, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false, false, false, false, false,
        false
    )

    /**
     * Encodes a single 1bpp MSB-first monochrome raster band into a JBIG1 BIE stream
     * (20-byte BIH + arithmetic coded stripe + 0xFF 0x02 SDNORM).
     */
    fun encodeStripe(
        packed1bpp: ByteArray,
        widthPx: Int,
        heightPx: Int
    ): ByteArray {
        val widthBytes = (widthPx + 7) / 8
        val out = ByteArrayOutputStream(widthBytes * heightPx / 4 + 64)

        // Write 20-byte Bi-level Image Header (BIH)
        out.write(0) // DL: initial layer
        out.write(0) // D: final layer (single resolution)
        out.write(1) // P: 1 bitplane
        out.write(0) // FILL
        writeIntBE(out, widthPx) // XD
        writeIntBE(out, heightPx) // YD
        writeIntBE(out, heightPx) // L0: lines per stripe
        out.write(0) // MX: default adaptive offset 0 (no AT movement)
        out.write(0) // MY
        out.write(0) // ORDER
        out.write(0) // OPTIONS (standard 3-line template, LRLTWO=0, TPBON=0)

        val encoder = QmEncoder(out)
        val states = IntArray(1024)
        val mps = IntArray(1024)

        fun getBit(y: Int, x: Int): Int {
            if (y < 0 || x < 0 || x >= widthPx) return 0
            val byteVal = packed1bpp[y * widthBytes + (x ushr 3)].toInt() and 0xFF
            return (byteVal ushr (7 - (x and 7))) and 1
        }

        for (y in 0 until heightPx) {
            for (x in 0 until widthPx) {
                // 3-line 10-bit context template (ITU-T T.82 Fig. 10)
                val cx = (getBit(y - 2, x - 1) shl 9) or
                    (getBit(y - 2, x) shl 8) or
                    (getBit(y - 2, x + 1) shl 7) or
                    (getBit(y - 1, x - 2) shl 6) or
                    (getBit(y - 1, x - 1) shl 5) or
                    (getBit(y - 1, x) shl 4) or
                    (getBit(y - 1, x + 1) shl 3) or
                    (getBit(y - 1, x + 2) shl 2) or
                    (getBit(y, x - 2) shl 1) or
                    getBit(y, x - 1)

                val pixel = getBit(y, x)
                val st = states[cx]
                val predicted = mps[cx]
                val lsz = LSZ[st]

                if (pixel == predicted) {
                    encoder.encodeMps(lsz) {
                        states[cx] = NMPS[st]
                    }
                } else {
                    encoder.encodeLps(lsz)
                    if (SWITCH_MPS[st]) {
                        mps[cx] = predicted xor 1
                    }
                    states[cx] = NLPS[st]
                }
            }
        }

        encoder.flush()
        // SDNORM marker (end of stripe, normal)
        out.write(0xFF)
        out.write(0x02)
        return out.toByteArray()
    }

    private fun writeIntBE(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private class QmEncoder(private val out: ByteArrayOutputStream) {
        private var a: Long = 0x10000L
        private var c: Long = 0L
        private var ct: Int = 11
        private var buffer: Int = -1
        private var sc: Int = 0

        inline fun encodeMps(lsz: Int, onRenorm: () -> Unit) {
            a -= lsz.toLong()
            if ((a and 0x8000L) == 0L) {
                if (a < lsz.toLong()) {
                    c = (c + a) and 0xFFFFFFFFL
                    a = lsz.toLong()
                }
                onRenorm()
                renorm()
            }
        }

        fun encodeLps(lsz: Int) {
            a -= lsz.toLong()
            if (a >= lsz.toLong()) {
                c = (c + a) and 0xFFFFFFFFL
                a = lsz.toLong()
            }
            renorm()
        }

        private fun renorm() {
            do {
                a = a shl 1
                c = (c shl 1) and 0xFFFFFFFFL
                ct--
                if (ct == 0) {
                    byteOut()
                }
            } while ((a and 0x8000L) == 0L)
        }

        private fun byteOut() {
            val temp = (c ushr 19) and 0x1FFFL
            if (temp > 0xFFL) {
                if (buffer >= 0) {
                    emitByte((buffer + 1) and 0xFF)
                }
                while (sc > 0) {
                    emitByte(0x00)
                    sc--
                }
                buffer = (temp and 0xFFL).toInt()
            } else if (temp == 0xFFL) {
                sc++
            } else {
                if (buffer >= 0) {
                    emitByte(buffer)
                }
                while (sc > 0) {
                    emitByte(0xFF)
                    sc--
                }
                buffer = (temp and 0xFFL).toInt()
            }
            c = c and 0x7FFFFL
            ct = 8
        }

        private fun emitByte(byteVal: Int) {
            out.write(byteVal)
            if (byteVal == 0xFF) {
                // ITU-T T.82 Section 6.8.2.3 byte stuffing (MARKER_ESC -> MARKER_STUFF)
                out.write(0x00)
            }
        }

        fun flush() {
            // CLEARBITS + FINALWRITES per ITU-T T.82 / libjbig arith_encode_flush
            val temp = (a - 1L + c) and 0xFFFF0000L
            c = if (temp < c) temp + 0x8000L else temp
            c = (c shl ct) and 0xFFFFFFFFL

            if ((c and 0xF8000000L) != 0L) {
                if (buffer >= 0) {
                    emitByte((buffer + 1) and 0xFF)
                } else {
                    emitByte(0x01)
                }
                if ((c and 0x7FFF800L) != 0L) {
                    while (sc > 0) {
                        emitByte(0x00)
                        sc--
                    }
                    emitByte(((c ushr 19) and 0xFFL).toInt())
                    val lowByte = ((c ushr 11) and 0xFFL).toInt()
                    if (lowByte != 0) {
                        emitByte(lowByte)
                    }
                }
            } else {
                if (buffer >= 0) {
                    emitByte(buffer)
                }
                if ((c and 0x7FFF800L) != 0L) {
                    while (sc > 0) {
                        emitByte(0xFF)
                        sc--
                    }
                    emitByte(((c ushr 19) and 0xFFL).toInt())
                    val lowByte = ((c ushr 11) and 0xFFL).toInt()
                    if (lowByte != 0) {
                        emitByte(lowByte)
                    }
                }
            }
        }
    }
}
