package com.example.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.PrintHistoryRepository
import com.example.data.PrintJobRecord
import com.example.data.PrintJobStatus
import com.example.data.PrinterPreferencesRepository
import com.example.document.DocumentEngine
import com.example.document.MonochromeProcessor
import com.example.model.DitherMode
import com.example.model.DocumentSource
import com.example.model.DocumentType
import com.example.model.ImageAdjustmentState
import com.example.model.PageRangeMode
import com.example.model.PageRangeParser
import com.example.model.PhotoItem
import com.example.model.PhotoLayoutGeometry
import com.example.model.PhotoLayoutOptions
import com.example.model.PhotoSheetPlan
import com.example.model.PrintOrientation
import com.example.model.PrintPhase
import com.example.model.PrintProgressState
import com.example.model.PrintSettings
import com.example.model.ScaleMode
import com.example.model.UsbPrinterStatus
import com.example.printer.Algo0x11Encoder
import com.example.printer.SplQpdlEncoder
import com.example.printer.UsbPrinterManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PrintStudioUiState(
    val document: DocumentSource? = null,
    val previewBitmap: Bitmap? = null,
    val isRenderingPreview: Boolean = false,
    val currentPageIndex: Int = 0,
    val adjustments: ImageAdjustmentState = ImageAdjustmentState(),
    val settings: PrintSettings = PrintSettings(),
    val progress: PrintProgressState = PrintProgressState(),
    val snackbarMessage: String? = null,
    val isTestPageSelected: Boolean = false
)

class PrintStudioViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val usbPrinterManager = UsbPrinterManager(appContext)
    private val historyRepository = PrintHistoryRepository(
        AppDatabase.getInstance(appContext).printJobDao()
    )
    private val prefsRepository = PrinterPreferencesRepository(appContext)

    val usbStatus: StateFlow<UsbPrinterStatus> = usbPrinterManager.status
    val printHistory: StateFlow<List<PrintJobRecord>> = historyRepository.allJobs
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _uiState = MutableStateFlow(PrintStudioUiState())
    val uiState: StateFlow<PrintStudioUiState> = _uiState.asStateFlow()

    private var previewJob: Job? = null
    private var printJob: Job? = null

    init {
        usbPrinterManager.register()
        viewModelScope.launch {
            val defaults = prefsRepository.defaultsFlow.first()
            _uiState.update { state ->
                state.copy(
                    settings = state.settings.copy(
                        paperSize = defaults.paperSize,
                        orientation = defaults.orientation,
                        scaleMode = defaults.scaleMode,
                        quality = defaults.quality,
                        dialect = defaults.dialect,
                        photoLayout = defaults.photoLayout
                    ),
                    adjustments = state.adjustments.copy(ditherMode = defaults.ditherMode)
                )
            }
        }
    }

    override fun onCleared() {
        usbPrinterManager.unregister()
        super.onCleared()
    }

    fun refreshUsb() {
        usbPrinterManager.refreshDevices()
    }

    fun requestUsbPermission() {
        usbPrinterManager.requestUsbPermission()
    }

    fun sendSoftReset() {
        viewModelScope.launch {
            val result = usbPrinterManager.sendSoftReset()
            _uiState.update {
                it.copy(
                    snackbarMessage = result.fold(
                        onSuccess = { "Printer interface reset successfully" },
                        onFailure = { e -> e.message ?: "Soft reset failed" }
                    )
                )
            }
        }
    }

    fun loadPhotoUris(uris: List<Uri>, append: Boolean = false) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val decodedItems = uris.mapIndexedNotNull { idx, uri ->
                val name = DocumentEngine.queryDisplayName(appContext, uri, "Photo ${idx + 1}")
                val dims = MonochromeProcessor.readImageDimensions(appContext, uri)
                if (dims != null) {
                    PhotoItem(uri = uri, displayName = name, widthPx = dims.first, heightPx = dims.second)
                } else {
                    null
                }
            }
            if (decodedItems.isEmpty()) {
                postMessage("Unable to decode image file")
                return@launch
            }
            if (decodedItems.size < uris.size) {
                postMessage("Loaded ${decodedItems.size} of ${uris.size} photos")
            }

            val existing = (_uiState.value.document as? DocumentSource.ImageDocument)
            val mergedPhotos = if (append && existing != null) {
                (existing.photos + decodedItems).take(24)
            } else {
                decodedItems
            }

            val currentState = _uiState.value
            val adjustments = if (append && existing != null) {
                currentState.adjustments
            } else {
                ImageAdjustmentState(ditherMode = DitherMode.HALFTONE_PHOTO)
            }
            val plan = computePlanForPhotos(
                photos = mergedPhotos,
                adjustments = adjustments,
                settings = currentState.settings
            )
            val totalPages = PhotoLayoutGeometry.computeTotalPhotoPages(
                photoCount = mergedPhotos.size,
                slotsPerPage = plan.slotsPerPage,
                repeatSinglePhoto = currentState.settings.photoLayout.repeatSinglePhoto
            )
            val doc = DocumentSource.ImageDocument(
                photos = mergedPhotos,
                slotsPerPage = plan.slotsPerPage,
                repeatSinglePhoto = currentState.settings.photoLayout.repeatSinglePhoto,
                totalPages = totalPages
            )

            _uiState.update {
                it.copy(
                    document = doc,
                    currentPageIndex = if (append) it.currentPageIndex.coerceIn(0, (totalPages - 1).coerceAtLeast(0)) else 0,
                    isTestPageSelected = false,
                    adjustments = adjustments,
                    settings = it.settings.copy(
                        pageRangeMode = PageRangeMode.ALL,
                        customPageRange = if (totalPages > 1) "1-$totalPages" else "1"
                    ),
                    progress = PrintProgressState()
                )
            }
            regeneratePreview()
        }
    }

    fun loadUri(uri: Uri, mimeTypeHint: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolvedMime = mimeTypeHint ?: appContext.contentResolver.getType(uri) ?: ""
            val name = DocumentEngine.queryDisplayName(appContext, uri, "Document")
            val lowerName = name.lowercase()

            when {
                resolvedMime.startsWith("image/") ||
                    lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") ||
                    lowerName.endsWith(".png") || lowerName.endsWith(".webp") ||
                    lowerName.endsWith(".heic") || lowerName.endsWith(".heif") ||
                    lowerName.endsWith(".avif") || lowerName.endsWith(".bmp") -> {
                    loadPhotoUris(listOf(uri), append = false)
                }

                resolvedMime == "application/pdf" || lowerName.endsWith(".pdf") -> {
                    val pageCount = runCatching { DocumentEngine.getPdfPageCount(appContext, uri) }
                        .getOrDefault(0)
                    if (pageCount <= 0) {
                        postMessage("Could not open PDF document")
                        return@launch
                    }
                    val doc = DocumentSource.PdfDocumentSource(uri, name, pageCount)
                    _uiState.update {
                        it.copy(
                            document = doc,
                            currentPageIndex = 0,
                            isTestPageSelected = false,
                            adjustments = ImageAdjustmentState(ditherMode = DitherMode.THRESHOLD_DOCUMENT),
                            settings = it.settings.copy(
                                pageRangeMode = PageRangeMode.ALL,
                                customPageRange = if (pageCount > 1) "1-$pageCount" else "1"
                            ),
                            progress = PrintProgressState()
                        )
                    }
                    regeneratePreview()
                }

                else -> {
                    postMessage("Unsupported file type. Select a photo or PDF.")
                }
            }
        }
    }

    fun previewDiagnosticTestPage() {
        _uiState.update {
            it.copy(
                document = null,
                isTestPageSelected = true,
                currentPageIndex = 0,
                adjustments = ImageAdjustmentState(ditherMode = DitherMode.THRESHOLD_DOCUMENT),
                progress = PrintProgressState()
            )
        }
        regeneratePreview()
    }

    fun clearDocument() {
        _uiState.update {
            it.copy(
                document = null,
                previewBitmap = null,
                isTestPageSelected = false,
                currentPageIndex = 0,
                progress = PrintProgressState()
            )
        }
    }

    fun setCurrentPage(index: Int) {
        val total = _uiState.value.document?.totalPages ?: 1
        val clamped = index.coerceIn(0, (total - 1).coerceAtLeast(0))
        if (clamped != _uiState.value.currentPageIndex) {
            _uiState.update { it.copy(currentPageIndex = clamped) }
            regeneratePreview()
        }
    }

    fun updateAdjustments(transform: (ImageAdjustmentState) -> ImageAdjustmentState) {
        _uiState.update { state ->
            val nextAdj = transform(state.adjustments)
            val updatedDoc = recomputeImageDocument(state.document, nextAdj, state.settings)
            val clampedPage = updatedDoc?.let {
                state.currentPageIndex.coerceIn(0, (it.totalPages - 1).coerceAtLeast(0))
            } ?: state.currentPageIndex
            state.copy(
                adjustments = nextAdj,
                document = updatedDoc ?: state.document,
                currentPageIndex = clampedPage
            )
        }
        persistDefaults()
        regeneratePreview()
    }

    fun resetAdjustments() {
        val defaultDither = if (_uiState.value.document is DocumentSource.ImageDocument) {
            DitherMode.HALFTONE_PHOTO
        } else {
            DitherMode.THRESHOLD_DOCUMENT
        }
        updateAdjustments { ImageAdjustmentState(ditherMode = defaultDither) }
    }

    fun updateSettings(transform: (PrintSettings) -> PrintSettings) {
        val prev = _uiState.value.settings
        val next = transform(prev)
        _uiState.update { state ->
            val updatedDoc = recomputeImageDocument(state.document, state.adjustments, next)
            val clampedPage = updatedDoc?.let {
                state.currentPageIndex.coerceIn(0, (it.totalPages - 1).coerceAtLeast(0))
            } ?: state.currentPageIndex
            state.copy(
                settings = next,
                document = updatedDoc ?: state.document,
                currentPageIndex = clampedPage
            )
        }
        persistDefaults()

        if (prev.paperSize != next.paperSize ||
            prev.orientation != next.orientation ||
            prev.scaleMode != next.scaleMode ||
            prev.photoLayout != next.photoLayout
        ) {
            regeneratePreview()
        }
    }

    fun updatePhotoLayout(transform: (PhotoLayoutOptions) -> PhotoLayoutOptions) {
        updateSettings { it.copy(photoLayout = transform(it.photoLayout)) }
    }

    fun resetPhotoLayout() {
        updateSettings {
            it.copy(
                scaleMode = ScaleMode.FIT,
                orientation = PrintOrientation.PORTRAIT,
                photoLayout = PhotoLayoutOptions()
            )
        }
    }

    private fun recomputeImageDocument(
        doc: DocumentSource?,
        adjustments: ImageAdjustmentState,
        settings: PrintSettings
    ): DocumentSource.ImageDocument? {
        if (doc !is DocumentSource.ImageDocument) return null
        val plan = computePlanForPhotos(doc.photos, adjustments, settings)
        val totalPages = PhotoLayoutGeometry.computeTotalPhotoPages(
            photoCount = doc.photos.size,
            slotsPerPage = plan.slotsPerPage,
            repeatSinglePhoto = settings.photoLayout.repeatSinglePhoto
        )
        return doc.copy(
            slotsPerPage = plan.slotsPerPage,
            repeatSinglePhoto = settings.photoLayout.repeatSinglePhoto,
            totalPages = totalPages
        )
    }

    private fun computePlanForPhotos(
        photos: List<PhotoItem>,
        adjustments: ImageAdjustmentState,
        settings: PrintSettings
    ): PhotoSheetPlan {
        val first = photos.firstOrNull()
        val rawW = first?.widthPx ?: 1
        val rawH = first?.heightPx ?: 1
        val cropW = (adjustments.cropRight - adjustments.cropLeft).coerceAtLeast(0.05f) * rawW
        val cropH = (adjustments.cropBottom - adjustments.cropTop).coerceAtLeast(0.05f) * rawH
        val isRotated90 = (adjustments.rotationDegrees % 180) != 0
        val effIsLandscape = if (isRotated90) cropH > cropW else cropW > cropH
        return PhotoLayoutGeometry.computeSheetPlan(
            paperSize = settings.paperSize,
            orientation = settings.orientation,
            layout = settings.photoLayout,
            primaryPhotoIsLandscape = effIsLandscape
        )
    }

    fun startPrintJob() {
        val state = _uiState.value
        if (state.progress.isActive) return
        if (state.document == null && !state.isTestPageSelected) {
            postMessage("Select a photo or PDF document first")
            return
        }

        usbPrinterManager.refreshDevices()
        val currentUsb = usbStatus.value
        if (currentUsb is UsbPrinterStatus.PermissionRequired) {
            usbPrinterManager.requestUsbPermission()
            postMessage("Approve USB OTG permission prompt to print")
            return
        }
        if (currentUsb !is UsbPrinterStatus.Connected) {
            postMessage("Connect your Samsung SCX-3200 via USB OTG cable first")
            return
        }

        val pageIndices = resolveTargetPages(state)
        if (pageIndices.isEmpty()) {
            postMessage("No matching pages in selected range")
            return
        }

        printJob?.cancel()
        printJob = viewModelScope.launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val settings = state.settings
            val adjustments = state.adjustments
            val xDpi = settings.quality.xDpi
            val yDpi = settings.quality.yDpi
            val jobTitle = if (state.isTestPageSelected) {
                "SCX-3200 Diagnostic Sheet"
            } else {
                state.document?.displayName ?: "Document"
            }
            val docTypeLabel = if (state.isTestPageSelected) {
                DocumentType.TEST_PAGE.displayLabel
            } else {
                state.document?.documentType?.displayLabel ?: "Document"
            }

            try {
                val pjlHeader = SplQpdlEncoder.buildPjlHeader(settings.quality, jobTitle)
                val pjlFooter = SplQpdlEncoder.buildPjlFooter()

                _uiState.update {
                    it.copy(
                        progress = PrintProgressState(
                            phase = PrintPhase.TRANSFERRING,
                            currentPage = 1,
                            totalPages = pageIndices.size
                        )
                    )
                }

                val streamSequence = sequence {
                    yield(pjlHeader)

                    when {
                        state.isTestPageSelected -> {
                            val details = (usbStatus.value as? UsbPrinterStatus.Connected)?.details
                            val (pageW, pageH) = MonochromeProcessor.resolvePageDimensions(
                                paperSize = settings.paperSize,
                                orientation = settings.orientation,
                                xDpi = xDpi,
                                yDpi = yDpi,
                                contentAspectIsLandscape = false
                            )
                            val bandBitmap = Bitmap.createBitmap(
                                pageW,
                                SplQpdlEncoder.BAND_HEIGHT_LINES,
                                Bitmap.Config.ARGB_8888
                            )
                            try {
                                val ditherer = MonochromeProcessor.StreamingBandDitherer(
                                    widthPx = pageW,
                                    brightness = adjustments.brightness,
                                    contrast = adjustments.contrast,
                                    ditherMode = adjustments.ditherMode
                                )
                                yield(SplQpdlEncoder.buildPageStartHeader(pageW, pageH, settings))
                                var y = 0
                                var bandIndex = 0
                                while (y < pageH) {
                                    val bandH = minOf(SplQpdlEncoder.BAND_HEIGHT_LINES, pageH - y)
                                    SplQpdlEncoder.drawDiagnosticTestPageBand(
                                        bandBitmap = bandBitmap,
                                        pageW = pageW,
                                        pageH = pageH,
                                        bandY = y,
                                        xDpi = xDpi,
                                        yDpi = yDpi,
                                        paperSize = settings.paperSize,
                                        deviceDetails = details
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
                                    updateStreamingBandProgress(1, 1, y.toFloat() / pageH)
                                }
                                yield(SplQpdlEncoder.buildPageEndFooter(settings.copies))
                            } finally {
                                bandBitmap.recycle()
                            }
                        }

                        state.document is DocumentSource.ImageDocument -> {
                            val doc = state.document
                            val layout = settings.photoLayout
                            val firstPlan = computePlanForPhotos(doc.photos, adjustments, settings)
                            val (pageW, pageH) = MonochromeProcessor.resolvePageDimensionsForOrientation(
                                paperSize = settings.paperSize,
                                sheetIsLandscape = firstPlan.sheetIsLandscape,
                                xDpi = xDpi,
                                yDpi = yDpi
                            )
                            val bandBitmap = Bitmap.createBitmap(
                                pageW,
                                SplQpdlEncoder.BAND_HEIGHT_LINES,
                                Bitmap.Config.ARGB_8888
                            )
                            val maxPhotoDim = if (firstPlan.slotsPerPage >= 4) 1400 else 2048
                            try {
                                for ((stepIdx, pageIdx) in pageIndices.withIndex()) {
                                    val pagePhotos = loadPreparedPhotosForSheet(
                                        doc = doc,
                                        pageIndex = pageIdx,
                                        slotsPerPage = firstPlan.slotsPerPage,
                                        repeatSingle = layout.repeatSinglePhoto,
                                        adjustments = adjustments,
                                        maxDimension = maxPhotoDim
                                    )
                                    if (pagePhotos.isEmpty()) {
                                        throw IllegalStateException("Failed to decode photo")
                                    }
                                    try {
                                        val ditherer = MonochromeProcessor.StreamingBandDitherer(
                                            widthPx = pageW,
                                            brightness = adjustments.brightness,
                                            contrast = adjustments.contrast,
                                            ditherMode = adjustments.ditherMode
                                        )
                                        yield(SplQpdlEncoder.buildPageStartHeader(pageW, pageH, settings))
                                        var y = 0
                                        var bandIndex = 0
                                        while (y < pageH) {
                                            val bandH = minOf(SplQpdlEncoder.BAND_HEIGHT_LINES, pageH - y)
                                            MonochromeProcessor.drawPhotoSheetBand(
                                                photosForSheet = pagePhotos,
                                                sheetPlan = firstPlan,
                                                bandBitmap = bandBitmap,
                                                pageW = pageW,
                                                pageH = pageH,
                                                bandY = y,
                                                scaleMode = settings.scaleMode,
                                                layout = layout
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
                                            updateStreamingBandProgress(
                                                currentPage = stepIdx + 1,
                                                totalPages = pageIndices.size,
                                                bandFraction = y.toFloat() / pageH
                                            )
                                        }
                                        yield(SplQpdlEncoder.buildPageEndFooter(settings.copies))
                                    } finally {
                                        pagePhotos.distinct().forEach { it.recycle() }
                                    }
                                }
                            } finally {
                                bandBitmap.recycle()
                            }
                        }

                        state.document is DocumentSource.PdfDocumentSource -> {
                            val pfd = appContext.contentResolver.openFileDescriptor(state.document.uri, "r")
                                ?: throw IllegalStateException("Unable to open PDF file")
                            pfd.use { descriptor ->
                                android.graphics.pdf.PdfRenderer(descriptor).use { renderer ->
                                    for ((stepIdx, pageIdx) in pageIndices.withIndex()) {
                                        renderer.openPage(pageIdx).use { page ->
                                            val (pageW, pageH) = MonochromeProcessor.resolvePageDimensions(
                                                paperSize = settings.paperSize,
                                                orientation = settings.orientation,
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
                                                    brightness = adjustments.brightness,
                                                    contrast = adjustments.contrast,
                                                    ditherMode = adjustments.ditherMode
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
                                                    updateStreamingBandProgress(
                                                        currentPage = stepIdx + 1,
                                                        totalPages = pageIndices.size,
                                                        bandFraction = y.toFloat() / pageH
                                                    )
                                                }
                                                yield(SplQpdlEncoder.buildPageEndFooter(settings.copies))
                                            } finally {
                                                bandBitmap.recycle()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    yield(pjlFooter)
                }

                val transferResult = usbPrinterManager.streamPrintJob(
                    chunks = streamSequence,
                    estimatedTotalBytes = 0L
                ) { sent, speedKb ->
                    _uiState.update {
                        it.copy(
                            progress = it.progress.copy(
                                phase = PrintPhase.TRANSFERRING,
                                bytesTransferred = sent,
                                speedKbPerSec = speedKb
                            )
                        )
                    }
                }

                val durationMs = System.currentTimeMillis() - startTime
                transferResult.fold(
                    onSuccess = { bytesSent ->
                        historyRepository.recordJob(
                            PrintJobRecord(
                                fileName = jobTitle,
                                documentType = docTypeLabel,
                                pageCount = pageIndices.size,
                                copies = settings.copies,
                                paperSize = settings.paperSize.label,
                                qualityLabel = settings.quality.label,
                                bytesTransferred = bytesSent,
                                durationMs = durationMs,
                                status = PrintJobStatus.COMPLETED.name
                            )
                        )
                        _uiState.update {
                            it.copy(
                                progress = PrintProgressState(
                                    phase = PrintPhase.COMPLETED,
                                    currentPage = pageIndices.size,
                                    totalPages = pageIndices.size,
                                    bytesTransferred = bytesSent,
                                    totalBytes = bytesSent
                                ),
                                snackbarMessage = "Sent ${pageIndices.size} page(s) to SCX-3200"
                            )
                        }
                    },
                    onFailure = { err ->
                        val msg = err.message ?: "USB transfer error"
                        historyRepository.recordJob(
                            PrintJobRecord(
                                fileName = jobTitle,
                                documentType = docTypeLabel,
                                pageCount = pageIndices.size,
                                copies = settings.copies,
                                paperSize = settings.paperSize.label,
                                qualityLabel = settings.quality.label,
                                bytesTransferred = _uiState.value.progress.bytesTransferred,
                                durationMs = durationMs,
                                status = PrintJobStatus.FAILED.name,
                                errorMessage = msg
                            )
                        )
                        _uiState.update {
                            it.copy(
                                progress = PrintProgressState(
                                    phase = PrintPhase.FAILED,
                                    errorMessage = msg
                                ),
                                snackbarMessage = msg
                            )
                        }
                    }
                )
            } catch (_: CancellationException) {
                val durationMs = System.currentTimeMillis() - startTime
                historyRepository.recordJob(
                    PrintJobRecord(
                        fileName = jobTitle,
                        documentType = docTypeLabel,
                        pageCount = pageIndices.size,
                        copies = settings.copies,
                        paperSize = settings.paperSize.label,
                        qualityLabel = settings.quality.label,
                        bytesTransferred = _uiState.value.progress.bytesTransferred,
                        durationMs = durationMs,
                        status = PrintJobStatus.CANCELLED.name,
                        errorMessage = "Cancelled by user"
                    )
                )
                _uiState.update {
                    it.copy(
                        progress = PrintProgressState(phase = PrintPhase.CANCELLED),
                        snackbarMessage = "Print job cancelled"
                    )
                }
            } catch (t: Throwable) {
                val msg = t.message ?: "Print failed"
                _uiState.update {
                    it.copy(
                        progress = PrintProgressState(phase = PrintPhase.FAILED, errorMessage = msg),
                        snackbarMessage = msg
                    )
                }
            }
        }
    }

    private fun loadPreparedPhotosForSheet(
        doc: DocumentSource.ImageDocument,
        pageIndex: Int,
        slotsPerPage: Int,
        repeatSingle: Boolean,
        adjustments: ImageAdjustmentState,
        maxDimension: Int
    ): List<Bitmap> {
        if (doc.photos.isEmpty()) return emptyList()
        if (doc.photos.size == 1) {
            val raw = MonochromeProcessor.loadScaledBitmap(appContext, doc.photos[0].uri, maxDimension)
                ?: return emptyList()
            val prepared = MonochromeProcessor.prepareCroppedRotatedBitmap(raw, adjustments)
            if (prepared !== raw) raw.recycle()
            return if (slotsPerPage > 1 && repeatSingle) {
                List(slotsPerPage) { prepared }
            } else {
                listOf(prepared)
            }
        }

        val start = pageIndex * slotsPerPage
        val end = minOf(doc.photos.size, start + slotsPerPage)
        val bitmaps = mutableListOf<Bitmap>()
        for (idx in start until end) {
            val raw = MonochromeProcessor.loadScaledBitmap(appContext, doc.photos[idx].uri, maxDimension)
                ?: continue
            val adj = if (idx == 0) {
                adjustments
            } else {
                ImageAdjustmentState(rotationDegrees = adjustments.rotationDegrees)
            }
            val prepared = MonochromeProcessor.prepareCroppedRotatedBitmap(raw, adj)
            if (prepared !== raw) raw.recycle()
            bitmaps.add(prepared)
        }
        return bitmaps
    }

    private fun updateStreamingBandProgress(
        currentPage: Int,
        totalPages: Int,
        bandFraction: Float
    ) {
        _uiState.update {
            it.copy(
                progress = it.progress.copy(
                    currentPage = currentPage,
                    totalPages = totalPages,
                    bandFraction = bandFraction
                )
            )
        }
    }

    fun cancelActivePrintJob() {
        printJob?.cancel()
    }

    fun dismissSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun deleteHistoryRecord(id: Long) {
        viewModelScope.launch { historyRepository.deleteJob(id) }
    }

    fun clearHistory() {
        viewModelScope.launch {
            historyRepository.clearHistory()
            postMessage("Print history cleared")
        }
    }

    private fun resolveTargetPages(state: PrintStudioUiState): List<Int> {
        if (state.isTestPageSelected) return listOf(0)
        val total = state.document?.totalPages ?: return emptyList()
        if (total <= 1) return listOf(0)
        return PageRangeParser.resolvePages(
            mode = state.settings.pageRangeMode,
            totalPages = total,
            currentPageIndex = state.currentPageIndex,
            customExpression = state.settings.customPageRange
        )
    }

    private fun regeneratePreview() {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val state = _uiState.value
            if (state.document == null && !state.isTestPageSelected) return@launch

            _uiState.update { it.copy(isRenderingPreview = true) }

            val rendered: Bitmap? = withContext(Dispatchers.IO) {
                val previewW = 900
                val rawSheet: Bitmap? = when {
                    state.isTestPageSelected -> {
                        val details = (usbStatus.value as? UsbPrinterStatus.Connected)?.details
                        SplQpdlEncoder.createDiagnosticTestPageBitmap(
                            paperSize = state.settings.paperSize,
                            deviceDetails = details,
                            previewScaleWidth = previewW
                        )
                    }
                    state.document is DocumentSource.ImageDocument -> {
                        val doc = state.document
                        val plan = computePlanForPhotos(doc.photos, state.adjustments, state.settings)
                        val maxDim = if (plan.slotsPerPage >= 4) 800 else 1400
                        val sheetPhotos = loadPreparedPhotosForSheet(
                            doc = doc,
                            pageIndex = state.currentPageIndex,
                            slotsPerPage = plan.slotsPerPage,
                            repeatSingle = state.settings.photoLayout.repeatSinglePhoto,
                            adjustments = state.adjustments,
                            maxDimension = maxDim
                        )
                        if (sheetPhotos.isEmpty()) return@withContext null
                        try {
                            MonochromeProcessor.composePhotoSheetPreview(
                                preparedPhotosForSheet = sheetPhotos,
                                sheetPlan = plan,
                                scaleMode = state.settings.scaleMode,
                                layout = state.settings.photoLayout,
                                targetWidthPx = previewW
                            )
                        } finally {
                            sheetPhotos.distinct().forEach { it.recycle() }
                        }
                    }
                    state.document is DocumentSource.PdfDocumentSource -> {
                        DocumentEngine.renderPdfPage(
                            context = appContext,
                            uri = state.document.uri,
                            pageIndex = state.currentPageIndex,
                            paperSize = state.settings.paperSize,
                            orientation = state.settings.orientation,
                            targetWidthPx = previewW
                        )
                    }
                    else -> null
                }

                if (rawSheet == null) return@withContext null
                val (monoBitmap, _) = try {
                    MonochromeProcessor.rasterizeMonochrome(
                        sheet = rawSheet,
                        brightness = state.adjustments.brightness,
                        contrast = state.adjustments.contrast,
                        ditherMode = state.adjustments.ditherMode,
                        producePreviewBitmap = true
                    )
                } finally {
                    rawSheet.recycle()
                }
                monoBitmap
            }

            _uiState.update {
                it.copy(
                    previewBitmap = rendered,
                    isRenderingPreview = false
                )
            }
        }
    }

    private fun persistDefaults() {
        val state = _uiState.value
        viewModelScope.launch {
            prefsRepository.saveDefaults(
                paperSize = state.settings.paperSize,
                orientation = state.settings.orientation,
                scaleMode = state.settings.scaleMode,
                quality = state.settings.quality,
                ditherMode = state.adjustments.ditherMode,
                dialect = state.settings.dialect,
                photoLayout = state.settings.photoLayout
            )
        }
    }

    private fun postMessage(message: String) {
        _uiState.update { it.copy(snackbarMessage = message) }
    }
}
