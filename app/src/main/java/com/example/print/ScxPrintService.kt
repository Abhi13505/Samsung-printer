package com.example.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import com.example.data.AppDatabase
import com.example.data.PrintHistoryRepository
import com.example.data.PrintJobRecord
import com.example.data.PrintJobStatus
import com.example.data.PrinterPreferencesRepository
import com.example.document.DocumentEngine
import com.example.document.MonochromeProcessor
import com.example.model.DitherMode
import com.example.model.DocumentType
import com.example.model.PaperSize
import com.example.model.PrintOrientation
import com.example.model.PrintQuality
import com.example.model.PrintSettings
import com.example.model.ScaleMode
import com.example.model.UsbPrinterStatus
import com.example.printer.Algo0x11Encoder
import com.example.printer.SplQpdlEncoder
import com.example.printer.UsbPrinterManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Android System Print Service registering Samsung SCX-3200 USB inside the OS Print dialog
 * so users can print directly from Chrome, Photos, Gmail, or any Android application.
 */
class ScxPrintService : PrintService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var usbManager: UsbPrinterManager
    private lateinit var historyRepository: PrintHistoryRepository
    private val activeJobs = mutableMapOf<String, Job>()

    override fun onCreate() {
        super.onCreate()
        usbManager = UsbPrinterManager(applicationContext).also { it.register() }
        historyRepository = PrintHistoryRepository(
            AppDatabase.getInstance(applicationContext).printJobDao()
        )
    }

    override fun onDestroy() {
        serviceScope.cancel()
        usbManager.unregister()
        super.onDestroy()
    }

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        val printerId = generatePrinterId("scx3200_usb_otg")
        return ScxDiscoverySession(printerId)
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        val jobKey = printJob.id.toString()
        activeJobs.remove(jobKey)?.cancel()
        if (printJob.isStarted || printJob.isQueued) {
            printJob.cancel()
        }
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        val jobKey = printJob.id.toString()
        printJob.start()

        val job = serviceScope.launch {
            val startMs = System.currentTimeMillis()
            val info = printJob.info
            val docInfo = printJob.document.info
            val docTitle = info.label.ifBlank { docInfo.name.ifBlank { "System Print Job" } }
            val attrs = info.attributes
            val copies = info.copies.coerceIn(1, 99)

            val paperSize = mapMediaSize(attrs.mediaSize)
            val hDpi = attrs.resolution?.horizontalDpi ?: 600
            val quality = when {
                hDpi >= 1200 -> PrintQuality.FINE_1200
                hDpi <= 300 -> PrintQuality.DRAFT_TONER_SAVE
                else -> PrintQuality.STANDARD_600
            }
            val orientation = if (attrs.mediaSize?.isPortrait == false) {
                PrintOrientation.LANDSCAPE
            } else {
                PrintOrientation.PORTRAIT
            }
            val prefsDialect = runCatching {
                PrinterPreferencesRepository(applicationContext).defaultsFlow.first().dialect
            }.getOrDefault(com.example.model.QpdlDialect.SCX3200_SPLIX_0X11)
            val settings = PrintSettings(
                copies = copies,
                paperSize = paperSize,
                orientation = orientation,
                scaleMode = ScaleMode.FIT,
                quality = quality,
                dialect = prefsDialect
            )
            var totalPages = 0

            try {
                usbManager.refreshDevices()
                val usbStatus = usbManager.status.value
                if (usbStatus !is UsbPrinterStatus.Connected) {
                    val reason = when (usbStatus) {
                        is UsbPrinterStatus.PermissionRequired ->
                            "Open Samsung Printer to grant USB OTG permission"
                        UsbPrinterStatus.UsbNotSupported ->
                            "USB Host mode unsupported on this device"
                        else ->
                            "Connect Samsung SCX-3200 via USB OTG cable"
                    }
                    printJob.fail(reason)
                    recordHistory(docTitle, 0, settings, 0L, startMs, PrintJobStatus.FAILED, reason)
                    return@launch
                }

                val pfd = printJob.document.data
                    ?: throw IllegalStateException("System print document has no PDF stream")

                val transferResult = pfd.use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        totalPages = renderer.pageCount
                        val xDpi = quality.xDpi
                        val yDpi = quality.yDpi
                        val pjlHeader = SplQpdlEncoder.buildPjlHeader(quality, docTitle)
                        val pjlFooter = SplQpdlEncoder.buildPjlFooter()

                        val streamSequence = sequence {
                            yield(pjlHeader)
                            for (pageIdx in 0 until totalPages) {
                                renderer.openPage(pageIdx).use { page ->
                                    val (pageW, pageH) = MonochromeProcessor.resolvePageDimensions(
                                        paperSize = paperSize,
                                        orientation = orientation,
                                        xDpi = xDpi,
                                        yDpi = yDpi,
                                        contentAspectIsLandscape = page.width > page.height
                                    )
                                    val bandBitmap = Bitmap.createBitmap(
                                        pageW,
                                        SplQpdlEncoder.BAND_HEIGHT_LINES,
                                        Bitmap.Config.ARGB_8888
                                    )
                                    try {
                                        val ditherer = MonochromeProcessor.StreamingBandDitherer(
                                            widthPx = pageW,
                                            brightness = 0f,
                                            contrast = 1.05f,
                                            ditherMode = DitherMode.HALFTONE_PHOTO
                                        )
                                        yield(SplQpdlEncoder.buildPageStartHeader(pageW, pageH, settings))
                                        var y = 0
                                        var bandIndex = 0
                                        while (y < pageH) {
                                            val bandH = minOf(SplQpdlEncoder.BAND_HEIGHT_LINES, pageH - y)
                                            DocumentEngine.renderPdfPageBand(
                                                page = page,
                                                bandBitmap = bandBitmap,
                                                pageW = pageW,
                                                pageH = pageH,
                                                bandY = y,
                                                xDpi = xDpi,
                                                yDpi = yDpi
                                            )
                                            val packed = ditherer.ditherBand(bandBitmap, bandH, y)
                                            val widthBytes = (pageW + 7) / 8
                                            if (settings.dialect.compByte != 0x11 ||
                                                !Algo0x11Encoder.isBandEmpty(packed, widthBytes, bandH)
                                            ) {
                                                yield(
                                                    SplQpdlEncoder.encodeBandRecord(
                                                        bandIndex = bandIndex,
                                                        packedBand1bpp = packed,
                                                        widthPx = pageW,
                                                        bandHeight = bandH,
                                                        dialect = settings.dialect,
                                                        isLastBand = (y + bandH >= pageH)
                                                    )
                                                )
                                            }
                                            y += bandH
                                            bandIndex++

                                            val safeTotal = totalPages.coerceAtLeast(1)
                                            val overall = (pageIdx + (y.toFloat() / pageH)) / safeTotal
                                            printJob.setProgress(overall.coerceIn(0f, 0.98f))
                                        }
                                        yield(SplQpdlEncoder.buildPageEndFooter(settings.copies))
                                    } finally {
                                        bandBitmap.recycle()
                                    }
                                }
                            }
                            yield(pjlFooter)
                        }

                        usbManager.streamPrintJob(
                            chunks = streamSequence,
                            estimatedTotalBytes = 0L
                        ) { _, _ -> }
                    }
                }

                transferResult.fold(
                    onSuccess = { bytesSent ->
                        printJob.setProgress(1f)
                        printJob.complete()
                        recordHistory(
                            docTitle,
                            totalPages,
                            settings,
                            bytesSent,
                            startMs,
                            PrintJobStatus.COMPLETED,
                            null
                        )
                    },
                    onFailure = { error ->
                        val msg = error.message ?: "USB transfer failed"
                        printJob.fail(msg)
                        recordHistory(
                            docTitle,
                            totalPages,
                            settings,
                            0L,
                            startMs,
                            PrintJobStatus.FAILED,
                            msg
                        )
                    }
                )
            } catch (_: CancellationException) {
                recordHistory(docTitle, totalPages, settings, 0L, startMs, PrintJobStatus.CANCELLED, "Cancelled")
            } catch (t: Throwable) {
                val msg = t.message ?: "Unexpected print error"
                printJob.fail(msg)
                recordHistory(docTitle, totalPages, settings, 0L, startMs, PrintJobStatus.FAILED, msg)
            } finally {
                activeJobs.remove(jobKey)
            }
        }
        activeJobs[jobKey] = job
    }

    private suspend fun recordHistory(
        title: String,
        pages: Int,
        settings: PrintSettings,
        bytesSent: Long,
        startMs: Long,
        status: PrintJobStatus,
        error: String?
    ) {
        historyRepository.recordJob(
            PrintJobRecord(
                fileName = title,
                documentType = DocumentType.PDF.displayLabel,
                pageCount = pages.coerceAtLeast(1),
                copies = settings.copies,
                paperSize = settings.paperSize.label,
                qualityLabel = settings.quality.label,
                bytesTransferred = bytesSent,
                durationMs = System.currentTimeMillis() - startMs,
                status = status.name,
                errorMessage = error
            )
        )
    }

    private fun mapMediaSize(mediaSize: PrintAttributes.MediaSize?): PaperSize {
        return when (mediaSize?.id) {
            PrintAttributes.MediaSize.NA_LETTER.id -> PaperSize.LETTER
            PrintAttributes.MediaSize.NA_LEGAL.id -> PaperSize.LEGAL
            PrintAttributes.MediaSize.ISO_A5.id -> PaperSize.A5
            else -> PaperSize.A4
        }
    }

    private inner class ScxDiscoverySession(
        private val printerId: PrinterId
    ) : PrinterDiscoverySession() {

        private var statusObserverJob: Job? = null

        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
            usbManager.refreshDevices()
            statusObserverJob?.cancel()
            statusObserverJob = serviceScope.launch {
                usbManager.status.collectLatest { usbStatus ->
                    publishPrinter(usbStatus)
                }
            }
        }

        override fun onStopPrinterDiscovery() {
            statusObserverJob?.cancel()
            statusObserverJob = null
        }

        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {
            usbManager.refreshDevices()
            publishPrinter(usbManager.status.value)
        }

        override fun onStartPrinterStateTracking(printerId: PrinterId) {
            usbManager.refreshDevices()
            publishPrinter(usbManager.status.value)
        }

        override fun onStopPrinterStateTracking(printerId: PrinterId) = Unit

        override fun onDestroy() {
            statusObserverJob?.cancel()
        }

        private fun publishPrinter(usbStatus: UsbPrinterStatus) {
            val capabilities = PrinterCapabilitiesInfo.Builder(printerId)
                .addMediaSize(PrintAttributes.MediaSize.ISO_A4, true)
                .addMediaSize(PrintAttributes.MediaSize.NA_LETTER, false)
                .addMediaSize(PrintAttributes.MediaSize.NA_LEGAL, false)
                .addMediaSize(PrintAttributes.MediaSize.ISO_A5, false)
                .addResolution(PrintAttributes.Resolution("scx_300", "300 DPI Toner Save", 300, 300), false)
                .addResolution(PrintAttributes.Resolution("scx_600", "600 DPI Standard", 600, 600), true)
                .addResolution(PrintAttributes.Resolution("scx_1200", "1200x600 DPI Fine", 1200, 600), false)
                .setColorModes(
                    PrintAttributes.COLOR_MODE_MONOCHROME,
                    PrintAttributes.COLOR_MODE_MONOCHROME
                )
                .setMinMargins(PrintAttributes.Margins(150, 150, 150, 150))
                .build()

            val (statusFlag, description) = when (usbStatus) {
                is UsbPrinterStatus.Connected -> {
                    if (usbStatus.details.portFlags.paperEmpty) {
                        PrinterInfo.STATUS_UNAVAILABLE to "Paper tray empty"
                    } else if (usbStatus.isBusy) {
                        PrinterInfo.STATUS_BUSY to "Printing over USB OTG"
                    } else {
                        PrinterInfo.STATUS_IDLE to "USB OTG Ready (${usbStatus.details.vidPidHex})"
                    }
                }
                is UsbPrinterStatus.PermissionRequired ->
                    PrinterInfo.STATUS_UNAVAILABLE to "Grant USB permission in Samsung Printer"
                else ->
                    PrinterInfo.STATUS_UNAVAILABLE to "Connect SCX-3200 via USB OTG"
            }

            val printer = PrinterInfo.Builder(
                printerId,
                "Samsung SCX-3200 (USB OTG)",
                statusFlag
            )
                .setDescription(description)
                .setCapabilities(capabilities)
                .build()

            addPrinters(listOf(printer))
        }
    }
}
