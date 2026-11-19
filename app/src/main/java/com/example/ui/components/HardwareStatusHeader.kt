package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.UsbOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
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

@Composable
fun HardwareStatusHeader(
    usbStatus: UsbPrinterStatus,
    onRefreshUsb: () -> Unit,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (bgColor, fgColor, dotColor, label) = when (usbStatus) {
        is UsbPrinterStatus.Connected -> {
            when {
                usbStatus.details.portFlags.paperEmpty -> StatusVisual(
                    CrimsonSoft,
                    CrimsonError,
                    CrimsonError,
                    "Paper Empty"
                )
                usbStatus.isBusy -> StatusVisual(
                    CobaltSoft,
                    CobaltAccent,
                    CobaltAccent,
                    stringResource(R.string.usb_printing)
                )
                !usbStatus.details.portFlags.noError -> StatusVisual(
                    CrimsonSoft,
                    CrimsonError,
                    CrimsonError,
                    "Printer Fault"
                )
                else -> StatusVisual(
                    EmeraldSoft,
                    EmeraldReady,
                    EmeraldReady,
                    if (usbStatus.details.isScx3200) "SCX-3200 Ready" else usbStatus.details.productName
                )
            }
        }
        is UsbPrinterStatus.PermissionRequired -> StatusVisual(
            AmberSoft,
            AmberWarn,
            AmberWarn,
            "Tap to Grant USB"
        )
        is UsbPrinterStatus.Error -> StatusVisual(
            CrimsonSoft,
            CrimsonError,
            CrimsonError,
            "USB Error"
        )
        UsbPrinterStatus.UsbNotSupported -> StatusVisual(
            WarmStone,
            GraphiteMuted,
            GraphiteMuted,
            "No USB Host"
        )
        UsbPrinterStatus.Disconnected -> StatusVisual(
            WarmStone,
            GraphiteMuted,
            GraphiteMuted,
            "OTG Disconnected"
        )
    }

    val animatedBg by animateColorAsState(bgColor, label = "status_bg")

    Surface(
        color = SheetWhite,
        border = BorderStroke(1.dp, HairlineStone),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(InkBlack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Print,
                        contentDescription = null,
                        tint = SheetWhite,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        color = InkBlack
                    )
                    val subtitle = when (usbStatus) {
                        is UsbPrinterStatus.Connected -> "USB ${usbStatus.details.vidPidHex}"
                        is UsbPrinterStatus.PermissionRequired -> "USB ${usbStatus.details.vidPidHex}"
                        else -> "Samsung SCX-3200 Driver"
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = GraphiteMuted
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = animatedBg,
                    border = BorderStroke(1.dp, fgColor.copy(alpha = 0.25f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable {
                            if (usbStatus is UsbPrinterStatus.PermissionRequired) {
                                onRequestPermission()
                            } else {
                                onRefreshUsb()
                            }
                        }
                        .testTag("usb_status_pill")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = when (usbStatus) {
                                is UsbPrinterStatus.Connected -> Icons.Rounded.Usb
                                is UsbPrinterStatus.PermissionRequired -> Icons.Rounded.WarningAmber
                                else -> Icons.Rounded.UsbOff
                            },
                            contentDescription = label,
                            tint = fgColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = fgColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                IconButton(
                    onClick = onRefreshUsb,
                    modifier = Modifier.testTag("usb_refresh_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.action_scan_usb),
                        tint = InkBlack,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

private data class StatusVisual(
    val bg: androidx.compose.ui.graphics.Color,
    val fg: androidx.compose.ui.graphics.Color,
    val dot: androidx.compose.ui.graphics.Color,
    val text: String
)
