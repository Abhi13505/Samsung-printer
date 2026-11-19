package com.example.model

import android.net.Uri
import kotlin.math.roundToInt

enum class DocumentType(val displayLabel: String) {
    PHOTO("Photo"),
    PDF("PDF"),
    TEST_PAGE("Test Page")
}

data class PhotoItem(
    val uri: Uri,
    val displayName: String,
    val widthPx: Int,
    val heightPx: Int
)

sealed interface DocumentSource {
    val uri: Uri
    val displayName: String
    val documentType: DocumentType
    val totalPages: Int

    data class ImageDocument(
        val photos: List<PhotoItem>,
        val slotsPerPage: Int = 1,
        val repeatSinglePhoto: Boolean = true,
        override val totalPages: Int = 1
    ) : DocumentSource {
        override val uri: Uri get() = photos.first().uri
        override val displayName: String
            get() = if (photos.size <= 1) {
                photos.firstOrNull()?.displayName ?: "Photo"
            } else {
                "${photos.size} Photos · ${photos.first().displayName}"
            }
        override val documentType: DocumentType = DocumentType.PHOTO

        val primaryWidthPx: Int get() = photos.firstOrNull()?.widthPx ?: 1
        val primaryHeightPx: Int get() = photos.firstOrNull()?.heightPx ?: 1
    }

    data class PdfDocumentSource(
        override val uri: Uri,
        override val displayName: String,
        override val totalPages: Int
    ) : DocumentSource {
        override val documentType: DocumentType = DocumentType.PDF
    }
}

enum class DitherMode(val label: String, val subtitle: String) {
    HALFTONE_PHOTO("Halftone", "Floyd-Steinberg laser dither for photos"),
    THRESHOLD_DOCUMENT("Crisp Text", "Sharp black/white edge threshold"),
    GRAYSCALE_TONE("Soft Tone", "Ordered screen for mixed graphics")
}

data class ImageAdjustmentState(
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    val rotationDegrees: Int = 0,
    val brightness: Float = 0f,     // -0.5f to +0.5f
    val contrast: Float = 1.0f,     // 0.5f to 2.0f
    val ditherMode: DitherMode = DitherMode.HALFTONE_PHOTO
) {
    val hasEdits: Boolean
        get() = cropLeft > 0.001f ||
            cropTop > 0.001f ||
            cropRight < 0.999f ||
            cropBottom < 0.999f ||
            rotationDegrees != 0 ||
            kotlin.math.abs(brightness) > 0.01f ||
            kotlin.math.abs(contrast - 1.0f) > 0.01f
}

enum class PaperSize(
    val label: String,
    val widthMm: Float,
    val heightMm: Float,
    val splPaperCode: Int
) {
    A4("A4", 210f, 297f, 2),
    LETTER("Letter", 215.9f, 279.4f, 0),
    LEGAL("Legal", 215.9f, 355.6f, 1),
    A5("A5", 148f, 210f, 16);

    fun pixelWidth(xDpi: Int): Int {
        val raw = (widthMm / 25.4f * xDpi).roundToInt()
        // Align to 32-pixel (4-byte) boundary for QPDL DMA/band compatibility
        return ((raw + 16) and 31.inv()).coerceAtLeast(64)
    }

    fun pixelHeight(yDpi: Int): Int = (heightMm / 25.4f * yDpi).roundToInt()
}

