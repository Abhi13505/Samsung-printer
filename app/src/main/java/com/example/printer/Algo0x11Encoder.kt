package com.example.printer

/**
 * Pure-Kotlin port of OpenPrinting/splix Algo0x11 (Samsung SPL/QPDL v3 monochrome
 * LZS/pointer-table compression used by Samsung SCX-3200 / ML-1660 printers).
 *
 * Outputs Little-Endian host-order metadata (`uncompSize: uint32`, `ptrArray[64]: uint16`)
 * matching SpliX `BandPlane::Dependant` on ARM/x86 CPUs, paired with sub-header magic
 * `0x09ABCDEF` in Little-Endian (`EF CD AB 09`).
 */
object Algo0x11Encoder {

    private const val COMPRESS_SAMPLE_RATE = 0x800      // 2048
    private const val TABLE_PTR_SIZE = 0x40             // 64
    private const val MAX_UNCOMPRESSED_BYTES = 0x80     // 128
    private const val MAX_COMPRESSED_BYTES = 0x202      // 514
    private const val MIN_COMPRESSED_BYTES = 0x2        // 2
    private const val COMPRESSION_FLAG = 0x80           // 128

    fun isBandEmpty(
        packedRowMajor: ByteArray,
        widthBytes: Int,
        actualBandHeight: Int
    ): Boolean {
        val limit = minOf(packedRowMajor.size, widthBytes * actualBandHeight)
        for (i in 0 until limit) {
            if (packedRowMajor[i] != 0.toByte()) return false
        }
        return true
    }

    /**
     * Prepares a row-major 1bpp MSB-first band (0=white, 1=black) for SpliX Algo0x11:
     * 1. Pads any trailing rows of a final partial band up to [fixedBandHeight] (128) with 0 (white).
     * 2. Transposes bytes from row-major to column-major (`band[x * bandHeight + y]`)
     *    matching `Algo0x11::reverseLineColumn() == true`.
     * 3. Bitwise-inverts every byte (`~b`, 0xFF=white, 0x00=black)
     *    matching `Algo0x11::inverseByte() == true`.
     */
    fun prepareColumnMajorInvertedBand(
        packedRowMajor: ByteArray,
        widthBytes: Int,
        actualBandHeight: Int,
        fixedBandHeight: Int = 128
    ): ByteArray {
        require(widthBytes > 0 && fixedBandHeight > 0) { "Invalid band dimensions" }
        val out = ByteArray(widthBytes * fixedBandHeight) { 0xFF.toByte() }
        val rows = minOf(actualBandHeight, fixedBandHeight)
        for (y in 0 until rows) {
            val srcRow = y * widthBytes
            if (srcRow + widthBytes > packedRowMajor.size) break
            for (x in 0 until widthBytes) {
                out[x * fixedBandHeight + y] = (packedRowMajor[srcRow + x].toInt() xor 0xFF).toByte()
            }
        }
        return out
    }

