package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.document.DocumentEngine
import com.example.document.MonochromeProcessor
import com.example.model.DitherMode
import com.example.model.PaperSize
import com.example.model.PrintOrientation
import com.example.model.PrintQuality
import com.example.model.PrintSettings
import com.example.printer.SplQpdlEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Samsung Printer", appName)
    }

    @Test
    fun `diagnostic test sheet rasterizes and encodes into valid QPDL stream`() {
        val sheet = SplQpdlEncoder.createDiagnosticTestPageBitmap(
            paperSize = PaperSize.A4,
            deviceDetails = null,
            previewScaleWidth = 256
        )
        assertEquals(256, sheet.width)
        val (preview, packed) = MonochromeProcessor.rasterizeMonochrome(
            sheet = sheet,
            brightness = 0f,
            contrast = 1.0f,
            ditherMode = DitherMode.THRESHOLD_DOCUMENT,
            producePreviewBitmap = true
        )
        assertNotNull(preview)
        assertEquals((256 / 8) * sheet.height, packed.size)

        val encoded = SplQpdlEncoder.encodePage(
            packed1bpp = packed,
            widthPx = sheet.width,
            heightPx = sheet.height,
            settings = PrintSettings(paperSize = PaperSize.A4, quality = PrintQuality.STANDARD_600)
        )
        assertTrue(encoded.isNotEmpty())
        assertEquals(0x00, encoded[0].toInt() and 0xFF) // RECTYPE 0x00 page start
        assertEquals(0x0C, encoded[17].toInt() and 0xFF) // RECTYPE 0x0C first band
        assertEquals(0x11, encoded[23].toInt() and 0xFF) // Algo 0x11 compression at byte 6 of 11B header
        assertEquals(0xEF, encoded[28].toInt() and 0xFF) // 0x09ABCDEF LE subheader magic
        assertEquals(0xCD, encoded[29].toInt() and 0xFF)
        assertEquals(0xAB, encoded[30].toInt() and 0xFF)
        assertEquals(0x09, encoded[31].toInt() and 0xFF)
        sheet.recycle()
        preview?.recycle()
    }

    @Test
    fun `streaming band ditherer encodes diagnostic sheet bands with bounded memory`() {
        val (pageW, pageH) = MonochromeProcessor.resolvePageDimensions(
            paperSize = PaperSize.A4,
            orientation = PrintOrientation.PORTRAIT,
            xDpi = 300,
            yDpi = 300,
            contentAspectIsLandscape = false
        )
        val bandBitmap = Bitmap.createBitmap(
            pageW,
            SplQpdlEncoder.BAND_HEIGHT_LINES,
            Bitmap.Config.ARGB_8888
        )
        val ditherer = MonochromeProcessor.StreamingBandDitherer(
            widthPx = pageW,
            brightness = 0f,
            contrast = 1.0f,
            ditherMode = DitherMode.HALFTONE_PHOTO
        )
        var y = 0
        var bandIndex = 0
        var totalEncoded = 0
        while (y < pageH) {
            val bandH = minOf(SplQpdlEncoder.BAND_HEIGHT_LINES, pageH - y)
            SplQpdlEncoder.drawDiagnosticTestPageBand(
                bandBitmap = bandBitmap,
                pageW = pageW,
                pageH = pageH,
                bandY = y,
                xDpi = 300,
                yDpi = 300,
                paperSize = PaperSize.A4,
                deviceDetails = null
            )
            val packed = ditherer.ditherBand(bandBitmap, bandH, y)
            assertEquals(((pageW + 7) / 8) * bandH, packed.size)
            val record = SplQpdlEncoder.encodeBandRecord(bandIndex, packed, pageW, bandH)
            totalEncoded += record.size
            y += bandH
            bandIndex++
        }
        bandBitmap.recycle()
        assertTrue(bandIndex > 10)
        assertTrue(totalEncoded > 0)
    }

    @Test
    fun `photo sheet multi-up composition and resilient uri decoding work cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val src = Bitmap.createBitmap(200, 140, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.DKGRAY)
        }
        val tmpFile = java.io.File(context.cacheDir, "test_photo_sample.png")
        tmpFile.outputStream().use { out ->
            src.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        src.recycle()

        val uri = android.net.Uri.fromFile(tmpFile)
        val dims = MonochromeProcessor.readImageDimensions(context, uri)
        assertNotNull(dims)
        assertEquals(200, dims?.first)
        assertEquals(140, dims?.second)

        val decoded = MonochromeProcessor.loadScaledBitmap(context, uri, 512)
        assertNotNull(decoded)

        val layout = com.example.model.PhotoLayoutOptions(
            imagesPerPage = com.example.model.ImagesPerPage.FOUR,
            photoSize = com.example.model.PhotoSizePreset.PASSPORT,
            marginPreset = com.example.model.PhotoMarginPreset.NARROW,
            position = com.example.model.PhotoPosition.CENTER,
            repeatSinglePhoto = true,
            cutGuide = com.example.model.PhotoCutGuide.THIN_BORDER
        )
        val plan = com.example.model.PhotoLayoutGeometry.computeSheetPlan(
            paperSize = PaperSize.A4,
            orientation = PrintOrientation.PORTRAIT,
            layout = layout,
            primaryPhotoIsLandscape = false
        )
        assertEquals(4, plan.cellsMm.size)
        val sheet = MonochromeProcessor.composePhotoSheetPreview(
            preparedPhotosForSheet = List(4) { decoded!! },
            sheetPlan = plan,
            scaleMode = com.example.model.ScaleMode.FILL,
            layout = layout,
            targetWidthPx = 480
        )
        assertEquals(480, sheet.width)
        assertEquals(Color.WHITE, sheet.getPixel(0, 0))
        sheet.recycle()
        decoded?.recycle()
        tmpFile.delete()
    }
}
