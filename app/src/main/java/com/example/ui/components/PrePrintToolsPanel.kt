package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.DitherMode
import com.example.model.DocumentSource
import com.example.model.ImageAdjustmentState
import com.example.ui.theme.CobaltAccent
import com.example.ui.theme.CobaltSoft
import com.example.ui.theme.GraphiteMuted
import com.example.ui.theme.HairlineStone
import com.example.ui.theme.InkBlack
import com.example.ui.theme.SheetWhite
import kotlin.math.roundToInt

@Composable
fun PrePrintToolsPanel(
    document: DocumentSource?,
    adjustments: ImageAdjustmentState,
    onUpdateAdjustments: ((ImageAdjustmentState) -> ImageAdjustmentState) -> Unit,
    onOpenCropModal: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = SheetWhite,
        border = BorderStroke(1.dp, HairlineStone),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = null,
                        tint = CobaltAccent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Monochrome Laser Tuning",
                        style = MaterialTheme.typography.titleSmall,
                        color = InkBlack
                    )
                }

                TextButton(
                    onClick = onReset,
                    modifier = Modifier.testTag("reset_adjustments_button")
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

            if (document is DocumentSource.ImageDocument) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenCropModal,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, HairlineStone),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("open_crop_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Crop,
                            contentDescription = null,
                            tint = InkBlack,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Crop Area", color = InkBlack)
                    }

                    OutlinedButton(
                        onClick = {
                            onUpdateAdjustments {
                                it.copy(rotationDegrees = (it.rotationDegrees + 90) % 360)
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, HairlineStone),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("quick_rotate_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.RotateRight,
                            contentDescription = null,
                            tint = InkBlack,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Rotate ${adjustments.rotationDegrees}°", color = InkBlack)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Dither Mode",
                style = MaterialTheme.typography.labelMedium,
                color = GraphiteMuted
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DitherMode.entries.forEach { mode ->
                    val selected = adjustments.ditherMode == mode
                    FilterChip(
                        selected = selected,
                        onClick = { onUpdateAdjustments { it.copy(ditherMode = mode) } },
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
                            .testTag("dither_${mode.name.lowercase()}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Contrast", style = MaterialTheme.typography.bodyMedium, color = InkBlack)
                Text(
                    text = "${(adjustments.contrast * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = GraphiteMuted
                )
            }
            Slider(
                value = adjustments.contrast,
                onValueChange = { value -> onUpdateAdjustments { it.copy(contrast = value) } },
                valueRange = 0.6f..1.8f,
                colors = SliderDefaults.colors(
                    thumbColor = InkBlack,
                    activeTrackColor = CobaltAccent
                ),
                modifier = Modifier.testTag("contrast_slider")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Brightness", style = MaterialTheme.typography.bodyMedium, color = InkBlack)
                Text(
                    text = "${if (adjustments.brightness >= 0) "+" else ""}${(adjustments.brightness * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = GraphiteMuted
                )
            }
            Slider(
                value = adjustments.brightness,
                onValueChange = { value -> onUpdateAdjustments { it.copy(brightness = value) } },
                valueRange = -0.35f..0.35f,
                colors = SliderDefaults.colors(
                    thumbColor = InkBlack,
                    activeTrackColor = CobaltAccent
                ),
                modifier = Modifier.testTag("brightness_slider")
            )
        }
    }
}
