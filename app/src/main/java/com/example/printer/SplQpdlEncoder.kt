package com.example.printer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.example.model.PaperSize
import com.example.model.PrintQuality
import com.example.model.PrintSettings
import com.example.model.QpdlDialect
import com.example.model.UsbDeviceDetails
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Samsung SPL / QPDL raster stream builder for Samsung SCX-3200 series printers.
 * Generates PJL initialization, QPDL RECTYPE 0x00 page headers, 128-line JBIG1 (0x13 / 0x15)
 * or raw (0x00) monochrome raster bands (RECTYPE 0x0C), and page/job footers (0x01 / 0x09).
 */
object SplQpdlEncoder {

    const val BAND_HEIGHT_LINES = 128

    fun buildPjlHeader(
        quality: PrintQuality,
        jobTitle: String
    ): ByteArray {
        val safeTitle = jobTitle.replace("\"", "'").take(48)
        val economode = if (quality.economode) "ON" else "OFF"
        val dateStamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val pjl = buildString {
            append("\u001B%-12345X@PJL DEFAULT SERVICEDATE=$dateStamp\r\n")
            append("@PJL SET USERNAME=\"Android\"\r\n")
            append("@PJL SET JOBNAME=\"$safeTitle\"\r\n")
            append("@PJL SET ECONOMODE=$economode\r\n")
            append("@PJL DEFAULT POWERSAVE=ON\r\n")
            append("@PJL DEFAULT POWERSAVETIME=5\r\n")
            append("@PJL SET JAMRECOVERY=OFF\r\n")
            append("@PJL SET DUPLEX=OFF\r\n")
            append("@PJL SET PAPERTYPE=OFF\r\n")
            append("@PJL SET ALTITUDE=LOW\r\n")
            append("@PJL SET DENSITY=3\r\n")
            append("@PJL SET RET=NORMAL\r\n")
            append("@PJL ENTER LANGUAGE = QPDL\r\n")
        }
        return pjl.toByteArray(Charsets.US_ASCII)
    }

    fun buildPjlFooter(): ByteArray {
        // SCX-3200 PPD EndPJL: RECTYPE 0x09 (End of Document) followed by UEL
        val uel = "\u001B%-12345X".toByteArray(Charsets.US_ASCII)
        return byteArrayOf(0x09) + uel
    }

    /**
     * RECTYPE 0x00: QPDL Start Page Header (17 bytes), matching OpenPrinting/splix qpdl.cpp.
     * Page w/h are normalized to 300-DPI coordinates; bytes 10..16 carry duplex, DocHeaderValues,
     * QPDL version (3 for SCX-3200), and X-resolution code.
     */
    fun buildPageStartHeader(
        widthPx: Int,
        heightPx: Int,
        settings: PrintSettings
    ): ByteArray {
        val xResCode = (settings.quality.xDpi / 100).coerceIn(1, 255)
        val yResCode = (settings.quality.yDpi / 100).coerceIn(1, 255)
        val copies = settings.copies.coerceIn(1, 99)
        val headerW = (widthPx * 300f / settings.quality.xDpi.coerceAtLeast(1)).roundToInt()
            .coerceIn(1, 0xFFFF)
        val headerH = (heightPx * 300f / settings.quality.yDpi.coerceAtLeast(1)).roundToInt()
            .coerceIn(1, 0xFFFF)
        val dialect = settings.dialect

        val out = ByteArrayOutputStream(17)
        out.write(0x00) // 0x00: Signature
        out.write(yResCode) // 0x01: Y Resolution / 100
        out.write((copies ushr 8) and 0xFF) // 0x02: Number of copies 8-15
        out.write(copies and 0xFF) // 0x03: Number of copies 0-7
        out.write(settings.paperSize.splPaperCode and 0xFF) // 0x04: Paper type
        out.write((headerW ushr 8) and 0xFF) // 0x05: Printable area width 8-15
        out.write(headerW and 0xFF) // 0x06: Printable area width 0-7
        out.write((headerH ushr 8) and 0xFF) // 0x07: Printable area height 8-15
        out.write(headerH and 0xFF) // 0x08: Printable area height 0-7
        out.write(0x01) // 0x09: Paper source (1 = Auto)
        out.write(dialect.docHeaderByte0 and 0xFF) // 0x0A: DocHeaderValues[0] (0)
        out.write(dialect.duplexSimplexByte and 0xFF) // 0x0B: Duplex (1 for Simplex when comp != 0x15)
        out.write(0x00) // 0x0C: Tumble (0)
        out.write(dialect.docHeaderByte1 and 0xFF) // 0x0D: DocHeaderValues[1] (0)
        out.write(dialect.qpdlVersion and 0xFF) // 0x0E: QPDL Version (3 for SCX-3200)
        out.write(dialect.docHeaderByte2 and 0xFF) // 0x0F: DocHeaderValues[2] (1)
        out.write(if (dialect.includeXResInByte16) xResCode else 0x00) // 0x10: X Resolution / 100
        return out.toByteArray()
    }