enum class PrintOrientation(val label: String) {
    AUTO("Auto"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape")
}

enum class ScaleMode(val label: String) {
    FIT("Scale to Fit"),
    FILL("Crop to Fill"),
    STRETCH("Stretch")
}

enum class ImagesPerPage(val count: Int, val label: String, val shortLabel: String) {
    ONE(1, "1 per page", "1"),
    TWO(2, "2 per page", "2"),
    FOUR(4, "4 per page (2×2)", "4"),
    SIX(6, "6 per page", "6"),
    EIGHT(8, "8 per page", "8"),
    NINE(9, "9 per page (3×3)", "9"),
    AUTO_GRID(0, "Auto Grid", "Auto")
}

enum class PhotoGapPreset(val label: String, val gapMm: Float) {
    NONE("No gaps", 0f),
    TIGHT("Compact", 2.5f),
    NORMAL("Normal", 6f),
    WIDE("Wide", 12f)
}

enum class PhotoSizePreset(
    val label: String,
    val shortLabel: String,
    val widthMm: Float,
    val heightMm: Float
) {
    AUTO("Auto (Fit Cell)", "Fit Cell", 0f, 0f),
    ID_STAMP("25×35 mm (ID)", "25×35 mm", 25f, 35f),
    PASSPORT("35×45 mm (Passport)", "Passport", 35f, 45f),
    WALLET("5×7 cm (Wallet)", "5×7 cm", 50f, 70f),
    SIZE_10X15("10×15 cm (4×6\")", "10×15 cm", 101.6f, 152.4f),
    SIZE_13X18("13×18 cm (5×7\")", "13×18 cm", 127f, 177.8f),
    SIZE_15X20("15×20 cm (6×8\")", "15×20 cm", 152.4f, 203.2f),
    SIZE_20X30("20×30 cm (Full)", "20×30 cm", 200f, 297f)
}

enum class PhotoMarginPreset(val label: String, val shortLabel: String, val marginMm: Float) {
    NONE("No margins (0 mm)", "None", 0f),
    NARROW("Narrow (5 mm)", "Narrow", 5f),
    NORMAL("Normal (10 mm)", "Normal", 10f),
    WIDE("Wide (20 mm)", "Wide", 20f)
}

enum class PhotoPosition(val label: String, val hBias: Float, val vBias: Float) {
    TOP_LEFT("Top Left", 0f, 0f),
    TOP_CENTER("Top Center", 0.5f, 0f),
    TOP_RIGHT("Top Right", 1f, 0f),
    CENTER_LEFT("Center Left", 0f, 0.5f),
    CENTER("Center", 0.5f, 0.5f),
    CENTER_RIGHT("Center Right", 1f, 0.5f),
    BOTTOM_LEFT("Bottom Left", 0f, 1f),
    BOTTOM_CENTER("Bottom Center", 0.5f, 1f),
    BOTTOM_RIGHT("Bottom Right", 1f, 1f)
}

enum class PhotoCutGuide(val label: String) {
    NONE("None"),
    THIN_BORDER("Thin Frame"),
    CORNER_MARKS("Cut Marks")
}

data class PhotoLayoutOptions(
    val imagesPerPage: ImagesPerPage = ImagesPerPage.ONE,
    val gapPreset: PhotoGapPreset = PhotoGapPreset.NORMAL,
    val photoSize: PhotoSizePreset = PhotoSizePreset.AUTO,
    val marginPreset: PhotoMarginPreset = PhotoMarginPreset.NARROW,
    val position: PhotoPosition = PhotoPosition.CENTER,
    val repeatSinglePhoto: Boolean = true,
    val autoRotateToCell: Boolean = false,
    val cutGuide: PhotoCutGuide = PhotoCutGuide.NONE
)

data class PhotoCellMm(
    val leftMm: Float,
    val topMm: Float,
    val widthMm: Float,
    val heightMm: Float
)

data class PhotoSheetPlan(
    val sheetIsLandscape: Boolean,
    val sheetWidthMm: Float,
    val sheetHeightMm: Float,
    val marginMm: Float,
    val cols: Int,
    val rows: Int,
    val cellsMm: List<PhotoCellMm>
) {
    val slotsPerPage: Int get() = (cols * rows).coerceAtLeast(1)
}

object PhotoLayoutGeometry {

    fun resolveSheetIsLandscape(
        paperSize: PaperSize,
        orientation: PrintOrientation,
        layout: PhotoLayoutOptions,
        primaryPhotoIsLandscape: Boolean
    ): Boolean {
        return when (orientation) {
            PrintOrientation.PORTRAIT -> false
            PrintOrientation.LANDSCAPE -> true
            PrintOrientation.AUTO -> when (layout.imagesPerPage) {
                ImagesPerPage.ONE -> {
                    if (layout.photoSize != PhotoSizePreset.AUTO && !layout.autoRotateToCell) {
                        layout.photoSize.widthMm > layout.photoSize.heightMm
                    } else {
                        primaryPhotoIsLandscape
                    }
                }
                ImagesPerPage.TWO -> !primaryPhotoIsLandscape
                ImagesPerPage.AUTO_GRID -> {
                    val pColsRows = computeAutoGridDims(
                        printableW = (paperSize.widthMm - 2f * layout.marginPreset.marginMm).coerceAtLeast(10f),
                        printableH = (paperSize.heightMm - 2f * layout.marginPreset.marginMm).coerceAtLeast(10f),
                        layout = layout
                    )
                    val lColsRows = computeAutoGridDims(
                        printableW = (paperSize.heightMm - 2f * layout.marginPreset.marginMm).coerceAtLeast(10f),
                        printableH = (paperSize.widthMm - 2f * layout.marginPreset.marginMm).coerceAtLeast(10f),
                        layout = layout
                    )
                    (lColsRows.first * lColsRows.second) > (pColsRows.first * pColsRows.second)
                }
                else -> primaryPhotoIsLandscape
            }
        }
    }

