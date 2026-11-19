package com.example.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import com.example.model.DitherMode
import com.example.model.ImageAdjustmentState
import com.example.model.PaperSize
import com.example.model.PhotoCutGuide
import com.example.model.PhotoLayoutGeometry
import com.example.model.PhotoLayoutOptions
import com.example.model.PhotoSheetPlan
import com.example.model.PrintOrientation
import com.example.model.ScaleMode
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Handles resilient image decoding (JPEG/PNG/WebP/HEIC/AVIF + Photo Picker URIs),
 * crop/rotate transformations, multi-photo physical layout & streaming band composition,
 * and low-memory monochrome laser dithering for both UI preview and 1bpp print bands.
 */
object MonochromeProcessor {

    private const val MAX_FALLBACK_STREAM_BYTES = 32 * 1024 * 1024

    private val BAYER_4X4 = arrayOf(
        intArrayOf(15, 135, 45, 165),
        intArrayOf(195, 75, 225, 105),
        intArrayOf(60, 180, 30, 150),
        intArrayOf(240, 120, 210, 90)
    )

    fun loadScaledBitmap(
        context: Context,
        uri: Uri,
        maxDimension: Int
    ): Bitmap? {
        // Tier 1: Modern ImageDecoder (API 28+) handles HEIC/AVIF/WebP/EXIF/PhotoPicker natively
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val decoded = decodeViaImageDecoder(context, uri, maxDimension)
            if (decoded != null) return decoded
        }

        // Tier 2: FileDescriptor decode (seekable, handles many Gallery/SAF providers cleanly)
        val fromFd = decodeViaFileDescriptor(context, uri, maxDimension)
        if (fromFd != null) return fromFd