    /**
     * Encodes a single 128-line (or tail) 1bpp MSB-first band into a QPDL RECTYPE 0x0C packet
     * following OpenPrinting/splix qpdl.cpp `_renderBand` / `_renderJBIGBand`.
     */
    fun encodeBandRecord(
        bandIndex: Int,
        packedBand1bpp: ByteArray,
        widthPx: Int,
        bandHeight: Int,
        dialect: QpdlDialect = QpdlDialect.SCX3200_SPLIX_0X11,
        isLastBand: Boolean = false
    ): ByteArray {
        val widthBytes = (widthPx + 7) / 8

        return when (dialect.compByte) {
            0x11 -> {
                val fixedBandHeight = BAND_HEIGHT_LINES
                val preparedBand = Algo0x11Encoder.prepareColumnMajorInvertedBand(
                    packedRowMajor = packedBand1bpp,
                    widthBytes = widthBytes,
                    actualBandHeight = bandHeight,
                    fixedBandHeight = fixedBandHeight
                )
                val compressed = Algo0x11Encoder.compress(preparedBand)
                val dataSize = compressed.size + 8 // 4B signature + payload + 4B checksum
                val out = ByteArrayOutputStream(11 + dataSize)

                // 11-byte monochrome RECTYPE 0x0C header (NO plane byte when ColorDevice=False)
                out.write(0x0C)
                out.write(bandIndex and 0xFF)
                out.write((widthPx ushr 8) and 0xFF)
                out.write(widthPx and 0xFF)
                out.write((fixedBandHeight ushr 8) and 0xFF)
                out.write(fixedBandHeight and 0xFF)
                out.write(0x11)
                writeUint32BE(out, dataSize.toLong())

                // 4-byte sub-header signature (0x09ABCDEF in Little-Endian CPU order)
                out.write(0xEF)
                out.write(0xCD)
                out.write(0xAB)
                out.write(0x09)

                out.write(compressed)

                var checksum = (0xEF + 0xCD + 0xAB + 0x09).toLong()
                for (b in compressed) {
                    checksum += (b.toInt() and 0xFF)
                }
                writeUint32BE(out, checksum)
                out.toByteArray()
            }

            0x15 -> {
                val jbigBie = JbigEncoder.encodeStripe(
                    packed1bpp = packedBand1bpp,
                    widthPx = widthPx,
                    heightPx = bandHeight
                )
                val dataSize = jbigBie.size + 4
                val out = ByteArrayOutputStream(12 + dataSize)
                out.write(0x0C)
                out.write(bandIndex and 0xFF)
                out.write((widthBytes ushr 8) and 0xFF)
                out.write(widthBytes and 0xFF)
                out.write((bandHeight ushr 8) and 0xFF)
                out.write(bandHeight and 0xFF)
                out.write(dialect.planeByte and 0xFF)
                out.write(0x15)
                writeUint32BE(out, dataSize.toLong())
                out.write(jbigBie)
                writeUint32BE(out, byteSum(jbigBie))
                out.toByteArray()
            }

            0x13 -> {
                val jbigBie = JbigEncoder.encodeStripe(
                    packed1bpp = packedBand1bpp,
                    widthPx = widthPx,
                    heightPx = bandHeight
                )
                // SpliX _renderBand with subVersion == 3: 4B sig + 28B subheader + payload + 4B checksum
                val dataSize = jbigBie.size + 36
                val out = ByteArrayOutputStream(11 + dataSize)
                out.write(0x0C)
                out.write(bandIndex and 0xFF)
                out.write((widthPx ushr 8) and 0xFF)
                out.write(widthPx and 0xFF)
                out.write((bandHeight ushr 8) and 0xFF)
                out.write(bandHeight and 0xFF)
                out.write(0x13)
                writeUint32BE(out, dataSize.toLong())

                // 32-byte Big-Endian sub-header (0x39ABCDEF, dataSize, state, 20B zeros)
                out.write(0x39)
                out.write(0xAB)
                out.write(0xCD)
                out.write(0xEF)
                writeUint32BE(out, jbigBie.size.toLong())
                val stateByte = when {
                    bandIndex == 0 -> 0x00
                    isLastBand -> 0x02
                    else -> 0x01
                }
                out.write(stateByte)
                repeat(23) { out.write(0x00) }

                out.write(jbigBie)

                var checksum = byteSum(jbigBie) + 0x39 + 0xAB + 0xCD + 0xEF + stateByte
                checksum += ((jbigBie.size ushr 24) and 0xFF) +
                    ((jbigBie.size ushr 16) and 0xFF) +
                    ((jbigBie.size ushr 8) and 0xFF) +
                    (jbigBie.size and 0xFF)
                writeUint32BE(out, checksum)
                out.toByteArray()
            }

            else -> {
                val expectedLen = widthBytes * bandHeight
                val payloadLen = minOf(expectedLen, packedBand1bpp.size)
                val dataSize = payloadLen + 8
                val out = ByteArrayOutputStream(11 + dataSize)
                out.write(0x0C)
                out.write(bandIndex and 0xFF)
                out.write((widthPx ushr 8) and 0xFF)
                out.write(widthPx and 0xFF)
                out.write((bandHeight ushr 8) and 0xFF)
                out.write(bandHeight and 0xFF)
                out.write(0x00)
                writeUint32BE(out, dataSize.toLong())
                out.write(0xEF)
                out.write(0xCD)
                out.write(0xAB)
                out.write(0x09)
                out.write(packedBand1bpp, 0, payloadLen)
                var checksum = (0xEF + 0xCD + 0xAB + 0x09).toLong()
                for (idx in 0 until payloadLen) {
                    checksum += (packedBand1bpp[idx].toInt() and 0xFF)
                }
                writeUint32BE(out, checksum)
                out.toByteArray()
            }
        }
    }

