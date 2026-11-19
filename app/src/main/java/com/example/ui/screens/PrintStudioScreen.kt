package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.model.DocumentSource
import com.example.model.ImageAdjustmentState
import com.example.model.PhotoLayoutOptions
import com.example.model.PrintSettings
import com.example.model.UsbPrinterStatus
import com.example.ui.components.CropToolModal
import com.example.ui.components.PaperCanvasPreview
import com.example.ui.components.PhotoLayoutSheet
import com.example.ui.components.PrePrintToolsPanel
import com.example.ui.components.PrintControlsDock
import com.example.ui.components.PrintPrimaryActionButton
import com.example.ui.theme.HairlineStone
import com.example.ui.theme.SheetWhite
import com.example.viewmodel.PrintStudioUiState

@Composable
fun PrintStudioScreen(
    uiState: PrintStudioUiState,
    usbStatus: UsbPrinterStatus,
    onPickPhoto: () -> Unit,
    onAddMorePhotos: () -> Unit,
    onPickPdf: () -> Unit,
    onClearDocument: () -> Unit,
    onPageChange: (Int) -> Unit,
    onUpdateAdjustments: ((ImageAdjustmentState) -> ImageAdjustmentState) -> Unit,
    onResetAdjustments: () -> Unit,
    onUpdateSettings: ((PrintSettings) -> PrintSettings) -> Unit,
    onUpdatePhotoLayout: ((PhotoLayoutOptions) -> PhotoLayoutOptions) -> Unit,
    onResetPhotoLayout: () -> Unit,
    onPrintClick: () -> Unit,
    onCancelClick: () -> Unit,
    onRequestUsbPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showCropModal by remember { mutableStateOf(false) }
    var showPhotoLayoutSheet by remember { mutableStateOf(false) }
    val imageDoc = uiState.document as? DocumentSource.ImageDocument

    if (showCropModal && imageDoc != null) {
        CropToolModal(
            imageUri = imageDoc.uri,
            initialAdjustments = uiState.adjustments,
            onApply = { l, t, r, b, rot ->
                onUpdateAdjustments {
                    it.copy(
                        cropLeft = l,
                        cropTop = t,
                        cropRight = r,
                        cropBottom = b,
                        rotationDegrees = rot
                    )
                }
                showCropModal = false
            },
            onDismiss = { showCropModal = false }
        )
    }

    if (showPhotoLayoutSheet && imageDoc != null) {
        val isRotated90 = (uiState.adjustments.rotationDegrees % 180) != 0
        val primaryIsLandscape = if (isRotated90) {
            imageDoc.primaryHeightPx > imageDoc.primaryWidthPx
        } else {
            imageDoc.primaryWidthPx > imageDoc.primaryHeightPx
        }
        PhotoLayoutSheet(
            photoCount = imageDoc.photos.size,
            primaryPhotoIsLandscape = primaryIsLandscape,
            settings = uiState.settings,
            onUpdateSettings = onUpdateSettings,
            onUpdatePhotoLayout = onUpdatePhotoLayout,
            onResetLayout = onResetPhotoLayout,
            onAddPhotos = onAddMorePhotos,
            onDismiss = { showPhotoLayoutSheet = false }
        )
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isExpanded = maxWidth >= 700.dp

        if (isExpanded) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1.05f)
                        .verticalScroll(rememberScrollState())
                ) {
                    PaperCanvasPreview(
                        document = uiState.document,
                        isTestPageSelected = uiState.isTestPageSelected,
                        previewBitmap = uiState.previewBitmap,
                        isRenderingPreview = uiState.isRenderingPreview,
                        currentPageIndex = uiState.currentPageIndex,
                        paperSize = uiState.settings.paperSize,
                        orientation = uiState.settings.orientation,
                        onPageChange = onPageChange,
                        onPickPhoto = onPickPhoto,
                        onAddMorePhotos = onAddMorePhotos,
                        onPickPdf = onPickPdf,
                        onClearDocument = onClearDocument
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(0.95f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AnimatedVisibility(visible = uiState.document != null || uiState.isTestPageSelected) {
                        PrePrintToolsPanel(
                            document = uiState.document,
                            adjustments = uiState.adjustments,
                            onUpdateAdjustments = onUpdateAdjustments,
                            onOpenCropModal = { showCropModal = true },
                            onReset = onResetAdjustments
                        )
                    }

                    PrintControlsDock(
                        document = uiState.document,
                        isTestPageSelected = uiState.isTestPageSelected,
                        currentPageIndex = uiState.currentPageIndex,
                        settings = uiState.settings,
                        progress = uiState.progress,
                        usbStatus = usbStatus,
                        onUpdateSettings = onUpdateSettings,
                        onUpdatePhotoLayout = onUpdatePhotoLayout,
                        onOpenPhotoLayoutSheet = { showPhotoLayoutSheet = true },
                        onPrintClick = onPrintClick,
                        onCancelClick = onCancelClick,
                        onRequestUsbPermission = onRequestUsbPermission,
                        showInlinePrintButton = true
                    )
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PaperCanvasPreview(
                        document = uiState.document,
                        isTestPageSelected = uiState.isTestPageSelected,
                        previewBitmap = uiState.previewBitmap,
                        isRenderingPreview = uiState.isRenderingPreview,
                        currentPageIndex = uiState.currentPageIndex,
                        paperSize = uiState.settings.paperSize,
                        orientation = uiState.settings.orientation,
                        onPageChange = onPageChange,
                        onPickPhoto = onPickPhoto,
                        onAddMorePhotos = onAddMorePhotos,
                        onPickPdf = onPickPdf,
                        onClearDocument = onClearDocument
                    )

                    AnimatedVisibility(visible = uiState.document != null || uiState.isTestPageSelected) {
                        PrePrintToolsPanel(
                            document = uiState.document,
                            adjustments = uiState.adjustments,
                            onUpdateAdjustments = onUpdateAdjustments,
                            onOpenCropModal = { showCropModal = true },
                            onReset = onResetAdjustments
                        )
                    }

                    PrintControlsDock(
                        document = uiState.document,
                        isTestPageSelected = uiState.isTestPageSelected,
                        currentPageIndex = uiState.currentPageIndex,
                        settings = uiState.settings,
                        progress = uiState.progress,
                        usbStatus = usbStatus,
                        onUpdateSettings = onUpdateSettings,
                        onUpdatePhotoLayout = onUpdatePhotoLayout,
                        onOpenPhotoLayoutSheet = { showPhotoLayoutSheet = true },
                        onPrintClick = onPrintClick,
                        onCancelClick = onCancelClick,
                        onRequestUsbPermission = onRequestUsbPermission,
                        showInlinePrintButton = false
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Sticky bottom print bar on handheld screens
                Surface(
                    color = SheetWhite,
                    border = BorderStroke(1.dp, HairlineStone),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    PrintPrimaryActionButton(
                        hasDocument = uiState.document != null || uiState.isTestPageSelected,
                        progress = uiState.progress,
                        onPrintClick = onPrintClick,
                        onCancelClick = onCancelClick,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}