    fun computeSheetPlan(
        paperSize: PaperSize,
        orientation: PrintOrientation,
        layout: PhotoLayoutOptions,
        primaryPhotoIsLandscape: Boolean
    ): PhotoSheetPlan {
        val isLandscape = resolveSheetIsLandscape(
            paperSize = paperSize,
            orientation = orientation,
            layout = layout,
            primaryPhotoIsLandscape = primaryPhotoIsLandscape
        )
        val sheetW = if (isLandscape) paperSize.heightMm else paperSize.widthMm
        val sheetH = if (isLandscape) paperSize.widthMm else paperSize.heightMm
        val maxMargin = (kotlin.math.min(sheetW, sheetH) - 12f) / 2f
        val margin = layout.marginPreset.marginMm.coerceIn(0f, maxMargin.coerceAtLeast(0f))
        val printableW = (sheetW - 2f * margin).coerceAtLeast(10f)
        val printableH = (sheetH - 2f * margin).coerceAtLeast(10f)
        val gap = layout.gapPreset.gapMm

        val cols: Int
        val rows: Int
        val cellW: Float
        val cellH: Float

        if (layout.imagesPerPage == ImagesPerPage.AUTO_GRID) {
            val (c, r, w, h) = computeAutoGridDims(printableW, printableH, layout)
            cols = c
            rows = r
            cellW = w
            cellH = h
        } else {
            val grid = when (layout.imagesPerPage) {
                ImagesPerPage.ONE -> 1 to 1
                ImagesPerPage.TWO -> if (isLandscape) 2 to 1 else 1 to 2
                ImagesPerPage.FOUR -> 2 to 2
                ImagesPerPage.SIX -> if (isLandscape) 3 to 2 else 2 to 3
                ImagesPerPage.EIGHT -> if (isLandscape) 4 to 2 else 2 to 4
                ImagesPerPage.NINE -> 3 to 3
                ImagesPerPage.AUTO_GRID -> 1 to 1
            }
            cols = grid.first
            rows = grid.second
            val maxCellW = ((printableW - gap * (cols - 1)) / cols).coerceAtLeast(5f)
            val maxCellH = ((printableH - gap * (rows - 1)) / rows).coerceAtLeast(5f)

            if (layout.photoSize == PhotoSizePreset.AUTO) {
                cellW = maxCellW
                cellH = maxCellH
            } else {
                var targetW = layout.photoSize.widthMm
                var targetH = layout.photoSize.heightMm
                if (layout.imagesPerPage == ImagesPerPage.ONE &&
                    layout.autoRotateToCell &&
                    primaryPhotoIsLandscape != (targetW > targetH)
                ) {
                    val tmp = targetW
                    targetW = targetH
                    targetH = tmp
                }
                if (targetW > maxCellW || targetH > maxCellH) {
                    val shrink = kotlin.math.min(maxCellW / targetW, maxCellH / targetH)
                    cellW = (targetW * shrink).coerceAtLeast(5f)
                    cellH = (targetH * shrink).coerceAtLeast(5f)
                } else {
                    cellW = targetW
                    cellH = targetH
                }
            }
        }

        val groupW = cols * cellW + (cols - 1) * gap
        val groupH = rows * cellH + (rows - 1) * gap
        val startX = margin + (printableW - groupW) * layout.position.hBias
        val startY = margin + (printableH - groupH) * layout.position.vBias

        val cells = ArrayList<PhotoCellMm>(cols * rows)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                cells.add(
                    PhotoCellMm(
                        leftMm = startX + c * (cellW + gap),
                        topMm = startY + r * (cellH + gap),
                        widthMm = cellW,
                        heightMm = cellH
                    )
                )
            }
        }