    private fun byteSum(bytes: ByteArray): Long {
        var sum = 0L
        for (b in bytes) {
            sum += (b.toInt() and 0xFF)
        }
        return sum
    }

    private fun writeUint32BE(out: ByteArrayOutputStream, value: Long) {
        out.write(((value ushr 24) and 0xFF).toInt())
        out.write(((value ushr 16) and 0xFF).toInt())
        out.write(((value ushr 8) and 0xFF).toInt())
        out.write((value and 0xFF).toInt())
    }

    /**
     * RECTYPE 0x01: End of Page (3 bytes).
     */
    fun buildPageEndFooter(copies: Int): ByteArray {
        val clamped = copies.coerceIn(1, 99)
        return byteArrayOf(
            0x01,
            ((clamped ushr 8) and 0xFF).toByte(),
            (clamped and 0xFF).toByte()
        )
    }

    /**
     * Encodes a small full-page packed 1bpp buffer (used by unit tests).
     */
    fun encodePage(
        packed1bpp: ByteArray,
        widthPx: Int,
        heightPx: Int,
        settings: PrintSettings
    ): ByteArray {
        val widthBytes = (widthPx + 7) / 8
        val out = ByteArrayOutputStream(packed1bpp.size / 4 + 512)
        out.write(buildPageStartHeader(widthPx, heightPx, settings))

        var y = 0
        var bandIndex = 0
        while (y < heightPx) {
            val bandHeight = minOf(BAND_HEIGHT_LINES, heightPx - y)
            val bandOffset = y * widthBytes
            val bandByteLen = bandHeight * widthBytes
            val bandSlice = packed1bpp.copyOfRange(bandOffset, bandOffset + bandByteLen)
            if (settings.dialect.compByte != 0x11 ||
                !Algo0x11Encoder.isBandEmpty(bandSlice, widthBytes, bandHeight)
            ) {
                out.write(
                    encodeBandRecord(
                        bandIndex = bandIndex,
                        packedBand1bpp = bandSlice,
                        widthPx = widthPx,
                        bandHeight = bandHeight,
                        dialect = settings.dialect,
                        isLastBand = (y + bandHeight >= heightPx)
                    )
                )
            }
            y += bandHeight
            bandIndex++
        }

        out.write(buildPageEndFooter(settings.copies))
        return out.toByteArray()
    }

