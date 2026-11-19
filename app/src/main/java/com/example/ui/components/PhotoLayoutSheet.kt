package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.ImagesPerPage
import com.example.model.PaperSize
import com.example.model.PhotoCutGuide
import com.example.model.PhotoGapPreset
import com.example.model.PhotoLayoutGeometry
import com.example.model.PhotoLayoutOptions
import com.example.model.PhotoMarginPreset
import com.example.model.PhotoPosition
import com.example.model.PhotoSizePreset
import com.example.model.PrintOrientation
import com.example.model.PrintSettings
import com.example.model.ScaleMode
import com.example.ui.theme.CobaltAccent
import com.example.ui.theme.CobaltSoft
import com.example.ui.theme.GraphiteMuted
import com.example.ui.theme.HairlineStone
import com.example.ui.theme.InkBlack
import com.example.ui.theme.PaperWhite
import com.example.ui.theme.SheetWhite
import com.example.ui.theme.WarmStone
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PhotoLayoutSheet(
    photoCount: Int,
    primaryPhotoIsLandscape: Boolean,
    settings: PrintSettings,
    onUpdateSettings: ((PrintSettings) -> PrintSettings) -> Unit,
    onUpdatePhotoLayout: ((PhotoLayoutOptions) -> PhotoLayoutOptions) -> Unit,
    onResetLayout: () -> Unit,
    onAddPhotos: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val layout = settings.photoLayout
    val plan = remember(
        settings.paperSize,
        settings.orientation,
        layout,
        primaryPhotoIsLandscape
    ) {
        PhotoLayoutGeometry.computeSheetPlan(
            paperSize = settings.paperSize,
            orientation = settings.orientation,
            layout = layout,
            primaryPhotoIsLandscape = primaryPhotoIsLandscape
        )
    }
    val totalPages = PhotoLayoutGeometry.computeTotalPhotoPages(
        photoCount = photoCount,
        slotsPerPage = plan.slotsPerPage,
        repeatSinglePhoto = layout.repeatSinglePhoto
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PaperWhite
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.GridView,
                        contentDescription = null,
                        tint = CobaltAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Photo Size & Layout",
                        style = MaterialTheme.typography.titleLarge,
                        color = InkBlack
                    )
                }

                TextButton(
                    onClick = onResetLayout,
                    modifier = Modifier.testTag("photo_layout_reset_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reset", style = MaterialTheme.typography.labelMedium)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Live Blueprint Diagram Card
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = WarmStone,
                border = BorderStroke(1.dp, HairlineStone),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Miniature Sheet Blueprint
                    val sheetAspect = (plan.sheetWidthMm / plan.sheetHeightMm).coerceIn(0.55f, 1.8f)
                    Surface(
                        color = SheetWhite,
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, InkBlack.copy(alpha = 0.25f)),
                        shadowElevation = 1.dp,
                        modifier = Modifier
                            .height(108.dp)
                            .aspectRatio(sheetAspect)
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val sx = size.width / plan.sheetWidthMm
                            val sy = size.height / plan.sheetHeightMm

                            // Margin guide
                            if (plan.marginMm > 0f) {
                                val ml = plan.marginMm * sx
                                val mt = plan.marginMm * sy
                                drawRect(
                                    color = GraphiteMuted.copy(alpha = 0.4f),
                                    topLeft = Offset(ml, mt),
                                    size = Size(size.width - ml * 2f, size.height - mt * 2f),
                                    style = Stroke(
                                        width = 1.5f,
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                                    )
                                )
                            }

                            // Photo cells
                            val activeCount = if (photoCount == 1) {
                                if (layout.repeatSinglePhoto) plan.cellsMm.size else 1
                            } else {
                                minOf(photoCount, plan.cellsMm.size)
                            }
                            plan.cellsMm.forEachIndexed { idx, cell ->
                                val isOccupied = idx < activeCount
                                val l = cell.leftMm * sx
                                val t = cell.topMm * sy
                                val w = cell.widthMm * sx
                                val h = cell.heightMm * sy
                                drawRect(
                                    color = if (isOccupied) CobaltSoft else WarmStone.copy(alpha = 0.6f),
                                    topLeft = Offset(l, t),
                                    size = Size(w, h)
                                )
                                drawRect(
                                    color = if (isOccupied) CobaltAccent else HairlineStone,
                                    topLeft = Offset(l, t),
                                    size = Size(w, h),
                                    style = Stroke(width = if (layout.cutGuide == PhotoCutGuide.THIN_BORDER) 2.5f else 1.5f)
                                )
                            }
                        }
                    }

                    // Live Spec Readout
                    val sampleCell = plan.cellsMm.firstOrNull()
                    val cellWmm = sampleCell?.widthMm?.roundToInt() ?: 0
                    val cellHmm = sampleCell?.heightMm?.roundToInt() ?: 0
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "${settings.paperSize.label} · ${if (plan.sheetIsLandscape) "Landscape" else "Portrait"}",
                            style = MaterialTheme.typography.titleSmall,
                            color = InkBlack
                        )
                        Text(
                            text = "${plan.cols} × ${plan.rows} grid (${plan.slotsPerPage} slot${if (plan.slotsPerPage > 1) "s" else ""}/sheet)",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkBlack
                        )
                        Text(
                            text = "Slot size: ${cellWmm} × ${cellHmm} mm",
                            style = MaterialTheme.typography.labelMedium,
                            color = CobaltAccent
                        )
                        Text(
                            text = "$photoCount photo${if (photoCount > 1) "s" else ""} → $totalPages page${if (totalPages > 1) "s" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = GraphiteMuted
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Scrollable Controls
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Images per Page
                SectionCard(title = "Images per Page") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ImagesPerPage.entries.forEach { option ->
                            val selected = layout.imagesPerPage == option
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    onUpdatePhotoLayout { it.copy(imagesPerPage = option) }
                                },
                                label = { Text(option.shortLabel, style = MaterialTheme.typography.bodySmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CobaltSoft,
                                    selectedLabelColor = CobaltAccent
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = selected,
                                    borderColor = HairlineStone,
                                    selectedBorderColor = CobaltAccent
                                ),
                                modifier = Modifier.testTag("layout_per_page_${option.name.lowercase()}")
                            )
                        }
                    }

                    AnimatedVisibility(visible = plan.slotsPerPage > 1) {
                        Column(modifier = Modifier.padding(top = 10.dp)) {
                            Text(
                                text = "Gap Between Photos",
                                style = MaterialTheme.typography.labelMedium,
                                color = GraphiteMuted
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                PhotoGapPreset.entries.forEach { gap ->
                                    val selected = layout.gapPreset == gap
                                    FilterChip(
                                        selected = selected,
                                        onClick = { onUpdatePhotoLayout { it.copy(gapPreset = gap) } },
                                        label = { Text(gap.label, style = MaterialTheme.typography.bodySmall) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = CobaltSoft,
                                            selectedLabelColor = CobaltAccent
                                        ),
                                        border = FilterChipDefaults.filterChipBorder(
                                            enabled = true,
                                            selected = selected,
                                            borderColor = HairlineStone,
                                            selectedBorderColor = CobaltAccent
                                        ),
                                        modifier = Modifier.testTag("layout_gap_${gap.name.lowercase()}")
                                    )
                                }
                            }

                            if (photoCount == 1) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Repeat photo across grid",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = InkBlack
                                        )
                                        Text(
                                            text = "Ideal for ID / passport sheets",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = GraphiteMuted
                                        )
                                    }
                                    Switch(
                                        checked = layout.repeatSinglePhoto,
                                        onCheckedChange = { checked ->
                                            onUpdatePhotoLayout { it.copy(repeatSinglePhoto = checked) }
                                        },
                                        colors = SwitchDefaults.colors(checkedTrackColor = CobaltAccent),
                                        modifier = Modifier.testTag("layout_repeat_switch")
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onAddPhotos,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, HairlineStone),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("layout_add_more_photos")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AddPhotoAlternate,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Add More Photos ($photoCount loaded)", color = InkBlack)
                            }
                        }
                    }
                }

                // 2. Image Size
                SectionCard(title = "Image Size") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PhotoSizePreset.entries.forEach { preset ->
                            val selected = layout.photoSize == preset
                            FilterChip(
                                selected = selected,
                                onClick = { onUpdatePhotoLayout { it.copy(photoSize = preset) } },
                                label = { Text(preset.label, style = MaterialTheme.typography.bodySmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CobaltSoft,
                                    selectedLabelColor = CobaltAccent
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = selected,
                                    borderColor = HairlineStone,
                                    selectedBorderColor = CobaltAccent
                                ),
                                modifier = Modifier.testTag("layout_size_${preset.name.lowercase()}")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-rotate photo to match slot",
                                style = MaterialTheme.typography.bodyMedium,
                                color = InkBlack
                            )
                            Text(
                                text = "Rotates portrait/landscape mismatch 90°",
                                style = MaterialTheme.typography.labelSmall,
                                color = GraphiteMuted
                            )
                        }
                        Switch(
                            checked = layout.autoRotateToCell,
                            onCheckedChange = { checked ->
                                onUpdatePhotoLayout { it.copy(autoRotateToCell = checked) }
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = CobaltAccent),
                            modifier = Modifier.testTag("layout_autorotate_switch")
                        )
                    }
                }

                // 3. Image Scaling & Paper Orientation
                SectionCard(title = "Image Scaling & Orientation") {
                    Text(
                        text = "Scaling",
                        style = MaterialTheme.typography.labelMedium,
                        color = GraphiteMuted
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ScaleMode.entries.forEach { mode ->
                            val selected = settings.scaleMode == mode
                            FilterChip(
                                selected = selected,
                                onClick = { onUpdateSettings { it.copy(scaleMode = mode) } },
                                label = {
                                    Text(
                                        text = mode.label,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CobaltSoft,
                                    selectedLabelColor = CobaltAccent
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = selected,
                                    borderColor = HairlineStone,
                                    selectedBorderColor = CobaltAccent
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("layout_scale_${mode.name.lowercase()}")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Paper Orientation",
                        style = MaterialTheme.typography.labelMedium,
                        color = GraphiteMuted
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PrintOrientation.entries.forEach { orient ->
                            val selected = settings.orientation == orient
                            FilterChip(
                                selected = selected,
                                onClick = { onUpdateSettings { it.copy(orientation = orient) } },
                                label = {
                                    Text(
                                        text = orient.label,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CobaltSoft,
                                    selectedLabelColor = CobaltAccent
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = selected,
                                    borderColor = HairlineStone,
                                    selectedBorderColor = CobaltAccent
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("layout_orient_${orient.name.lowercase()}")
                            )
                        }
                    }
                }

                // 4. Margins & 3x3 Interactive Position Matrix
                SectionCard(title = "Margins & Position") {
                    Text(
                        text = "Margins",
                        style = MaterialTheme.typography.labelMedium,
                        color = GraphiteMuted
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PhotoMarginPreset.entries.forEach { margin ->
                            val selected = layout.marginPreset == margin
                            FilterChip(
                                selected = selected,
                                onClick = { onUpdatePhotoLayout { it.copy(marginPreset = margin) } },
                                label = { Text(margin.label, style = MaterialTheme.typography.bodySmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CobaltSoft,
                                    selectedLabelColor = CobaltAccent
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = selected,
                                    borderColor = HairlineStone,
                                    selectedBorderColor = CobaltAccent
                                ),
                                modifier = Modifier.testTag("layout_margin_${margin.name.lowercase()}")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Position on Sheet",
                                style = MaterialTheme.typography.labelMedium,
                                color = GraphiteMuted
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = layout.position.label,
                                style = MaterialTheme.typography.titleSmall,
                                color = InkBlack
                            )
                            Text(
                                text = "Tap a spot on the sheet",
                                style = MaterialTheme.typography.labelSmall,
                                color = GraphiteMuted
                            )
                        }

                        PositionMatrixPad(
                            selected = layout.position,
                            onSelect = { pos -> onUpdatePhotoLayout { it.copy(position = pos) } }
                        )
                    }
                }

                // 5. Cut Guides / Border
                SectionCard(title = "Laser Trim & Cut Guides") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PhotoCutGuide.entries.forEach { guide ->
                            val selected = layout.cutGuide == guide
                            FilterChip(
                                selected = selected,
                                onClick = { onUpdatePhotoLayout { it.copy(cutGuide = guide) } },
                                label = {
                                    Text(
                                        text = guide.label,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CobaltSoft,
                                    selectedLabelColor = CobaltAccent
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = selected,
                                    borderColor = HairlineStone,
                                    selectedBorderColor = CobaltAccent
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("layout_cut_${guide.name.lowercase()}")
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
            }

            HorizontalDivider(color = HairlineStone)
            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = InkBlack,
                    contentColor = SheetWhite
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("photo_layout_done_button")
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Apply Layout", fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = SheetWhite,
        border = BorderStroke(1.dp, HairlineStone),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = InkBlack
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun PositionMatrixPad(
    selected: PhotoPosition,
    onSelect: (PhotoPosition) -> Unit
) {
    val rows = listOf(
        listOf(PhotoPosition.TOP_LEFT, PhotoPosition.TOP_CENTER, PhotoPosition.TOP_RIGHT),
        listOf(PhotoPosition.CENTER_LEFT, PhotoPosition.CENTER, PhotoPosition.CENTER_RIGHT),
        listOf(PhotoPosition.BOTTOM_LEFT, PhotoPosition.BOTTOM_CENTER, PhotoPosition.BOTTOM_RIGHT)
    )
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = WarmStone,
        border = BorderStroke(1.dp, HairlineStone)
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            rows.forEach { rowPositions ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    rowPositions.forEach { pos ->
                        val isSelected = selected == pos
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) CobaltSoft else SheetWhite)
                                .clickable { onSelect(pos) }
                                .semantics {
                                    contentDescription = "Align ${pos.label}"
                                }
                                .testTag("layout_pos_${pos.name.lowercase()}")
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(if (isSelected) 12.dp else 8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) CobaltAccent else GraphiteMuted.copy(alpha = 0.45f))
                            )
                        }
                    }
                }
            }
        }
    }
}
