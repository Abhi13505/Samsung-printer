package com.example.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import com.example.model.PaperSize
import com.example.model.PrintOrientation
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Handles PDF rendering via Android PdfRenderer (both preview and band-by-band print rendering)
 * and content display name resolution.
 */
object DocumentEngine {

    fun queryDisplayName(context: Context, uri: Uri, fallback: String): String {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback
    }

    fun getPdfPageCount(context: Context, uri: Uri): Int {
        return context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                renderer.pageCount
            }
        } ?: 0
    }

    fun renderPdfPage(
        context: Context,
        uri: Uri,
        pageIndex: Int,
        paperSize: PaperSize,
        orientation: PrintOrientation,
        targetWidthPx: Int
    ): Bitmap? {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        return pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (pageIndex < 0 || pageIndex >= renderer.pageCount) return null
                renderer.openPage(pageIndex).use { page ->
                    val (sheetW, sheetH) = MonochromeProcessor.resolvePageDimensions(
                        paperSize = paperSize,
                        orientation = orientation,
                        xDpi = 600,
                        yDpi = 600,
                        contentAspectIsLandscape = page.width > page.height
                    )
                    val scaleDown = targetWidthPx.toFloat() / sheetW.toFloat()
                    val outW = ((sheetW * scaleDown).roundToInt() + 7) and 7.inv()
                    val outH = (sheetH * scaleDown).roundToInt().coerceAtLeast(64)

                    val bitmap = Bitmap.createBitmap(outW.coerceAtLeast(64), outH, Bitmap.Config.ARGB_8888)
                    renderPdfPageBand(page, bitmap, outW.coerceAtLeast(64), outH, bandY = 0, xDpi = 600, yDpi = 600)
                    bitmap
                }
            }
        }
    }

    /**
     * Renders a single horizontal band [bandY, bandY + bandBitmap.height) of an open PdfRenderer.Page.
     * Supports anisotropic DPI (e.g. 1200x600) without full-page bitmap allocation.
     */
    fun renderPdfPageBand(
        page: PdfRenderer.Page,
        bandBitmap: Bitmap,
        pageW: Int,
        pageH: Int,
        bandY: Int,
        xDpi: Int,
        yDpi: Int
    ) {
        bandBitmap.eraseColor(Color.WHITE)
        val aspectAdjustX = xDpi.toFloat() / yDpi.toFloat().coerceAtLeast(1f)
        val logicalPageW = pageW / aspectAdjustX
        val scale = min(
            logicalPageW / page.width.toFloat().coerceAtLeast(1f),
            pageH.toFloat() / page.height.toFloat().coerceAtLeast(1f)
        )
        val dstW = page.width * scale * aspectAdjustX
        val dstH = page.height * scale
        val offsetX = (pageW - dstW) / 2f
        val offsetY = (pageH - dstH) / 2f

        val matrix = Matrix().apply {
            postScale(scale * aspectAdjustX, scale)
            postTranslate(offsetX, offsetY - bandY.toFloat())
        }
        page.render(bandBitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
    }
}