        return PhotoSheetPlan(
            sheetIsLandscape = isLandscape,
            sheetWidthMm = sheetW,
            sheetHeightMm = sheetH,
            marginMm = margin,
            cols = cols,
            rows = rows,
            cellsMm = cells
        )
    }

    private data class AutoGridQuad(val first: Int, val second: Int, val cellW: Float, val cellH: Float)

    private fun computeAutoGridDims(
        printableW: Float,
        printableH: Float,
        layout: PhotoLayoutOptions
    ): AutoGridQuad {
        val gap = layout.gapPreset.gapMm
        val rawW = if (layout.photoSize == PhotoSizePreset.AUTO) 55f else layout.photoSize.widthMm
        val rawH = if (layout.photoSize == PhotoSizePreset.AUTO) 80f else layout.photoSize.heightMm

        fun fitCount(w: Float, h: Float): AutoGridQuad {
            val cw = w.coerceAtMost(printableW)
            val ch = h.coerceAtMost(printableH)
            val c = kotlin.math.floor(((printableW + gap) / (cw + gap)).toDouble()).toInt().coerceAtLeast(1)
            val r = kotlin.math.floor(((printableH + gap) / (ch + gap)).toDouble()).toInt().coerceAtLeast(1)
            return AutoGridQuad(c, r, cw, ch)
        }

        val normal = fitCount(rawW, rawH)
        if (!layout.autoRotateToCell && layout.photoSize != PhotoSizePreset.AUTO) {
            return normal
        }
        val rotated = fitCount(rawH, rawW)
        return if ((rotated.first * rotated.second) > (normal.first * normal.second)) rotated else normal
    }

    fun computeTotalPhotoPages(photoCount: Int, slotsPerPage: Int, repeatSinglePhoto: Boolean): Int {
        if (photoCount <= 0) return 1
        if (photoCount == 1) return 1
        val slots = slotsPerPage.coerceAtLeast(1)
        return (photoCount + slots - 1) / slots
    }
}

enum class PrintQuality(
    val label: String,
    val xDpi: Int,
    val yDpi: Int,
    val economode: Boolean
) {
    DRAFT_TONER_SAVE("Toner Save", 600, 600, true),
    STANDARD_600("600 DPI", 600, 600, false),
    FINE_1200("1200×600", 1200, 600, false);

    val dpi: Int get() = xDpi
}

enum class QpdlDialect(
    val label: String,
    val compByte: Int,
    val isColorDevice: Boolean,
    val planeByte: Int,
    val qpdlVersion: Int,
    val docHeaderByte0: Int,
    val docHeaderByte1: Int,
    val docHeaderByte2: Int,
    val duplexSimplexByte: Int,
    val includeXResInByte16: Boolean
) {
    SCX3200_SPLIX_0X11(
        label = "SCX-3200 (0x11)",
        compByte = 0x11,
        isColorDevice = false,
        planeByte = 0x00,
        qpdlVersion = 3,
        docHeaderByte0 = 0x00,
        docHeaderByte1 = 0x00,
        docHeaderByte2 = 0x01,
        duplexSimplexByte = 0x01,
        includeXResInByte16 = true
    ),
    QPDL_MONO_0X13(
        label = "Mono JBIG (0x13)",
        compByte = 0x13,
        isColorDevice = false,
        planeByte = 0x00,
        qpdlVersion = 3,
        docHeaderByte0 = 0x00,
        docHeaderByte1 = 0x00,
        docHeaderByte2 = 0x01,
        duplexSimplexByte = 0x01,
        includeXResInByte16 = true
    ),
    QPDL_COLOR_0X15(
        label = "QPDL v3 (0x15)",
        compByte = 0x15,
        isColorDevice = true,
        planeByte = 0x04,
        qpdlVersion = 3,
        docHeaderByte0 = 0x00,
        docHeaderByte1 = 0x00,
        docHeaderByte2 = 0x01,
        duplexSimplexByte = 0x00,
        includeXResInByte16 = true
    ),
    RAW_MONO_0X00(
        label = "Mono Raw (0x00)",
        compByte = 0x00,
        isColorDevice = false,
        planeByte = 0x00,
        qpdlVersion = 3,
        docHeaderByte0 = 0x00,
        docHeaderByte1 = 0x00,
        docHeaderByte2 = 0x01,
        duplexSimplexByte = 0x01,
        includeXResInByte16 = true
    )
}

enum class PageRangeMode(val label: String) {
    ALL("All"),
    CURRENT("Current"),
    ODD("Odd"),
    EVEN("Even"),
    CUSTOM("Custom")
}

data class PrintSettings(
    val copies: Int = 1,
    val paperSize: PaperSize = PaperSize.A4,
    val orientation: PrintOrientation = PrintOrientation.PORTRAIT,
    val scaleMode: ScaleMode = ScaleMode.FIT,
    val quality: PrintQuality = PrintQuality.STANDARD_600,
    val dialect: QpdlDialect = QpdlDialect.SCX3200_SPLIX_0X11,
    val pageRangeMode: PageRangeMode = PageRangeMode.ALL,
    val customPageRange: String = "1",
    val photoLayout: PhotoLayoutOptions = PhotoLayoutOptions()
)

