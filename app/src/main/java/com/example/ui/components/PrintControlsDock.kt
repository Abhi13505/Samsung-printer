package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.DocumentSource
import com.example.model.ImagesPerPage
import com.example.model.PageRangeMode
import com.example.model.PageRangeParser
import com.example.model.PaperSize
import com.example.model.PhotoLayoutOptions
import com.example.model.PrintOrientation
import com.example.model.PrintPhase
import com.example.model.PrintProgressState
import com.example.model.PrintQuality
import com.example.model.PrintSettings
import com.example.model.ScaleMode
import com.example.model.UsbPrinterStatus
import com.example.ui.theme.AmberSoft
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.CobaltAccent
import com.example.ui.theme.CobaltSoft
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.GraphiteMuted
import com.example.ui.theme.HairlineStone
import com.example.ui.theme.InkBlack
import com.example.ui.theme.SheetWhite
import com.example.ui.theme.WarmStone
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrintControlsDock(
    document: DocumentSource?,
    isTestPageSelected: Boolean,
    currentPageIndex: Int,
    settings: PrintSettings,
    progress: PrintProgressState,
    usbStatus: UsbPrinterStatus,
    onUpdateSettings: ((PrintSettings) -> PrintSettings) -> Unit,
    onUpdatePhotoLayout: ((PhotoLayoutOptions) -> PhotoLayoutOptions) -> Unit,
    onOpenPhotoLayoutSheet: () -> Unit,
    onPrintClick: () -> Unit,
    onCancelClick: () -> Unit,
    onRequestUsbPermission: () -> Unit,
    showInlinePrintButton: Boolean,
    modifier: Modifier = Modifier
) {
    val totalPages = if (isTestPageSelected) 1 else (document?.totalPages ?: 0)
    val isImage = document is DocumentSource.ImageDocument

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = SheetWhite,
        border = BorderStroke(1.dp, HairlineStone),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Print Configuration",
                style = MaterialTheme.typography.titleSmall,
                color = InkBlack
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Copies stepper
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Copies", style = MaterialTheme.typography.bodyMedium, color = InkBlack)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = WarmStone,
                    border = BorderStroke(1.dp, HairlineStone)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                onUpdateSettings { it.copy(copies = (it.copies - 1).coerceAtLeast(1)) }
                            },
                            enabled = settings.copies > 1 && !progress.isActive,
                            modifier = Modifier.testTag("copies_minus_button")
                        ) {
                            Icon(Icons.Rounded.Remove, contentDescription = "Decrease copies")
                        }
                        Text(
                            text = "${settings.copies}",
                            style = MaterialTheme.typography.titleMedium,
                            color = InkBlack,
                            modifier = Modifier
                                .padding(horizontal = 10.dp)
                                .testTag("copies_count_text")
                        )
                        IconButton(
                            onClick = {
                                onUpdateSettings { it.copy(copies = (it.copies + 1).coerceAtMost(99)) }
                            },
                            enabled = settings.copies < 99 && !progress.isActive,
                            modifier = Modifier.testTag("copies_plus_button")
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = "Increase copies")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Paper Size
            Text("Paper Size", style = MaterialTheme.typography.labelMedium, color = GraphiteMuted)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PaperSize.entries.forEach { paper ->
                    val selected = settings.paperSize == paper
                    FilterChip(
                        selected = selected,
                        enabled = !progress.isActive,
                        onClick = { onUpdateSettings { it.copy(paperSize = paper) } },
                        label = { Text(paper.label, style = MaterialTheme.typography.bodySmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CobaltSoft,
                            selectedLabelColor = CobaltAccent
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = !progress.isActive,
                            selected = selected,
                            borderColor = HairlineStone,
                            selectedBorderColor = CobaltAccent
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("paper_${paper.name.lowercase()}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Orientation
            Text("Orientation", style = MaterialTheme.typography.labelMedium, color = GraphiteMuted)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PrintOrientation.entries.forEach { orient ->
                    val selected = settings.orientation == orient
                    FilterChip(
                        selected = selected,
                        enabled = !progress.isActive,
                        onClick = { onUpdateSettings { it.copy(orientation = orient) } },
                        label = { Text(orient.label, style = MaterialTheme.typography.bodySmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CobaltSoft,
                            selectedLabelColor = CobaltAccent
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = !progress.isActive,
                            selected = selected,
                            borderColor = HairlineStone,
                            selectedBorderColor = CobaltAccent
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("orientation_${orient.name.lowercase()}")
                    )
                }
            }

            // Photo Size & Layout Section
            if (isImage) {
                Spacer(modifier = Modifier.height(12.dp))
                val layout = settings.photoLayout

                Surface(
                    onClick = onOpenPhotoLayoutSheet,
                    enabled = !progress.isActive,
                    shape = RoundedCornerShape(10.dp),
                    color = WarmStone,
                    border = BorderStroke(1.dp, HairlineStone),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("open_photo_layout_button")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GridView,
                                contentDescription = null,
                                tint = CobaltAccent,
                                modifier = Modifier.size(19.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Photo Size & Layout Options",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = InkBlack
                                )
                                Text(
                                    text = "${layout.imagesPerPage.label} · ${layout.photoSize.shortLabel} · ${layout.marginPreset.shortLabel} margins · ${layout.position.label}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = GraphiteMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = "Open photo layout options",
                            tint = GraphiteMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text("Images per Page", style = MaterialTheme.typography.labelMedium, color = GraphiteMuted)
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        ImagesPerPage.ONE,
                        ImagesPerPage.TWO,
                        ImagesPerPage.FOUR,
                        ImagesPerPage.SIX,
                        ImagesPerPage.NINE,
                        ImagesPerPage.AUTO_GRID
                    ).forEach { countOption ->
                        val selected = layout.imagesPerPage == countOption
                        FilterChip(
                            selected = selected,
                            enabled = !progress.isActive,
                            onClick = {
                                onUpdatePhotoLayout { it.copy(imagesPerPage = countOption) }
                            },
                            label = {
                                Text(
                                    text = countOption.shortLabel,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CobaltSoft,
                                selectedLabelColor = CobaltAccent
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = !progress.isActive,
                                selected = selected,
                                borderColor = HairlineStone,
                                selectedBorderColor = CobaltAccent
                            ),
                            modifier = Modifier.testTag("quick_per_page_${countOption.name.lowercase()}")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text("Photo Scaling", style = MaterialTheme.typography.labelMedium, color = GraphiteMuted)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ScaleMode.entries.forEach { mode ->
                        val selected = settings.scaleMode == mode
                        FilterChip(
                            selected = selected,
                            enabled = !progress.isActive,
                            onClick = { onUpdateSettings { it.copy(scaleMode = mode) } },
                            label = {
                                Text(
                                    text = mode.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CobaltSoft,
                                selectedLabelColor = CobaltAccent
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = !progress.isActive,
                                selected = selected,
                                borderColor = HairlineStone,
                                selectedBorderColor = CobaltAccent
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("scale_${mode.name.lowercase()}")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Quality / Toner Save
            Text("Laser Quality", style = MaterialTheme.typography.labelMedium, color = GraphiteMuted)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PrintQuality.entries.forEach { qual ->
                    val selected = settings.quality == qual
                    FilterChip(
                        selected = selected,
                        enabled = !progress.isActive,
                        onClick = { onUpdateSettings { it.copy(quality = qual) } },
                        label = {
                            Text(
                                text = qual.label,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CobaltSoft,
                            selectedLabelColor = CobaltAccent
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = !progress.isActive,
                            selected = selected,
                            borderColor = HairlineStone,
                            selectedBorderColor = CobaltAccent
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("quality_${qual.name.lowercase()}")
                    )
                }
            }

            // Page Range Selection for multi-page PDF or multi-sheet Photos
            if (totalPages > 1) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Page Range ($totalPages pages)",
                    style = MaterialTheme.typography.labelMedium,
                    color = GraphiteMuted
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PageRangeMode.entries.forEach { rangeMode ->
                        val selected = settings.pageRangeMode == rangeMode
                        FilterChip(
                            selected = selected,
                            enabled = !progress.isActive,
                            onClick = { onUpdateSettings { it.copy(pageRangeMode = rangeMode) } },
                            label = { Text(rangeMode.label, style = MaterialTheme.typography.bodySmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CobaltSoft,
                                selectedLabelColor = CobaltAccent
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = !progress.isActive,
                                selected = selected,
                                borderColor = HairlineStone,
                                selectedBorderColor = CobaltAccent
                            ),
                            modifier = Modifier.testTag("range_${rangeMode.name.lowercase()}")
                        )
                    }
                }

                if (settings.pageRangeMode != PageRangeMode.CUSTOM) {
                    val resolved = PageRangeParser.resolvePages(
                        mode = settings.pageRangeMode,
                        totalPages = totalPages,
                        currentPageIndex = currentPageIndex,
                        customExpression = settings.customPageRange
                    )
                    val previewSample = resolved.take(6).joinToString(", ") { (it + 1).toString() }
                    val suffix = if (resolved.size > 6) ", …" else ""
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = when (settings.pageRangeMode) {
                            PageRangeMode.ODD -> "Odd pages ($previewSample$suffix) • ${resolved.size} page(s) queued"
                            PageRangeMode.EVEN -> "Even pages ($previewSample$suffix) • ${resolved.size} page(s) queued"
                            PageRangeMode.CURRENT -> "Current page (${currentPageIndex + 1} of $totalPages)"
                            PageRangeMode.ALL -> "All $totalPages pages queued"
                            PageRangeMode.CUSTOM -> ""
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = GraphiteMuted,
                        modifier = Modifier.testTag("page_range_summary")
                    )
                }

                AnimatedVisibility(visible = settings.pageRangeMode == PageRangeMode.CUSTOM) {
                    val parsed = PageRangeParser.parseZeroIndexed(settings.customPageRange, totalPages)
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedTextField(
                            value = settings.customPageRange,
                            onValueChange = { txt ->
                                onUpdateSettings { it.copy(customPageRange = txt) }
                            },
                            label = { Text("Pages (e.g. 1-3, 5)") },
                            singleLine = true,
                            isError = parsed.isEmpty(),
                            supportingText = {
                                Text(
                                    text = if (parsed.isEmpty()) {
                                        "Enter valid page numbers between 1 and $totalPages"
                                    } else {
                                        "${parsed.size} page(s) queued"
                                    },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CobaltAccent,
                                unfocusedBorderColor = HairlineStone
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("custom_page_range_input")
                        )
                    }
                }
            }

            // Live USB Transfer Progress
            AnimatedVisibility(visible = progress.isActive || progress.phase == PrintPhase.FAILED) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = WarmStone,
                    border = BorderStroke(1.dp, HairlineStone),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = progress.phase.label,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (progress.phase == PrintPhase.FAILED) CrimsonError else InkBlack
                            )
                            if (progress.totalBytes > 0L) {
                                val sentKb = progress.bytesTransferred / 1024f
                                val totalKb = progress.totalBytes / 1024f
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "%.0f / %.0f KB (%.0f KB/s)",
                                        sentKb,
                                        totalKb,
                                        progress.speedKbPerSec
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = GraphiteMuted
                                )
                            } else if (progress.bytesTransferred > 0L || progress.totalPages > 0) {
                                val sentKb = progress.bytesTransferred / 1024f
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "Page %d/%d • %.0f KB (%.0f KB/s)",
                                        progress.currentPage.coerceAtLeast(1),
                                        progress.totalPages.coerceAtLeast(1),
                                        sentKb,
                                        progress.speedKbPerSec
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = GraphiteMuted
                                )
                            }
                        }
                        if (progress.isActive) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { progress.fraction },
                                color = CobaltAccent,
                                trackColor = HairlineStone,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (!progress.errorMessage.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = progress.errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = CrimsonError
                            )
                        }
                    }
                }
            }

            // USB Permission prompt banner if needed
            if (usbStatus is UsbPrinterStatus.PermissionRequired) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = AmberSoft,
                    border = BorderStroke(1.dp, AmberWarn.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "USB OTG access required for SCX-3200",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkBlack,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = onRequestUsbPermission,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, AmberWarn),
                            modifier = Modifier.testTag("grant_usb_inline_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Usb,
                                contentDescription = null,
                                tint = AmberWarn,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Grant", color = InkBlack)
                        }
                    }
                }
            }

            if (showInlinePrintButton) {
                Spacer(modifier = Modifier.height(14.dp))
                PrintPrimaryActionButton(
                    hasDocument = document != null || isTestPageSelected,
                    progress = progress,
                    onPrintClick = onPrintClick,
                    onCancelClick = onCancelClick
                )
            }
        }
    }
}

@Composable
fun PrintPrimaryActionButton(
    hasDocument: Boolean,
    progress: PrintProgressState,
    onPrintClick: () -> Unit,
    onCancelClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (progress.isActive) {
        OutlinedButton(
            onClick = onCancelClick,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, CrimsonError),
            modifier = modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("cancel_print_button")
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = null,
                tint = CrimsonError,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.action_cancel_job), color = CrimsonError)
        }
    } else {
        Button(
            onClick = onPrintClick,
            enabled = hasDocument,
            colors = ButtonDefaults.buttonColors(
                containerColor = InkBlack,
                contentColor = SheetWhite,
                disabledContainerColor = HairlineStone,
                disabledContentColor = GraphiteMuted
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("print_usb_button")
        ) {
            Icon(
                imageVector = Icons.Rounded.Print,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.action_print_usb))
        }
    }
}