    /**
     * Draws a single horizontal band [bandY, bandY + bandBitmap.height) of the SCX-3200 Diagnostic Sheet.
     */
    fun drawDiagnosticTestPageBand(
        bandBitmap: Bitmap,
        pageW: Int,
        pageH: Int,
        bandY: Int,
        xDpi: Int,
        yDpi: Int,
        paperSize: PaperSize,
        deviceDetails: UsbDeviceDetails?
    ) {
        bandBitmap.eraseColor(Color.WHITE)
        val canvas = Canvas(bandBitmap)
        val aspectAdjustX = xDpi.toFloat() / yDpi.toFloat().coerceAtLeast(1f)
        val logicalW = pageW / aspectAdjustX

        canvas.save()
        canvas.translate(0f, -bandY.toFloat())
        canvas.scale(aspectAdjustX, 1f)

        val margin = logicalW * 0.065f
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 3f * (logicalW / 1240f)
        }
        val thinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * (logicalW / 1240f)
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = logicalW * 0.042f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = logicalW * 0.022f
            typeface = Typeface.MONOSPACE
        }
        val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = logicalW * 0.017f
            typeface = Typeface.MONOSPACE
        }

        canvas.drawRect(margin, margin, logicalW - margin, pageH - margin, linePaint)
        val markLen = logicalW * 0.025f
        canvas.drawLine(margin - markLen, margin, margin, margin, thinPaint)
        canvas.drawLine(margin, margin - markLen, margin, margin, thinPaint)
        canvas.drawLine(logicalW - margin, margin, logicalW - margin + markLen, margin, thinPaint)
        canvas.drawLine(logicalW - margin, margin - markLen, logicalW - margin, margin, thinPaint)

        var y = margin + logicalW * 0.07f
        canvas.drawText("SCX-3200 USB DIAGNOSTIC SHEET", margin + logicalW * 0.02f, y, titlePaint)
        y += logicalW * 0.032f
        canvas.drawLine(margin + logicalW * 0.02f, y, logicalW - margin - logicalW * 0.02f, y, thinPaint)
        y += logicalW * 0.045f

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val lines = listOf(
            "MODEL      : ${deviceDetails?.productName ?: "Samsung SCX-3200 Series"}",
            "USB ID     : ${deviceDetails?.vidPidHex ?: "04E8:3441"}",
            "PROTOCOL   : Samsung SPL / QPDL (Algo 0x11)",
            "RESOLUTION : ${xDpi}x${yDpi} DPI",
            "PAPER SIZE : ${paperSize.label} (${paperSize.widthMm.toInt()} x ${paperSize.heightMm.toInt()} mm)",
            "TIMESTAMP  : $timestamp",
            "IEEE 1284  : ${deviceDetails?.ieee1284Id?.take(64) ?: "MFG:Samsung;CMD:SPL;MDL:SCX-3200;"}"
        )
        for (line in lines) {
            canvas.drawText(line, margin + logicalW * 0.02f, y, bodyPaint)
            y += logicalW * 0.034f
        }

        y += logicalW * 0.03f
        canvas.drawText("TONER DENSITY & HALFTONE STEP WEDGE", margin + logicalW * 0.02f, y, smallPaint)
        y += logicalW * 0.02f

        val wedgeLeft = margin + logicalW * 0.02f
        val wedgeWidth = logicalW - 2 * margin - logicalW * 0.04f
        val stepCount = 10
        val stepW = wedgeWidth / stepCount
        val stepH = logicalW * 0.055f
        for (i in 0 until stepCount) {
            val gray = 255 - (i * 255 / (stepCount - 1))
            fillPaint.color = Color.rgb(gray, gray, gray)
            canvas.drawRect(
                wedgeLeft + i * stepW,
                y,
                wedgeLeft + (i + 1) * stepW,
                y + stepH,
                fillPaint
            )
            canvas.drawRect(
                wedgeLeft + i * stepW,
                y,
                wedgeLeft + (i + 1) * stepW,
                y + stepH,
                thinPaint
            )
        }
        fillPaint.color = Color.BLACK

        y += stepH + logicalW * 0.055f
        canvas.drawText("LINE WEIGHT & REGISTRATION TARGETS", wedgeLeft, y, smallPaint)
        y += logicalW * 0.025f

        val baseStroke = (logicalW / 1240f).coerceAtLeast(1f)
        for (i in 1..6) {
            thinPaint.strokeWidth = i * baseStroke
            val lineY = y + i * logicalW * 0.022f
            canvas.drawLine(wedgeLeft, lineY, wedgeLeft + wedgeWidth * 0.65f, lineY, thinPaint)
            canvas.drawText("${i}px", wedgeLeft + wedgeWidth * 0.68f, lineY + 6f, smallPaint)
        }

        thinPaint.strokeWidth = 2f * baseStroke
        val cx = logicalW - margin - logicalW * 0.14f
        val cy = pageH - margin - logicalW * 0.14f
        val r = logicalW * 0.07f
        canvas.drawCircle(cx, cy, r, thinPaint)
        canvas.drawCircle(cx, cy, r * 0.5f, thinPaint)
        canvas.drawLine(cx - r * 1.25f, cy, cx + r * 1.25f, cy, thinPaint)
        canvas.drawLine(cx, cy - r * 1.25f, cx, cy + r * 1.25f, thinPaint)

        val footerRect = RectF(
            wedgeLeft,
            pageH - margin - logicalW * 0.06f,
            logicalW - margin - logicalW * 0.30f,
            pageH - margin - logicalW * 0.02f
        )
        canvas.drawText("Samsung Printer — USB OTG Direct Laser Driver", footerRect.left, footerRect.top, smallPaint)
        canvas.restore()
    }

    /**
     * Generates a preview-sized SCX-3200 diagnostic test sheet bitmap.
     */
    fun createDiagnosticTestPageBitmap(
        paperSize: PaperSize,
        deviceDetails: UsbDeviceDetails?,
        previewScaleWidth: Int = 1240
    ): Bitmap {
        val aspect = paperSize.heightMm / paperSize.widthMm
        val width = previewScaleWidth
        val height = (width * aspect).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawDiagnosticTestPageBand(
            bandBitmap = bitmap,
            pageW = width,
            pageH = height,
            bandY = 0,
            xDpi = 600,
            yDpi = 600,
            paperSize = paperSize,
            deviceDetails = deviceDetails
        )
        return bitmap
    }
}