        // Tier 3: Buffered InputStream fallback for virtual/non-seekable streams
        return decodeViaStreamBytes(context, uri, maxDimension)
    }

    fun readImageDimensions(context: Context, uri: Uri): Pair<Int, Int>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val dims = readDimensionsViaImageDecoder(context, uri)
            if (dims != null) return dims
        }

        // Try FileDescriptor bounds + EXIF
        val fdDims = runCatching {
            openSeekableAssetOrPfd(context, uri)?.use { afdOrPfd ->
                val fd = when (afdOrPfd) {
                    is android.content.res.AssetFileDescriptor -> afdOrPfd.fileDescriptor
                    is android.os.ParcelFileDescriptor -> afdOrPfd.fileDescriptor
                    else -> null
                } ?: return@use null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(fd, null, bounds)
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    adjustDimsForExif(context, uri, bounds.outWidth, bounds.outHeight)
                } else null
            }
        }.getOrNull()
        if (fdDims != null) return fdDims

        // Try InputStream bounds
        val streamDims = runCatching {
            openBestInputStream(context, uri)?.use { stream ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, bounds)
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    adjustDimsForExif(context, uri, bounds.outWidth, bounds.outHeight)
                } else null
            }
        }.getOrNull()
        if (streamDims != null) return streamDims

        // Last-ditch: decode scaled bitmap directly and inspect its dimensions
        val fallbackBmp = decodeViaStreamBytes(context, uri, 1024) ?: return null
        val result = fallbackBmp.width to fallbackBmp.height
        fallbackBmp.recycle()
        return if (result.first > 0 && result.second > 0) result else null
    }

    private fun readDimensionsViaImageDecoder(context: Context, uri: Uri): Pair<Int, Int>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return runCatching {
            var width = 0
            var height = 0
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val tiny = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                width = info.size.width
                height = info.size.height
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSize(
                    width.coerceIn(1, 8),
                    height.coerceIn(1, 8)
                )
            }
            tiny.recycle()
            if (width > 0 && height > 0) width to height else null
        }.getOrNull()
    }

    private fun decodeViaImageDecoder(
        context: Context,
        uri: Uri,
        maxDimension: Int
    ): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                val srcW = info.size.width.coerceAtLeast(1)
                val srcH = info.size.height.coerceAtLeast(1)
                val maxSide = max(srcW, srcH)
                if (maxSide > maxDimension) {
                    val scale = maxDimension.toFloat() / maxSide.toFloat()
                    val dstW = (srcW * scale).roundToInt().coerceAtLeast(1)
                    val dstH = (srcH * scale).roundToInt().coerceAtLeast(1)
                    decoder.setTargetSize(dstW, dstH)
                }
            }
        }.getOrNull()
    }

    private fun decodeViaFileDescriptor(
        context: Context,
        uri: Uri,
        maxDimension: Int
    ): Bitmap? {
        return runCatching {
            openSeekableAssetOrPfd(context, uri)?.use { handle ->
                val fd = when (handle) {
                    is android.content.res.AssetFileDescriptor -> handle.fileDescriptor
                    is android.os.ParcelFileDescriptor -> handle.fileDescriptor
                    else -> null
                } ?: return@use null

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(fd, null, bounds)
                val srcW = bounds.outWidth
                val srcH = bounds.outHeight
                if (srcW <= 0 || srcH <= 0) return@use null

                val sampleSize = computeInSampleSize(srcW, srcH, maxDimension)
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val raw = BitmapFactory.decodeFileDescriptor(fd, null, opts) ?: return@use null
                applyExifAndCapSize(context, uri, raw, maxDimension)
            }
        }.getOrNull()
    }

    private fun decodeViaStreamBytes(
        context: Context,
        uri: Uri,
        maxDimension: Int
    ): Bitmap? {
        return runCatching {
            val bytes = openBestInputStream(context, uri)?.use { readBoundedBytes(it) } ?: return null
            if (bytes.isEmpty()) return null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sampleSize = if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                computeInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            } else {
                1
            }

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            applyExifAndCapSize(context, uri, raw, maxDimension)
        }.getOrNull()
    }

    private fun openSeekableAssetOrPfd(context: Context, uri: Uri): java.io.Closeable? {
        return runCatching {
            context.contentResolver.openTypedAssetFileDescriptor(uri, "image/*", null)
        }.getOrNull() ?: runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")
        }.getOrNull()
    }

    private fun openBestInputStream(context: Context, uri: Uri): InputStream? {
        return runCatching {
            context.contentResolver.openInputStream(uri)
        }.getOrNull() ?: runCatching {
            context.contentResolver.openTypedAssetFileDescriptor(uri, "image/*", null)?.createInputStream()
        }.getOrNull()
    }

    private fun readBoundedBytes(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (total < MAX_FALLBACK_STREAM_BYTES) {
            val read = input.read(buf, 0, min(buf.size, MAX_FALLBACK_STREAM_BYTES - total))
            if (read <= 0) break
            out.write(buf, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    private fun computeInSampleSize(srcW: Int, srcH: Int, maxDimension: Int): Int {
        var sampleSize = 1
        while ((srcW / sampleSize) > maxDimension * 2 || (srcH / sampleSize) > maxDimension * 2) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun readExifOrientation(context: Context, uri: Uri): Int {
        return runCatching {
            openBestInputStream(context, uri)?.use { stream ->
                android.media.ExifInterface(stream).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrNull() ?: android.media.ExifInterface.ORIENTATION_NORMAL
    }

    private fun adjustDimsForExif(context: Context, uri: Uri, width: Int, height: Int): Pair<Int, Int> {
        return when (readExifOrientation(context, uri)) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90,
            android.media.ExifInterface.ORIENTATION_ROTATE_270,
            android.media.ExifInterface.ORIENTATION_TRANSPOSE,
            android.media.ExifInterface.ORIENTATION_TRANSVERSE -> height to width
            else -> width to height
        }
    }

    private fun applyExifAndCapSize(
        context: Context,
        uri: Uri,
        decoded: Bitmap,
        maxDimension: Int
    ): Bitmap {
        val matrix = Matrix()
        var needsTransform = false
        when (readExifOrientation(context, uri)) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> {
                matrix.postRotate(90f)
                needsTransform = true
            }
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> {
                matrix.postRotate(180f)
                needsTransform = true
            }
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> {
                matrix.postRotate(270f)
                needsTransform = true
            }
            android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
                matrix.postScale(-1f, 1f)
                needsTransform = true
            }
            android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.postScale(1f, -1f)
                needsTransform = true
            }
        }
        val maxSide = max(decoded.width, decoded.height)
        if (maxSide > maxDimension) {
            val scale = maxDimension.toFloat() / maxSide.toFloat()
            matrix.postScale(scale, scale)
            needsTransform = true
        }
        if (!needsTransform) return decoded
        val transformed = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (transformed !== decoded) decoded.recycle()
        return transformed
    }

    /**
     * Applies normalized crop and rotation, returning a new bitmap (or the input if unchanged).
     */
    fun prepareCroppedRotatedBitmap(
        source: Bitmap,
        adjustments: ImageAdjustmentState
    ): Bitmap {
        val left = (adjustments.cropLeft.coerceIn(0f, 0.95f) * source.width).roundToInt()
        val top = (adjustments.cropTop.coerceIn(0f, 0.95f) * source.height).roundToInt()
        val right = (adjustments.cropRight.coerceIn(adjustments.cropLeft + 0.05f, 1f) * source.width).roundToInt()
        val bottom = (adjustments.cropBottom.coerceIn(adjustments.cropTop + 0.05f, 1f) * source.height).roundToInt()
        val cropW = (right - left).coerceAtLeast(1)
        val cropH = (bottom - top).coerceAtLeast(1)

        val cropped = if (left == 0 && top == 0 && cropW == source.width && cropH == source.height) {
            source
        } else {
            Bitmap.createBitmap(source, left, top, cropW, cropH)
        }

        val rotated = if (adjustments.rotationDegrees % 360 != 0) {
            val matrix = Matrix().apply { postRotate((adjustments.rotationDegrees % 360).toFloat()) }
            Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true)
        } else {
            cropped
        }

        if (rotated !== cropped && cropped !== source) {
            cropped.recycle()
        }
        return rotated
    }

    fun resolvePageDimensions(
        paperSize: PaperSize,
        orientation: PrintOrientation,
        xDpi: Int,
        yDpi: Int,
        contentAspectIsLandscape: Boolean
    ): Pair<Int, Int> {
        val isLandscape = when (orientation) {
            PrintOrientation.PORTRAIT -> false
            PrintOrientation.LANDSCAPE -> true
            PrintOrientation.AUTO -> contentAspectIsLandscape
        }
        return resolvePageDimensionsForOrientation(paperSize, isLandscape, xDpi, yDpi)
    }

    fun resolvePageDimensionsForOrientation(
        paperSize: PaperSize,
        sheetIsLandscape: Boolean,
        xDpi: Int,
        yDpi: Int
    ): Pair<Int, Int> {
        return if (sheetIsLandscape) {
            val rawW = (paperSize.heightMm / 25.4f * xDpi).roundToInt()
            val w = ((rawW + 16) and 31.inv()).coerceAtLeast(64)
            val h = (paperSize.widthMm / 25.4f * yDpi).roundToInt()
            w to h.coerceAtLeast(64)
        } else {
            paperSize.pixelWidth(xDpi).coerceAtLeast(64) to paperSize.pixelHeight(yDpi).coerceAtLeast(64)
        }
    }

    /**
     * Draws one horizontal band [bandY, bandY + bandH) of a PhotoSheetPlan with one or more photos.
     * Single source of truth shared between UI preview and physical 600/1200 DPI QPDL printing.
     */
    fun drawPhotoSheetBand(
        photosForSheet: List<Bitmap>,
        sheetPlan: PhotoSheetPlan,
        bandBitmap: Bitmap,
        pageW: Int,
        pageH: Int,
        bandY: Int,
        scaleMode: ScaleMode,
        layout: PhotoLayoutOptions
    ) {
        bandBitmap.eraseColor(Color.WHITE)
        if (photosForSheet.isEmpty() || sheetPlan.cellsMm.isEmpty()) return

        val canvas = Canvas(bandBitmap)
        val pxPerMmX = pageW.toFloat() / sheetPlan.sheetWidthMm
        val pxPerMmY = pageH.toFloat() / sheetPlan.sheetHeightMm
        val aspectAdjustX = pxPerMmX / pxPerMmY.coerceAtLeast(0.01f)

        val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val guideStrokePx = max(1.2f, 0.3f * pxPerMmY)
        val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = guideStrokePx
        }
        val cutMarkLenX = 2.6f * pxPerMmX
        val cutMarkLenY = 2.6f * pxPerMmY

        val bandH = bandBitmap.height.toFloat()

        for ((slotIdx, cellMm) in sheetPlan.cellsMm.withIndex()) {
            val photo = photosForSheet.getOrNull(slotIdx) ?: continue
            val cellL = cellMm.leftMm * pxPerMmX
            val cellT = cellMm.topMm * pxPerMmY - bandY
            val cellW = cellMm.widthMm * pxPerMmX
            val cellH = cellMm.heightMm * pxPerMmY
            val cellR = cellL + cellW
            val cellB = cellT + cellH

            // Skip cells completely outside this horizontal band
            if (cellB < -guideStrokePx || cellT > bandH + guideStrokePx) continue

            val logicalCellW = cellW / aspectAdjustX
            val rotatePhoto90 = layout.autoRotateToCell &&
                ((photo.width > photo.height) != (logicalCellW > cellH))
            val effectiveW = if (rotatePhoto90) photo.height.toFloat() else photo.width.toFloat()
            val effectiveH = if (rotatePhoto90) photo.width.toFloat() else photo.height.toFloat()

            canvas.save()
            canvas.clipRect(cellL, cellT, cellR, cellB)

            when (scaleMode) {
                ScaleMode.STRETCH -> {
                    val drawRect = RectF(cellL, cellT, cellR, cellB)
                    drawPossiblyRotatedBitmap(canvas, photo, drawRect, rotatePhoto90, imagePaint)
                }
                ScaleMode.FILL -> {
                    val scale = max(logicalCellW / effectiveW, cellH / effectiveH)
                    val drawW = effectiveW * scale * aspectAdjustX
                    val drawH = effectiveH * scale
                    val drawL = cellL + (cellW - drawW) * 0.5f
                    val drawT = cellT + (cellH - drawH) * 0.5f
                    drawPossiblyRotatedBitmap(
                        canvas,
                        photo,
                        RectF(drawL, drawT, drawL + drawW, drawT + drawH),
                        rotatePhoto90,
                        imagePaint
                    )
                }
                ScaleMode.FIT -> {
                    val scale = min(logicalCellW / effectiveW, cellH / effectiveH)
                    val drawW = effectiveW * scale * aspectAdjustX
                    val drawH = effectiveH * scale
                    val hBias = if (sheetPlan.slotsPerPage == 1) layout.position.hBias else 0.5f
                    val vBias = if (sheetPlan.slotsPerPage == 1) layout.position.vBias else 0.5f
                    val drawL = cellL + (cellW - drawW) * hBias
                    val drawT = cellT + (cellH - drawH) * vBias
                    drawPossiblyRotatedBitmap(
                        canvas,
                        photo,
                        RectF(drawL, drawT, drawL + drawW, drawT + drawH),
                        rotatePhoto90,
                        imagePaint
                    )
                }
            }
            canvas.restore()

            when (layout.cutGuide) {
                PhotoCutGuide.NONE -> Unit
                PhotoCutGuide.THIN_BORDER -> {
                    canvas.drawRect(cellL, cellT, cellR, cellB, guidePaint)
                }
                PhotoCutGuide.CORNER_MARKS -> {
                    // Top-left
                    canvas.drawLine(cellL, cellT, cellL + cutMarkLenX, cellT, guidePaint)
                    canvas.drawLine(cellL, cellT, cellL, cellT + cutMarkLenY, guidePaint)
                    // Top-right
                    canvas.drawLine(cellR - cutMarkLenX, cellT, cellR, cellT, guidePaint)
                    canvas.drawLine(cellR, cellT, cellR, cellT + cutMarkLenY, guidePaint)
                    // Bottom-left
                    canvas.drawLine(cellL, cellB, cellL + cutMarkLenX, cellB, guidePaint)
                    canvas.drawLine(cellL, cellB - cutMarkLenY, cellL, cellB, guidePaint)
                    // Bottom-right
                    canvas.drawLine(cellR - cutMarkLenX, cellB, cellR, cellB, guidePaint)
                    canvas.drawLine(cellR, cellB - cutMarkLenY, cellR, cellB, guidePaint)
                }
            }
        }
    }

    private fun drawPossiblyRotatedBitmap(
        canvas: Canvas,
        bitmap: Bitmap,
        dstRect: RectF,
        rotate90: Boolean,
        paint: Paint
    ) {
        if (!rotate90) {
            canvas.drawBitmap(bitmap, null, dstRect, paint)
            return
        }
        val matrix = Matrix().apply {
            // Map rotated bitmap [0..h, 0..w] onto dstRect
            postTranslate(-bitmap.width / 2f, -bitmap.height / 2f)
            postRotate(90f)
            postScale(
                dstRect.width() / bitmap.height.toFloat().coerceAtLeast(1f),
                dstRect.height() / bitmap.width.toFloat().coerceAtLeast(1f)
            )
            postTranslate(dstRect.centerX(), dstRect.centerY())
        }
        canvas.drawBitmap(bitmap, matrix, paint)
    }

    /**
     * UI-preview helper: composes a full preview-resolution multi-photo paper sheet bitmap.
     */
    fun composePhotoSheetPreview(
        preparedPhotosForSheet: List<Bitmap>,
        sheetPlan: PhotoSheetPlan,
        scaleMode: ScaleMode,
        layout: PhotoLayoutOptions,
        targetWidthPx: Int
    ): Bitmap {
        val sheetW = ((targetWidthPx + 7) and 7.inv()).coerceAtLeast(64)
        val sheetAspect = sheetPlan.sheetHeightMm / sheetPlan.sheetWidthMm
        val sheetH = (sheetW * sheetAspect).roundToInt().coerceAtLeast(64)

        val sheet = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888)
        drawPhotoSheetBand(
            photosForSheet = preparedPhotosForSheet,
            sheetPlan = sheetPlan,
            bandBitmap = sheet,
            pageW = sheetW,
            pageH = sheetH,
            bandY = 0,
            scaleMode = scaleMode,
            layout = layout
        )
        return sheet
    }

    /**
     * Backward-compatible single-image preview helper.
     */
    fun composeOnPaperSheet(
        source: Bitmap,
        adjustments: ImageAdjustmentState,
        paperSize: PaperSize,
        orientation: PrintOrientation,
        scaleMode: ScaleMode,
        targetWidthPx: Int,
        layout: PhotoLayoutOptions = PhotoLayoutOptions()
    ): Bitmap {
        val prepared = prepareCroppedRotatedBitmap(source, adjustments)
        try {
            val plan = PhotoLayoutGeometry.computeSheetPlan(
                paperSize = paperSize,
                orientation = orientation,
                layout = layout,
                primaryPhotoIsLandscape = prepared.width > prepared.height
            )
            val sheetPhotos = if (plan.slotsPerPage > 1 && layout.repeatSinglePhoto) {
                List(plan.slotsPerPage) { prepared }
            } else {
                listOf(prepared)
            }
            return composePhotoSheetPreview(
                preparedPhotosForSheet = sheetPhotos,
                sheetPlan = plan,
                scaleMode = scaleMode,
                layout = layout,
                targetWidthPx = targetWidthPx
            )
        } finally {
            if (prepared !== source) prepared.recycle()
        }
    }

    /**
     * Stateful band-by-band 1bpp ditherer that reuses tiny row buffers (< 1 MB) across
     * all 128-line bands of a page—eliminating OutOfMemoryError at 600 / 1200 DPI.
     */
    class StreamingBandDitherer(
        private val widthPx: Int,
        private val brightness: Float,
        private val contrast: Float,
        private val ditherMode: DitherMode
    ) {
        private val widthBytes = (widthPx + 7) / 8
        private val brightOffset = brightness * 255f
        private val errCurr = IntArray(widthPx)
        private val errNext = IntArray(widthPx)
        private var pixelScratch = IntArray(widthPx * 128)

        fun ditherBand(
            bandBitmap: Bitmap,
            bandHeight: Int,
            globalStartY: Int
        ): ByteArray {
            val needed = widthPx * bandHeight
            if (pixelScratch.size < needed) {
                pixelScratch = IntArray(needed)
            }
            bandBitmap.getPixels(pixelScratch, 0, widthPx, 0, 0, widthPx, bandHeight)
            val packed = ByteArray(widthBytes * bandHeight)

            when (ditherMode) {
                DitherMode.HALFTONE_PHOTO -> {
                    for (localY in 0 until bandHeight) {
                        errNext.fill(0)
                        val rowOffset = localY * widthPx
                        val packedRowOffset = localY * widthBytes
                        for (x in 0 until widthPx) {
                            val c = pixelScratch[rowOffset + x]
                            val r = (c ushr 16) and 0xFF
                            val g = (c ushr 8) and 0xFF
                            val b = c and 0xFF
                            val lum = (0.299f * r + 0.587f * g + 0.114f * b - 128f) * contrast + 128f + brightOffset
                            val oldVal = (lum.roundToInt() + errCurr[x]).coerceIn(0, 255)
                            val isBlack = oldVal < 128
                            val newVal = if (isBlack) 0 else 255
                            val err = oldVal - newVal

                            if (isBlack) {
                                val idx = packedRowOffset + (x ushr 3)
                                packed[idx] = (packed[idx].toInt() or (1 shl (7 - (x and 7)))).toByte()
                            }

                            if (x + 1 < widthPx) errCurr[x + 1] += (err * 7) shr 4
                            if (x - 1 >= 0) errNext[x - 1] += (err * 3) shr 4
                            errNext[x] += (err * 5) shr 4
                            if (x + 1 < widthPx) errNext[x + 1] += err shr 4
                        }
                        System.arraycopy(errNext, 0, errCurr, 0, widthPx)
                    }
                }

                DitherMode.THRESHOLD_DOCUMENT -> {
                    for (localY in 0 until bandHeight) {
                        val rowOffset = localY * widthPx
                        val packedRowOffset = localY * widthBytes
                        for (x in 0 until widthPx) {
                            val c = pixelScratch[rowOffset + x]
                            val r = (c ushr 16) and 0xFF
                            val g = (c ushr 8) and 0xFF
                            val b = c and 0xFF
                            val lum = ((0.299f * r + 0.587f * g + 0.114f * b - 128f) * contrast + 128f + brightOffset)
                                .roundToInt().coerceIn(0, 255)
                            if (lum < 145) {
                                val idx = packedRowOffset + (x ushr 3)
                                packed[idx] = (packed[idx].toInt() or (1 shl (7 - (x and 7)))).toByte()
                            }
                        }
                    }
                }

                DitherMode.GRAYSCALE_TONE -> {
                    for (localY in 0 until bandHeight) {
                        val rowOffset = localY * widthPx
                        val packedRowOffset = localY * widthBytes
                        val bayerRow = BAYER_4X4[(globalStartY + localY) and 3]
                        for (x in 0 until widthPx) {
                            val c = pixelScratch[rowOffset + x]
                            val r = (c ushr 16) and 0xFF
                            val g = (c ushr 8) and 0xFF
                            val b = c and 0xFF
                            val lum = ((0.299f * r + 0.587f * g + 0.114f * b - 128f) * contrast + 128f + brightOffset)
                                .roundToInt().coerceIn(0, 255)
                            if (lum < bayerRow[x and 3]) {
                                val idx = packedRowOffset + (x ushr 3)
                                packed[idx] = (packed[idx].toInt() or (1 shl (7 - (x and 7)))).toByte()
                            }
                        }
                    }
                }
            }
            return packed
        }
    }

    /**
     * Converts a preview-sized sheet bitmap into a 1-bit preview Bitmap and packed 1bpp bytes.
     */
    fun rasterizeMonochrome(
        sheet: Bitmap,
        brightness: Float,
        contrast: Float,
        ditherMode: DitherMode,
        producePreviewBitmap: Boolean
    ): Pair<Bitmap?, ByteArray> {
        val w = sheet.width
        val h = sheet.height
        val widthBytes = (w + 7) / 8
        val ditherer = StreamingBandDitherer(w, brightness, contrast, ditherMode)
        val packed = ditherer.ditherBand(sheet, h, 0)

        val previewBitmap = if (producePreviewBitmap) {
            val outPixels = IntArray(w * h)
            for (y in 0 until h) {
                val packedRow = y * widthBytes
                val outRow = y * w
                for (x in 0 until w) {
                    val b = packed[packedRow + (x ushr 3)].toInt() and 0xFF
                    val isBlack = ((b ushr (7 - (x and 7))) and 1) == 1
                    outPixels[outRow + x] = if (isBlack) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                setPixels(outPixels, 0, w, 0, 0, w, h)
            }
        } else {
            null
        }
        return previewBitmap to packed
    }
}
