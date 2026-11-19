package com.example

import android.content.Intent
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.HardwareStatusHeader
import com.example.ui.screens.PrintStudioScreen
import com.example.ui.screens.PrinterHistoryScreen
import com.example.ui.theme.CobaltAccent
import com.example.ui.theme.CobaltSoft
import com.example.ui.theme.GraphiteMuted
import com.example.ui.theme.InkBlack
import com.example.ui.theme.PaperWhite
import com.example.ui.theme.SCXPrintTheme
import com.example.ui.theme.SheetWhite
import com.example.viewmodel.PrintStudioViewModel

private enum class StudioTab {
    STUDIO,
    PRINTER_HISTORY
}

class MainActivity : ComponentActivity() {

    private val viewModel: PrintStudioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        setContent {
            SCXPrintTheme {
                ScxPrintRoot(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                viewModel.refreshUsb()
            }
            Intent.ACTION_SEND -> {
                val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) {
                    viewModel.loadUri(uri, intent.type)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (!uris.isNullOrEmpty()) {
                    viewModel.loadPhotoUris(uris, append = false)
                }
            }
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    viewModel.loadUri(uri, intent.type)
                }
            }
        }
    }
}

@Composable
private fun ScxPrintRoot(viewModel: PrintStudioViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val usbStatus by viewModel.usbStatus.collectAsStateWithLifecycle()
    val history by viewModel.printHistory.collectAsStateWithLifecycle()

    var currentTab by rememberSaveable { mutableStateOf(StudioTab.STUDIO) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Zero-permission Android Photo Picker (1 or multiple photos)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.loadPhotoUris(uris, append = false)
        }
    }

    // Zero-permission Android Photo Picker to append more photos
    val addPhotosLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.loadPhotoUris(uris, append = true)
        }
    }

    // Zero-permission Storage Access Framework PDF Picker
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.loadUri(uri, "application/pdf")
        }
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissSnackbar()
        }
    }

    BackHandler(enabled = currentTab != StudioTab.STUDIO) {
        currentTab = StudioTab.STUDIO
    }

    val onPickPhoto = {
        photoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }
    val onAddMorePhotos = {
        addPhotosLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }
    val onPickPdf = {
        pdfPickerLauncher.launch(arrayOf("application/pdf"))
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp

        Scaffold(
            containerColor = PaperWhite,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!useNavRail) {
                    NavigationBar(
                        containerColor = SheetWhite,
                        tonalElevation = 0.dp
                    ) {
                        NavigationBarItem(
                            selected = currentTab == StudioTab.STUDIO,
                            onClick = { currentTab = StudioTab.STUDIO },
                            icon = {
                                Icon(
                                    imageVector = if (currentTab == StudioTab.STUDIO) {
                                        Icons.Rounded.Print
                                    } else {
                                        Icons.Outlined.Print
                                    },
                                    contentDescription = null
                                )
                            },
                            label = { Text(stringResource(R.string.tab_studio)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = CobaltAccent,
                                selectedTextColor = InkBlack,
                                indicatorColor = CobaltSoft,
                                unselectedIconColor = GraphiteMuted,
                                unselectedTextColor = GraphiteMuted
                            ),
                            modifier = Modifier.testTag("nav_tab_studio")
                        )
                        NavigationBarItem(
                            selected = currentTab == StudioTab.PRINTER_HISTORY,
                            onClick = { currentTab = StudioTab.PRINTER_HISTORY },
                            icon = {
                                Icon(
                                    imageVector = if (currentTab == StudioTab.PRINTER_HISTORY) {
                                        Icons.Rounded.Usb
                                    } else {
                                        Icons.Outlined.Usb
                                    },
                                    contentDescription = null
                                )
                            },
                            label = { Text(stringResource(R.string.tab_printer)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = CobaltAccent,
                                selectedTextColor = InkBlack,
                                indicatorColor = CobaltSoft,
                                unselectedIconColor = GraphiteMuted,
                                unselectedTextColor = GraphiteMuted
                            ),
                            modifier = Modifier.testTag("nav_tab_printer")
                        )
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavRail) {
                    NavigationRail(
                        containerColor = SheetWhite,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        NavigationRailItem(
                            selected = currentTab == StudioTab.STUDIO,
                            onClick = { currentTab = StudioTab.STUDIO },
                            icon = {
                                Icon(
                                    imageVector = if (currentTab == StudioTab.STUDIO) {
                                        Icons.Rounded.Print
                                    } else {
                                        Icons.Outlined.Print
                                    },
                                    contentDescription = null
                                )
                            },
                            label = { Text("Studio") },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = CobaltAccent,
                                selectedTextColor = InkBlack,
                                indicatorColor = CobaltSoft,
                                unselectedIconColor = GraphiteMuted
                            ),
                            modifier = Modifier.testTag("rail_tab_studio")
                        )
                        NavigationRailItem(
                            selected = currentTab == StudioTab.PRINTER_HISTORY,
                            onClick = { currentTab = StudioTab.PRINTER_HISTORY },
                            icon = {
                                Icon(
                                    imageVector = if (currentTab == StudioTab.PRINTER_HISTORY) {
                                        Icons.Rounded.Usb
                                    } else {
                                        Icons.Outlined.Usb
                                    },
                                    contentDescription = null
                                )
                            },
                            label = { Text("USB") },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = CobaltAccent,
                                selectedTextColor = InkBlack,
                                indicatorColor = CobaltSoft,
                                unselectedIconColor = GraphiteMuted
                            ),
                            modifier = Modifier.testTag("rail_tab_printer")
                        )
                    }
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    HardwareStatusHeader(
                        usbStatus = usbStatus,
                        onRefreshUsb = viewModel::refreshUsb,
                        onRequestPermission = viewModel::requestUsbPermission
                    )

                    Box(modifier = Modifier.weight(1f)) {
                        when (currentTab) {
                            StudioTab.STUDIO -> {
                                PrintStudioScreen(
                                    uiState = uiState,
                                    usbStatus = usbStatus,
                                    onPickPhoto = onPickPhoto,
                                    onAddMorePhotos = onAddMorePhotos,
                                    onPickPdf = onPickPdf,
                                    onClearDocument = viewModel::clearDocument,
                                    onPageChange = viewModel::setCurrentPage,
                                    onUpdateAdjustments = viewModel::updateAdjustments,
                                    onResetAdjustments = viewModel::resetAdjustments,
                                    onUpdateSettings = viewModel::updateSettings,
                                    onUpdatePhotoLayout = viewModel::updatePhotoLayout,
                                    onResetPhotoLayout = viewModel::resetPhotoLayout,
                                    onPrintClick = viewModel::startPrintJob,
                                    onCancelClick = viewModel::cancelActivePrintJob,
                                    onRequestUsbPermission = viewModel::requestUsbPermission
                                )
                            }
                            StudioTab.PRINTER_HISTORY -> {
                                PrinterHistoryScreen(
                                    usbStatus = usbStatus,
                                    history = history,
                                    onRefreshUsb = viewModel::refreshUsb,
                                    onRequestPermission = viewModel::requestUsbPermission,
                                    onSoftReset = viewModel::sendSoftReset,
                                    onPreviewTestPage = {
                                        viewModel.previewDiagnosticTestPage()
                                        currentTab = StudioTab.STUDIO
                                    },
                                    onDeleteJob = viewModel::deleteHistoryRecord,
                                    onClearHistory = viewModel::clearHistory
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
