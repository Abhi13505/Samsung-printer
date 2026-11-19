package com.example.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.PrintJobRecord
import com.example.data.PrintJobStatus
import com.example.model.UsbPrinterStatus
import com.example.ui.theme.AmberSoft
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.CobaltAccent
import com.example.ui.theme.CobaltSoft
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.CrimsonSoft
import com.example.ui.theme.EmeraldReady
import com.example.ui.theme.EmeraldSoft
import com.example.ui.theme.GraphiteMuted
import com.example.ui.theme.HairlineStone
import com.example.ui.theme.InkBlack
import com.example.ui.theme.SheetWhite
import com.example.ui.theme.WarmStone
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PrinterHistoryScreen(
    usbStatus: UsbPrinterStatus,
    history: List<PrintJobRecord>,
    onRefreshUsb: () -> Unit,
    onRequestPermission: () -> Unit,
    onSoftReset: () -> Unit,
    onPreviewTestPage: () -> Unit,
    onDeleteJob: (Long) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .widthIn(max = 760.dp),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            UsbDiagnosticsCard(
                usbStatus = usbStatus,
                onRefreshUsb = onRefreshUsb,
                onRequestPermission = onRequestPermission,
                onSoftReset = onSoftReset,
                onPreviewTestPage = onPreviewTestPage,
                onOpenSystemPrintSettings = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_PRINT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.History,
                        contentDescription = null,
                        tint = InkBlack,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Print Job History (${history.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = InkBlack
                    )
                }

                if (history.isNotEmpty()) {
                    TextButton(
                        onClick = onClearHistory,
                        modifier = Modifier.testTag("clear_history_button")
                    ) {
                        Text(
                            text = stringResource(R.string.action_clear_history),
                            color = CrimsonError,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

        if (history.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = WarmStone,
                    border = BorderStroke(1.dp, HairlineStone),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Description,
                            contentDescription = null,
                            tint = GraphiteMuted,
                            modifier = Modifier.size(30.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.empty_history_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = InkBlack
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.empty_history_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = GraphiteMuted,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(history, key = { it.id }) { record ->
                PrintHistoryCard(
                    record = record,
                    onDelete = { onDeleteJob(record.id) }
                )
            }
        }
    }
}

@Composable
private fun UsbDiagnosticsCard(
    usbStatus: UsbPrinterStatus,
    onRefreshUsb: () -> Unit,
    onRequestPermission: () -> Unit,
    onSoftReset: () -> Unit,
    onPreviewTestPage: () -> Unit,
    onOpenSystemPrintSettings: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = SheetWhite,
        border = BorderStroke(1.dp, HairlineStone),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Usb,
                        contentDescription = null,
                        tint = CobaltAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "USB OTG Hardware Telemetry",
                        style = MaterialTheme.typography.titleMedium,
                        color = InkBlack
                    )
                }

                IconButton(
                    onClick = onRefreshUsb,
                    modifier = Modifier.testTag("diag_scan_usb")
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Scan USB OTG")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            when (usbStatus) {
                is UsbPrinterStatus.Connected -> {
                    val d = usbStatus.details
                    TelemetryRow("Model", "${d.manufacturer} ${d.productName}")
                    TelemetryRow("USB VID:PID", d.vidPidHex + if (d.isScx3200) " (SCX-3200)" else "")
                    TelemetryRow("Port Path", d.deviceName)
                    if (!d.ieee1284Id.isNullOrBlank()) {
                        TelemetryRow("IEEE 1284", d.ieee1284Id)
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FlagBadge(
                            label = if (d.portFlags.selected) "Selected" else "Offline",
                            isOk = d.portFlags.selected,
                            modifier = Modifier.weight(1f)
                        )
                        FlagBadge(
                            label = if (d.portFlags.paperEmpty) "Paper Empty" else "Paper OK",
                            isOk = !d.portFlags.paperEmpty,
                            modifier = Modifier.weight(1f)
                        )
                        FlagBadge(
                            label = if (d.portFlags.noError) "No Fault" else "Error",
                            isOk = d.portFlags.noError,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                is UsbPrinterStatus.PermissionRequired -> {
                    TelemetryRow("Detected", "${usbStatus.details.productName} (${usbStatus.details.vidPidHex})")
                    TelemetryRow("Status", "Awaiting Android USB OTG permission")
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onRequestPermission,
                        colors = ButtonDefaults.buttonColors(containerColor = CobaltAccent),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("diag_grant_usb")
                    ) {
                        Text(stringResource(R.string.action_grant_usb))
                    }
                }
                is UsbPrinterStatus.Error -> {
                    TelemetryRow("Status", usbStatus.message)
                }
                UsbPrinterStatus.UsbNotSupported -> {
                    TelemetryRow("Status", stringResource(R.string.usb_not_supported))
                }
                UsbPrinterStatus.Disconnected -> {
                    TelemetryRow("Target", "Samsung SCX-3200 (04E8:3441) or USB Class 0x07")
                    TelemetryRow("Status", "Connect printer via USB OTG adapter and power on")
                }
            }

            HorizontalDivider(
                color = HairlineStone,
                modifier = Modifier.padding(vertical = 14.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPreviewTestPage,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = InkBlack,
                        contentColor = SheetWhite
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("diag_test_page")
                ) {
                    Icon(Icons.Rounded.Print, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Test Sheet", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                OutlinedButton(
                    onClick = onSoftReset,
                    enabled = usbStatus is UsbPrinterStatus.Connected,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, HairlineStone),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("diag_soft_reset")
                ) {
                    Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Soft Reset", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onOpenSystemPrintSettings,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, HairlineStone),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("open_print_settings_button")
            ) {
                Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_enable_print_service), color = InkBlack)
            }
        }
    }
}

@Composable
private fun TelemetryRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = GraphiteMuted,
            modifier = Modifier.width(92.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = InkBlack,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun FlagBadge(label: String, isOk: Boolean, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isOk) EmeraldSoft else CrimsonSoft,
        border = BorderStroke(1.dp, (if (isOk) EmeraldReady else CrimsonError).copy(alpha = 0.25f)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (isOk) EmeraldReady else CrimsonError,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun PrintHistoryCard(
    record: PrintJobRecord,
    onDelete: () -> Unit
) {
    val statusEnum = runCatching { PrintJobStatus.valueOf(record.status) }
        .getOrDefault(PrintJobStatus.COMPLETED)
    val (bg, fg, icon) = when (statusEnum) {
        PrintJobStatus.COMPLETED -> Triple(EmeraldSoft, EmeraldReady, Icons.Rounded.CheckCircle)
        PrintJobStatus.CANCELLED -> Triple(AmberSoft, AmberWarn, Icons.Rounded.ErrorOutline)
        PrintJobStatus.FAILED -> Triple(CrimsonSoft, CrimsonError, Icons.Rounded.ErrorOutline)
    }

    val dateStr = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        .format(Date(record.timestampMs))
    val kbText = String.format(Locale.US, "%.1f KB", record.bytesTransferred / 1024f)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = SheetWhite,
        border = BorderStroke(1.dp, HairlineStone),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = CobaltSoft,
                        shape = RoundedCornerShape(5.dp)
                    ) {
                        Text(
                            text = record.documentType.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = CobaltAccent,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = record.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        color = InkBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "${record.pageCount} pg × ${record.copies} • ${record.paperSize} • ${record.qualityLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = GraphiteMuted
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$dateStr • $kbText • ${record.durationMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = GraphiteMuted
                )

                if (!record.errorMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = record.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = CrimsonError
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = bg
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = fg,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = statusEnum.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = fg
                        )
                    }
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_job_${record.id}")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Delete job record",
                        tint = GraphiteMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
