package com.example

import com.example.model.ImagesPerPage
import com.example.model.PageRangeMode
import com.example.model.PageRangeParser
import com.example.model.PaperSize
import com.example.model.PhotoGapPreset
import com.example.model.PhotoLayoutGeometry
import com.example.model.PhotoLayoutOptions
import com.example.model.PhotoMarginPreset
import com.example.model.PhotoPosition
import com.example.model.PhotoSizePreset
import com.example.model.PrintOrientation
import com.example.model.PrintQuality
import com.example.model.PrintSettings
import com.example.model.QpdlDialect
import com.example.printer.JbigEncoder
import com.example.printer.SplQpdlEncoder
import com.example.printer.UsbPrinterManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintPipelineUnitTest {

    @Test
    fun `usb printer manager uses 60s timeout, retries, and spec-compliant GET_DEVICE_ID wIndex`() {
        assertEquals(60_000, UsbPrinterManager.USB_TIMEOUT_MS)
        assertEquals(3, UsbPrinterManager.MAX_BULK_RETRIES)
        // USB Printer Class 1.1 §4.2.1: interface in high byte, alternate setting in low byte
        assertEquals(0x0000, UsbPrinterManager.buildGetDeviceIdIndex(interfaceId = 0, alternateSetting = 0))
        assertEquals(0x0100, UsbPrinterManager.buildGetDeviceIdIndex(interfaceId = 1, alternateSetting = 0))
        assertEquals(0x0203, UsbPrinterManager.buildGetDeviceIdIndex(interfaceId = 2, alternateSetting = 3))
    }

    @Test
    fun `page range parser handles ranges odd even current and clamping`() {
        assertEquals(listOf(0, 1, 2, 4), PageRangeParser.parseZeroIndexed("1-3, 5", 8))
        assertEquals(listOf(0, 2, 3), PageRangeParser.parseZeroIndexed("3-4, 1, 3", 5))
        assertEquals(emptyList<Int>(), PageRangeParser.parseZeroIndexed("4-2", 5))
        assertEquals(emptyList<Int>(), PageRangeParser.parseZeroIndexed("abc", 5))
        assertEquals(listOf(3, 4), PageRangeParser.parseZeroIndexed("4-99", 5))

        // Odd and Even page modes
        assertEquals(listOf(0, 2, 4), PageRangeParser.resolvePages(PageRangeMode.ODD, 6, 0, ""))
        assertEquals(listOf(1, 3, 5), PageRangeParser.resolvePages(PageRangeMode.EVEN, 6, 0, ""))
        assertEquals(listOf(0, 2, 4), PageRangeParser.resolvePages(PageRangeMode.ODD, 5, 0, ""))
        assertEquals(listOf(1, 3), PageRangeParser.resolvePages(PageRangeMode.EVEN, 5, 0, ""))
        assertEquals(listOf(2), PageRangeParser.resolvePages(PageRangeMode.CURRENT, 5, 2, ""))
        assertEquals(listOf(0, 1, 2, 3), PageRangeParser.resolvePages(PageRangeMode.ALL, 4, 0, ""))
    }

    @Test
    fun `photo layout geometry computes auto grid, margins, and 9-position alignment accurately`() {
        val passportGrid = PhotoLayoutGeometry.computeSheetPlan(
            paperSize = PaperSize.A4,
            orientation = PrintOrientation.PORTRAIT,
            layout = PhotoLayoutOptions(
                imagesPerPage = ImagesPerPage.AUTO_GRID,
                gapPreset = PhotoGapPreset.TIGHT,
                photoSize = PhotoSizePreset.PASSPORT,
                marginPreset = PhotoMarginPreset.NARROW
            ),
            primaryPhotoIsLandscape = false
        )
        // A4 printable = 200x287 mm; passport = 35x45 mm + 2.5 mm gap -> 5 cols x 6 rows = 30 slots
        assertEquals(5, passportGrid.cols)
        assertEquals(6, passportGrid.rows)
        assertEquals(30, passportGrid.cellsMm.size)
        assertEquals(35f, passportGrid.cellsMm.first().widthMm, 0.01f)
        assertEquals(45f, passportGrid.cellsMm.first().heightMm, 0.01f)

        val bottomRightSingle = PhotoLayoutGeometry.computeSheetPlan(
            paperSize = PaperSize.A4,
            orientation = PrintOrientation.PORTRAIT,
            layout = PhotoLayoutOptions(
                imagesPerPage = ImagesPerPage.ONE,
                photoSize = PhotoSizePreset.SIZE_10X15,
                marginPreset = PhotoMarginPreset.NORMAL,
                position = PhotoPosition.BOTTOM_RIGHT
            ),
            primaryPhotoIsLandscape = false
        )
        val cell = bottomRightSingle.cellsMm.single()
        assertEquals(210f - 10f - 101.6f, cell.leftMm, 0.1f)
        assertEquals(297f - 10f - 152.4f, cell.topMm, 0.1f)

        assertEquals(2, PhotoLayoutGeometry.computeTotalPhotoPages(5, 4, repeatSinglePhoto = true))
        assertEquals(1, PhotoLayoutGeometry.computeTotalPhotoPages(1, 4, repeatSinglePhoto = true))
    }

    @Test
    fun `jbig encoder produces valid 20-byte BIH and SDNORM marker`() {
        val widthPx = 64
        val heightPx = 16
        val packed = ByteArray((widthPx / 8) * heightPx) { idx ->
            if (idx % 3 == 0) 0xAA.toByte() else 0x55.toByte()
        }
        val stream = JbigEncoder.encodeStripe(packed, widthPx, heightPx)
        assertTrue(stream.size > 22)
        // BIH planes = 1
        assertEquals(1, stream[2].toInt() and 0xFF)
        // BIH XD = 64 (big-endian at offset 4..7)
        assertEquals(64, stream[7].toInt() and 0xFF)
        // BIH YD = 16 (big-endian at offset 8..11)
        assertEquals(16, stream[11].toInt() and 0xFF)
        // SDNORM marker 0xFF 0x02 at tail
        assertEquals(0xFF, stream[stream.size - 2].toInt() and 0xFF)
        assertEquals(0x02, stream[stream.size - 1].toInt() and 0xFF)
    }

    @Test
    fun `jbig encoder roundtrips losslessly through ITU-T T82 QM decoder across complex patterns`() {
        val patterns = listOf(
            // All white
            Triple(128, 32, ByteArray(16 * 32) { 0x00 }),
            // All black
            Triple(128, 32, ByteArray(16 * 32) { 0xFF.toByte() }),
            // Alternating 1px checkerboard
            Triple(64, 32, ByteArray(8 * 32) { idx ->
                val row = idx / 8
                if (row % 2 == 0) 0xAA.toByte() else 0x55.toByte()
            }),
            // Non-byte-aligned width (37px)
            Triple(37, 25, ByteArray(5 * 25) { idx ->
                val row = idx / 5
                val col = idx % 5
                val raw = ((row * 31 + col * 17) xor 0xA5) and 0xFF
                if (col == 4) (raw and 0xF8).toByte() else raw.toByte()
            }),
            // Full A4 600 DPI 128-line band (4960 x 128) with mixed text & halftone blocks
            run {
                val w = 4960
                val h = 128
                val wb = w / 8
                val buf = ByteArray(wb * h)
                for (y in 0 until h) {
                    for (xByte in 0 until wb) {
                        val v = when {
                            y in 10..24 && xByte in 40..500 -> if ((xByte + y) % 5 == 0) 0xFF else 0xDB
                            y in 40..90 && xByte in 100..450 -> ((y * 73 + xByte * 37) xor (xByte ushr 1)) and 0xFF
                            y in 100..115 -> 0xFF
                            else -> 0x00
                        }
                        buf[y * wb + xByte] = v.toByte()
                    }
                }
                Triple(w, h, buf)
            }
        )

        for ((widthPx, heightPx, expected) in patterns) {
            val bie = JbigEncoder.encodeStripe(expected, widthPx, heightPx)
            val decoded = decodeJbigBie(bie)
            assertArrayEquals(
                "Roundtrip mismatch for ${widthPx}x${heightPx}",
                expected,
                decoded
            )
        }
    }

    @Test
    fun `pjl header and footer match SCX-3200 QPDL specification`() {
        val header = SplQpdlEncoder.buildPjlHeader(
            quality = PrintQuality.DRAFT_TONER_SAVE,
            jobTitle = "Invoice.pdf"
        ).toString(Charsets.US_ASCII)

        assertTrue(header.contains("@PJL SET PAPERTYPE=OFF"))
        assertTrue(header.contains("@PJL SET DUPLEX=OFF"))
        assertTrue(header.contains("@PJL SET ECONOMODE=ON"))
        assertTrue(header.contains("@PJL SET ALTITUDE=LOW"))
        assertTrue(header.contains("@PJL SET DENSITY=3"))
        assertTrue(header.contains("@PJL ENTER LANGUAGE = QPDL"))

        val footer = SplQpdlEncoder.buildPjlFooter()
        assertEquals(0x09, footer[0].toInt() and 0xFF)
        assertEquals("\u001B%-12345X", footer.copyOfRange(1, footer.size).toString(Charsets.US_ASCII))

        assertEquals(0, PaperSize.A4.pixelWidth(600) % 32)
        assertEquals(0, PaperSize.LETTER.pixelWidth(1200) % 32)
        assertEquals(0, PaperSize.LETTER.splPaperCode)
        assertEquals(1, PaperSize.LEGAL.splPaperCode)
        assertEquals(2, PaperSize.A4.splPaperCode)
        assertEquals(16, PaperSize.A5.splPaperCode)
        assertEquals(1200, PrintQuality.FINE_1200.xDpi)
        assertEquals(600, PrintQuality.FINE_1200.yDpi)
    }

    @Test
    fun `algo 0x11 encoder roundtrips losslessly across blank, repetitive, noisy, and full A4 bands`() {
        val inputs = listOf(
            // All white 620x128 (full A4 600dpi band = 79360 bytes)
            ByteArray(620 * 128) { 0x00 },
            // All black 620x128
            ByteArray(620 * 128) { 0xFF.toByte() },
            // Checkerboard / repeating pattern with 2048+ span
            ByteArray(620 * 32) { idx ->
                val row = idx / 620
                if (row % 2 == 0) 0xAA.toByte() else 0x55.toByte()
            },
            // High-entropy / mixed text & halftone band (including uncompressible runs)
            ByteArray(620 * 64) { idx ->
                val row = idx / 620
                val col = idx % 620
                if (row in 10..20 && col in 50..500) {
                    ((idx * 1103515245 + 12345) ushr 16).toByte()
                } else if (row in 30..45) {
                    0xDB.toByte()
                } else {
                    0x00
                }
            },
            // Small tail band < 2048 bytes
            ByteArray(620 * 2) { idx -> ((idx * 17) xor 0x5A).toByte() }
        )

        for ((caseIdx, expected) in inputs.withIndex()) {
            val compressed = com.example.printer.Algo0x11Encoder.compress(expected)
            val decoded = decodeAlgo0x11(compressed, expected.size)
            assertArrayEquals("Algo0x11 roundtrip mismatch on case $caseIdx", expected, decoded)
        }
    }

    @Test
    fun `page start header emits SpliX scx3200 bounds, duplex=1, qpdlVersion=3, DocHeaderValues, and xRes`() {
        val a4Width600 = PaperSize.A4.pixelWidth(600)   // 4960 (620 bytes)
        val a4Height600 = PaperSize.A4.pixelHeight(600) // 7016
        val header = SplQpdlEncoder.buildPageStartHeader(
            widthPx = a4Width600,
            heightPx = a4Height600,
            settings = PrintSettings(
                paperSize = PaperSize.A4,
                quality = PrintQuality.STANDARD_600,
                dialect = QpdlDialect.SCX3200_SPLIX_0X11
            )
        )
        assertEquals(17, header.size)
        assertEquals(0x00, header[0].toInt() and 0xFF)
        assertEquals(6, header[1].toInt() and 0xFF) // 600 DPI yResCode
        assertEquals(PaperSize.A4.splPaperCode, header[4].toInt() and 0xFF)
        val w300 = ((header[5].toInt() and 0xFF) shl 8) or (header[6].toInt() and 0xFF)
        val h300 = ((header[7].toInt() and 0xFF) shl 8) or (header[8].toInt() and 0xFF)
        assertEquals(2480, w300)
        assertEquals(3508, h300)
        assertEquals(0x01, header[9].toInt() and 0xFF)  // paperSource = Auto
        assertEquals(0x00, header[10].toInt() and 0xFF) // DocHeaderValues[0] = 0
        assertEquals(0x01, header[11].toInt() and 0xFF) // duplex = 1 (Simplex non-0x15)
        assertEquals(0x00, header[12].toInt() and 0xFF) // tumble = 0
        assertEquals(0x00, header[13].toInt() and 0xFF) // DocHeaderValues[1] = 0
        assertEquals(0x03, header[14].toInt() and 0xFF) // QPDLVersion = 3
        assertEquals(0x01, header[15].toInt() and 0xFF) // DocHeaderValues[2] = 1
        assertEquals(0x06, header[16].toInt() and 0xFF) // 600 DPI xResCode
    }

    @Test
    fun `band record emits SpliX-exact 11-byte mono 0x11, 0x13, 0x15, and raw records`() {
        val widthPx = 512
        val bandH = 32
        val packed = ByteArray((widthPx / 8) * bandH) { 0x3C.toByte() }

        // Default SCX-3200 SpliX 0x11 record
        val rec11 = SplQpdlEncoder.encodeBandRecord(
            bandIndex = 2,
            packedBand1bpp = packed,
            widthPx = widthPx,
            bandHeight = bandH,
            dialect = QpdlDialect.SCX3200_SPLIX_0X11
        )
        assertEquals(0x0C, rec11[0].toInt() and 0xFF)
        assertEquals(2, rec11[1].toInt() and 0xFF)
        // Width in PIXELS (512 = 0x0200) at bytes 2..3
        assertEquals(0x02, rec11[2].toInt() and 0xFF)
        assertEquals(0x00, rec11[3].toInt() and 0xFF)
        // Height is ALWAYS fixed 128 lines (0x0080) per SpliX compress.cpp for Algo0x11
        assertEquals(0x00, rec11[4].toInt() and 0xFF)
        assertEquals(0x80, rec11[5].toInt() and 0xFF)
        // NO plane byte; comp=0x11 directly at byte 6
        assertEquals(0x11, rec11[6].toInt() and 0xFF)
        val dataSize11 = ((rec11[7].toInt() and 0xFF) shl 24) or
            ((rec11[8].toInt() and 0xFF) shl 16) or
            ((rec11[9].toInt() and 0xFF) shl 8) or
            (rec11[10].toInt() and 0xFF)
        assertEquals(rec11.size - 11, dataSize11)
        // Sub-header magic 0x09ABCDEF in Little-Endian at bytes 11..14
        assertEquals(0xEF, rec11[11].toInt() and 0xFF)
        assertEquals(0xCD, rec11[12].toInt() and 0xFF)
        assertEquals(0xAB, rec11[13].toInt() and 0xFF)
        assertEquals(0x09, rec11[14].toInt() and 0xFF)
        // Verify full SpliX 0x11 roundtrip: decode -> un-invert (~b) -> un-transpose (x*128+y)
        val compressedPayload = rec11.copyOfRange(15, rec11.size - 4)
        val decodedColMajorInv = decodeAlgo0x11(compressedPayload, (widthPx / 8) * 128)
        val widthBytes = widthPx / 8
        for (y in 0 until 128) {
            for (x in 0 until widthBytes) {
                val restoredByte = ((decodedColMajorInv[x * 128 + y].toInt() and 0xFF) xor 0xFF).toByte()
                val expectedByte = if (y < bandH) packed[y * widthBytes + x] else 0x00.toByte()
                assertEquals("Pixel byte mismatch at ($x,$y)", expectedByte, restoredByte)
            }
        }
        // Trailing Big-Endian 32-bit byte-sum checksum
        var expectedSum = 0L
        for (idx in 11 until rec11.size - 4) {
            expectedSum += (rec11[idx].toInt() and 0xFF)
        }
        val actualSum = ((rec11[rec11.size - 4].toLong() and 0xFF) shl 24) or
            ((rec11[rec11.size - 3].toLong() and 0xFF) shl 16) or
            ((rec11[rec11.size - 2].toLong() and 0xFF) shl 8) or
            (rec11[rec11.size - 1].toLong() and 0xFF)
        assertEquals(expectedSum, actualSum)

        // Mono JBIG 0x13 record (11-byte header + 32-byte Big-Endian 0x39ABCDEF subheader + BIE + 4B checksum)
        val rec13 = SplQpdlEncoder.encodeBandRecord(
            bandIndex = 0,
            packedBand1bpp = packed,
            widthPx = widthPx,
            bandHeight = bandH,
            dialect = QpdlDialect.QPDL_MONO_0X13
        )
        assertEquals(0x13, rec13[6].toInt() and 0xFF)
        assertEquals(0x39, rec13[11].toInt() and 0xFF)
        assertEquals(0xAB, rec13[12].toInt() and 0xFF)
        assertEquals(0xCD, rec13[13].toInt() and 0xFF)
        assertEquals(0xEF, rec13[14].toInt() and 0xFF)

        // Color/v3 0x15 record (12-byte header with plane=4, widthBytes, comp=0x15)
        val rec15 = SplQpdlEncoder.encodeBandRecord(
            bandIndex = 0,
            packedBand1bpp = packed,
            widthPx = widthPx,
            bandHeight = bandH,
            dialect = QpdlDialect.QPDL_COLOR_0X15
        )
        assertEquals(0x04, rec15[6].toInt() and 0xFF)
        assertEquals(0x15, rec15[7].toInt() and 0xFF)

        // Mono Raw 0x00 record
        val rawRec = SplQpdlEncoder.encodeBandRecord(
            bandIndex = 1,
            packedBand1bpp = packed,
            widthPx = widthPx,
            bandHeight = bandH,
            dialect = QpdlDialect.RAW_MONO_0X00
        )
        assertEquals(0x00, rawRec[6].toInt() and 0xFF)
        assertEquals(11 + 4 + packed.size + 4, rawRec.size)
        assertArrayEquals(packed, rawRec.copyOfRange(15, 15 + packed.size))
    }

    private fun decodeAlgo0x11(encoded: ByteArray, expectedLen: Int): ByteArray {
        val uncompSize = (encoded[0].toInt() and 0xFF) or
            ((encoded[1].toInt() and 0xFF) shl 8) or
            ((encoded[2].toInt() and 0xFF) shl 16) or
            ((encoded[3].toInt() and 0xFF) shl 24)
        val ptrArray = IntArray(64) { i ->
            val off = 4 + i * 2
            (encoded[off].toInt() and 0xFF) or ((encoded[off + 1].toInt() and 0xFF) shl 8)
        }
        val out = ByteArray(expectedLen)
        var pos = 4 + 128
        var w = 0
        for (i in 0 until uncompSize) {
            out[w++] = encoded[pos++]
        }
        while (pos < encoded.size) {
            val ctrl = encoded[pos++].toInt() and 0xFF
            if ((ctrl and 0x80) != 0) {
                val b1 = encoded[pos++].toInt() and 0xFF
                val ptrIdx = b1 and 0x3F
                val len = (((b1 and 0xC0) shl 1) or (ctrl and 0x7F)) + 3
                val offset = ptrArray[ptrIdx]
                for (k in 0 until len) {
                    out[w] = out[w - offset]
                    w++
                }
            } else {
                val litCount = ctrl + 1
                for (k in 0 until litCount) {
                    out[w++] = encoded[pos++]
                }
            }
        }
        assertEquals(expectedLen, w)
        return out
    }

    /**
     * Independent ITU-T T.82 (JBIG1) QM arithmetic decoder for verifying JbigEncoder output bit-for-bit.
     */
    private fun decodeJbigBie(bie: ByteArray): ByteArray {
        val xd = ((bie[4].toInt() and 0xFF) shl 24) or
            ((bie[5].toInt() and 0xFF) shl 16) or
            ((bie[6].toInt() and 0xFF) shl 8) or
            (bie[7].toInt() and 0xFF)
        val yd = ((bie[8].toInt() and 0xFF) shl 24) or
            ((bie[9].toInt() and 0xFF) shl 16) or
            ((bie[10].toInt() and 0xFF) shl 8) or
            (bie[11].toInt() and 0xFF)

        val lsz = intArrayOf(
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
        val nmps = intArrayOf(
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
        val nlps = intArrayOf(
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
        val switchMps = booleanArrayOf(
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

        var pos = 20
        var hitEndMarker = false
        fun nextEntropyByte(): Long {
            if (hitEndMarker || pos >= bie.size) return 0L
            val b = bie[pos].toInt() and 0xFF
            if (b == 0xFF) {
                val next = if (pos + 1 < bie.size) bie[pos + 1].toInt() and 0xFF else 0
                if (next == 0x00) {
                    pos += 2
                    return 0xFFL
                }
                hitEndMarker = true
                return 0L
            }
            pos++
            return b.toLong()
        }

        var a = 0x10000L
        var c = (nextEntropyByte() shl 24) or (nextEntropyByte() shl 16) or (nextEntropyByte() shl 8)
        var ct = 8
        val states = IntArray(1024)
        val mps = IntArray(1024)

        val widthBytes = (xd + 7) / 8
        val out = ByteArray(widthBytes * yd)

        fun getBit(y: Int, x: Int): Int {
            if (y < 0 || x < 0 || x >= xd) return 0
            val byteVal = out[y * widthBytes + (x ushr 3)].toInt() and 0xFF
            return (byteVal ushr (7 - (x and 7))) and 1
        }

        fun setBit(y: Int, x: Int, bit: Int) {
            if (bit == 1) {
                val idx = y * widthBytes + (x ushr 3)
                out[idx] = (out[idx].toInt() or (1 shl (7 - (x and 7)))).toByte()
            }
        }

        fun renorm() {
            do {
                a = a shl 1
                c = (c shl 1) and 0xFFFFFFFFL
                ct--
                if (ct == 0) {
                    c = (c or (nextEntropyByte() shl 8)) and 0xFFFFFFFFL
                    ct = 8
                }
            } while ((a and 0x8000L) == 0L)
        }

        for (y in 0 until yd) {
            for (x in 0 until xd) {
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

                val st = states[cx]
                val q = lsz[st].toLong()
                a -= q
                val cHigh = c ushr 16
                val pix: Int
                if (cHigh < a) {
                    if ((a and 0x8000L) != 0L) {
                        pix = mps[cx]
                    } else {
                        if (a < q) {
                            pix = mps[cx] xor 1
                            if (switchMps[st]) mps[cx] = mps[cx] xor 1
                            states[cx] = nlps[st]
                        } else {
                            pix = mps[cx]
                            states[cx] = nmps[st]
                        }
                        renorm()
                    }
                } else {
                    c = (c - (a shl 16)) and 0xFFFFFFFFL
                    if (a < q) {
                        pix = mps[cx]
                        states[cx] = nmps[st]
                    } else {
                        pix = mps[cx] xor 1
                        if (switchMps[st]) mps[cx] = mps[cx] xor 1
                        states[cx] = nlps[st]
                    }
                    a = q
                    renorm()
                }
                setBit(y, x, pix)
            }
        }
        return out
    }
}