    fun compress(data: ByteArray): ByteArray {
        val size = data.size
        require(size > 0) { "Cannot compress empty band data with Algo0x11" }

        val ptrArray = lookupBestOccurs(data, size)

        var uncompSize = 0
        for (i in 0 until TABLE_PTR_SIZE) {
            if (ptrArray[i] > uncompSize) {
                uncompSize = ptrArray[i]
            }
        }
        if (uncompSize > MAX_UNCOMPRESSED_BYTES) {
            uncompSize = MAX_UNCOMPRESSED_BYTES
        }
        if (uncompSize > size) {
            uncompSize = size
        }

        // Worst-case size: 132B header + uncompSize + ~1.02 * remaining bytes
        var out = ByteArray(maxOf(size + 512 + (size ushr 6), 512))
        fun ensureCapacity(needed: Int) {
            if (needed > out.size) {
                out = out.copyOf(maxOf(out.size * 2, needed + 256))
            }
        }

        // Bytes 0..3: uncompSize (uint32 Little-Endian)
        out[0] = (uncompSize and 0xFF).toByte()
        out[1] = ((uncompSize ushr 8) and 0xFF).toByte()
        out[2] = ((uncompSize ushr 16) and 0xFF).toByte()
        out[3] = ((uncompSize ushr 24) and 0xFF).toByte()

        // Bytes 4..131: ptrArray[64] (uint16 Little-Endian)
        var w = 4
        for (i in 0 until TABLE_PTR_SIZE) {
            val v = ptrArray[i]
            out[w] = (v and 0xFF).toByte()
            out[w + 1] = ((v ushr 8) and 0xFF).toByte()
            w += 2
        }

        // Initial uncompressed prefix bytes
        for (rIdx in 0 until uncompSize) {
            out[w++] = data[rIdx]
        }

        var r = uncompSize
        var rawDataCounter = 0
        var rawDataCounterPtr = 0

        while (true) {
            val remaining = size - r
            val maxCompSize = if (remaining > MAX_COMPRESSED_BYTES) MAX_COMPRESSED_BYTES else remaining

            if (maxCompSize == 0) {
                if (rawDataCounter > 0) {
                    out[rawDataCounterPtr] = (rawDataCounter - 1).toByte()
                }
                break
            }

            if (maxCompSize >= 2) {
                var bestCompCounter = 0
                var bestPtr = 0
                val firstTarget = data[r]

                for (i in 0 until TABLE_PTR_SIZE) {
                    val offset = ptrArray[i]
                    if (offset > r) continue
                    val rTmp = r - offset
                    if (data[rTmp] != firstTarget) continue

                    var counter = 1
                    while (counter < maxCompSize && data[r + counter] == data[rTmp + counter]) {
                        counter++
                    }
                    if (counter > bestCompCounter) {
                        bestCompCounter = counter
                        bestPtr = i
                        if (counter == maxCompSize) break
                    }
                }

                if (bestCompCounter > MIN_COMPRESSED_BYTES) {
                    r += bestCompCounter
                    val lenCode = bestCompCounter - 3
                    ensureCapacity(w + 2)
                    out[w] = (COMPRESSION_FLAG or (lenCode and 0x7F)).toByte()
                    out[w + 1] = (((lenCode ushr 1) and 0xC0) or (bestPtr and 0x3F)).toByte()
                    w += 2
                    if (rawDataCounter > 0) {
                        out[rawDataCounterPtr] = (rawDataCounter - 1).toByte()
                        rawDataCounter = 0
                    }
                    continue
                }
            }

            rawDataCounter++
            if (rawDataCounter == 1) {
                ensureCapacity(w + 2)
                rawDataCounterPtr = w
                w++
            } else if (rawDataCounter == MAX_UNCOMPRESSED_BYTES) {
                ensureCapacity(w + 1)
                out[rawDataCounterPtr] = 0x7F.toByte()
                rawDataCounter = 0
            } else {
                ensureCapacity(w + 1)
            }
            out[w++] = data[r++]
        }

        return out.copyOf(w)
    }

    private fun lookupBestOccurs(data: ByteArray, size: Int): IntArray {
        val counts = IntArray(COMPRESS_SAMPLE_RATE)
        var i = COMPRESS_SAMPLE_RATE
        while (i < size) {
            val b = data[i]
            for (j in 1 until COMPRESS_SAMPLE_RATE) {
                if (data[i - j] == b) {
                    counts[j - 1]++
                }
            }
            i += COMPRESS_SAMPLE_RATE
        }

        val indices = Array(COMPRESS_SAMPLE_RATE) { it }
        indices.sortWith { a, b -> counts[b].compareTo(counts[a]) }

        val ptrArray = IntArray(TABLE_PTR_SIZE)
        var oneIsPresent = false
        for (k in 0 until TABLE_PTR_SIZE) {
            val offset = indices[k] + 1
            ptrArray[k] = offset
            if (offset == 1) {
                oneIsPresent = true
            }
        }
        if (!oneIsPresent) {
            ptrArray[TABLE_PTR_SIZE - 1] = 1
        }
        return ptrArray
    }
}