object PageRangeParser {
    fun resolvePages(
        mode: PageRangeMode,
        totalPages: Int,
        currentPageIndex: Int,
        customExpression: String
    ): List<Int> {
        if (totalPages <= 0) return emptyList()
        return when (mode) {
            PageRangeMode.ALL -> (0 until totalPages).toList()
            PageRangeMode.CURRENT -> listOf(currentPageIndex.coerceIn(0, totalPages - 1))
            PageRangeMode.ODD -> (0 until totalPages).filter { (it + 1) % 2 == 1 }
            PageRangeMode.EVEN -> (0 until totalPages).filter { (it + 1) % 2 == 0 }
            PageRangeMode.CUSTOM -> parseZeroIndexed(customExpression, totalPages)
        }
    }

    /**
     * Parses a 1-indexed human page range expression like "1-3, 5" into sorted unique
     * 0-indexed page indices within [0, totalPages - 1]. Returns empty list on invalid syntax.
     */
    fun parseZeroIndexed(expression: String, totalPages: Int): List<Int> {
        if (totalPages <= 0) return emptyList()
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) return emptyList()

        val result = linkedSetOf<Int>()
        val tokens = trimmed.split(',')
        for (rawToken in tokens) {
            val token = rawToken.trim()
            if (token.isEmpty()) return emptyList()
            if (token.contains('-')) {
                val parts = token.split('-')
                if (parts.size != 2) return emptyList()
                val start = parts[0].trim().toIntOrNull() ?: return emptyList()
                val end = parts[1].trim().toIntOrNull() ?: return emptyList()
                if (start < 1 || end < start) return emptyList()
                val clampedStart = start.coerceAtMost(totalPages)
                val clampedEnd = end.coerceAtMost(totalPages)
                if (start <= totalPages) {
                    for (p in clampedStart..clampedEnd) {
                        result.add(p - 1)
                    }
                }
            } else {
                val page = token.toIntOrNull() ?: return emptyList()
                if (page < 1) return emptyList()
                if (page <= totalPages) {
                    result.add(page - 1)
                }
            }
        }
        return result.toList().sorted()
    }
}

data class UsbPortFlags(
    val paperEmpty: Boolean = false,
    val selected: Boolean = true,
    val noError: Boolean = true
) {
    val isReady: Boolean get() = selected && noError && !paperEmpty
}

data class UsbDeviceDetails(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val manufacturer: String,
    val productName: String,
    val serialNumber: String?,
    val ieee1284Id: String?,
    val isScx3200: Boolean,
    val portFlags: UsbPortFlags = UsbPortFlags()
) {
    val vidPidHex: String
        get() = "%04X:%04X".format(vendorId, productId)
}

sealed interface UsbPrinterStatus {
    data object UsbNotSupported : UsbPrinterStatus
    data object Disconnected : UsbPrinterStatus
    data class PermissionRequired(val details: UsbDeviceDetails) : UsbPrinterStatus
    data class Connected(val details: UsbDeviceDetails, val isBusy: Boolean = false) : UsbPrinterStatus
    data class Error(val message: String, val details: UsbDeviceDetails? = null) : UsbPrinterStatus
}

enum class PrintPhase(val label: String) {
    IDLE("Idle"),
    RASTERIZING("Rasterizing"),
    ENCODING_SPL("Encoding SPL/QPDL"),
    TRANSFERRING("Sending via USB OTG"),
    COMPLETED("Completed"),
    CANCELLED("Cancelled"),
    FAILED("Failed")
}

data class PrintProgressState(
    val phase: PrintPhase = PrintPhase.IDLE,
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val speedKbPerSec: Float = 0f,
    val bandFraction: Float = 0f,
    val errorMessage: String? = null
) {
    val isActive: Boolean
        get() = phase == PrintPhase.RASTERIZING ||
            phase == PrintPhase.ENCODING_SPL ||
            phase == PrintPhase.TRANSFERRING

    val fraction: Float
        get() = when {
            phase == PrintPhase.COMPLETED -> 1f
            totalBytes > 0L -> (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            totalPages > 0 -> {
                val base = ((currentPage - 1).coerceAtLeast(0).toFloat() + bandFraction.coerceIn(0f, 1f)) /
                    totalPages.toFloat()
                base.coerceIn(0f, 1f)
            }
            else -> 0f
        }
}
