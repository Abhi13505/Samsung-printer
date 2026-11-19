package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.DocumentSource
import com.example.model.PaperSize
import com.example.model.PrintOrientation
import com.example.ui.theme.CobaltAccent
import com.example.ui.theme.CobaltSoft
import com.example.ui.theme.GraphiteMuted
import com.example.ui.theme.HairlineStone
import com.example.ui.theme.InkBlack
import com.example.ui.theme.SheetWhite
import com.example.ui.theme.WarmStone

@Composable
fun PaperCanvasPreview(
    document: DocumentSource?,
    isTestPageSelected: Boolean,
    previewBitmap: Bitmap?,
    isRenderingPreview: Boolean,
    currentPageIndex: Int,
    paperSize: PaperSize,
    orientation: PrintOrientation,
    onPageChange: (Int) -> Unit,
    onPickPhoto: () -> Unit,
    onAddMorePhotos: () -> Unit,
    onPickPdf: () -> Unit,
    onClearDocument: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = WarmStone,
        border = BorderStroke(1.dp, HairlineStone),
        modifier = modifier.fillMaxWidth()
    ) {
        if (document == null && !isTestPageSelected) {
            EmptyPaperCanvas(
                onPickPhoto = onPickPhoto,
                onPickPdf = onPickPdf
            )
        } else {
            val totalPages = if (isTestPageSelected) 1 else (document?.totalPages ?: 1)
            val docTitle = if (isTestPageSelected) {
                "SCX-3200 Diagnostic Sheet"
            } else {
                document?.displayName ?: "Document"
            }
            val typeTag = if (isTestPageSelected) {
                "TEST"
            } else {
                document?.documentType?.displayLabel?.uppercase() ?: "DOC"
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Document bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            color = CobaltSoft,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = typeTag,
                                style = MaterialTheme.typography.labelSmall,
                                color = CobaltAccent,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = docTitle,
                            style = MaterialTheme.typography.titleSmall,
                            color = InkBlack,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(
                        onClick = onClearDocument,
                        modifier = Modifier.testTag("clear_document_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Clear document",
                            tint = GraphiteMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Physical Paper Sheet Preview
                val sheetAspect = when {
                    previewBitmap != null && previewBitmap.height > 0 ->
                        previewBitmap.width.toFloat() / previewBitmap.height.toFloat()
                    orientation == PrintOrientation.LANDSCAPE ->
                        paperSize.heightMm / paperSize.widthMm
                    else -> paperSize.widthMm / paperSize.heightMm
                }.coerceIn(0.55f, 1.8f)

                Surface(
                    color = SheetWhite,
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, HairlineStone),
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .widthIn(max = 360.dp)
                        .heightIn(max = 420.dp)
                        .aspectRatio(sheetAspect)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (previewBitmap != null) {
                            Image(
                                bitmap = previewBitmap.asImageBitmap(),
                                contentDescription = "Monochrome laser print preview of $docTitle",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(4.dp))
                            )
                        }

                        if (isRenderingPreview) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(SheetWhite.copy(alpha = 0.65f)),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = CobaltAccent,
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Page Scrubber + Quick Switch Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (totalPages > 1) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { onPageChange(currentPageIndex - 1) },
                                enabled = currentPageIndex > 0,
                                modifier = Modifier.testTag("prev_page_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                                    contentDescription = "Previous page"
                                )
                            }
                            Text(
                                text = "Page ${currentPageIndex + 1} / $totalPages",
                                style = MaterialTheme.typography.labelMedium,
                                color = InkBlack
                            )
                            IconButton(
                                onClick = { onPageChange(currentPageIndex + 1) },
                                enabled = currentPageIndex < totalPages - 1,
                                modifier = Modifier.testTag("next_page_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                    contentDescription = "Next page"
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "${paperSize.label} • 1-bit Preview",
                            style = MaterialTheme.typography.labelSmall,
                            color = GraphiteMuted
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (document is DocumentSource.ImageDocument) {
                            OutlinedButton(
                                onClick = onAddMorePhotos,
                                border = BorderStroke(1.dp, HairlineStone),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("add_more_photos_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("Add", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        OutlinedButton(
                            onClick = onPickPhoto,
                            border = BorderStroke(1.dp, HairlineStone),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("replace_photo_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AddPhotoAlternate,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Photo", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = onPickPdf,
                            border = BorderStroke(1.dp, HairlineStone),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("replace_pdf_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PictureAsPdf,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("PDF", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyPaperCanvas(
    onPickPhoto: () -> Unit,
    onPickPdf: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = SheetWhite,
            border = BorderStroke(1.dp, HairlineStone),
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.Print,
                    contentDescription = null,
                    tint = CobaltAccent,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = stringResource(R.string.empty_canvas_title),
            style = MaterialTheme.typography.titleLarge,
            color = InkBlack,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.empty_canvas_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = GraphiteMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onPickPhoto,
                colors = ButtonDefaults.buttonColors(
                    containerColor = InkBlack,
                    contentColor = SheetWhite
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("pick_photo_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.AddPhotoAlternate,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Photo",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            OutlinedButton(
                onClick = onPickPdf,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, HairlineStone),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("pick_pdf_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.PictureAsPdf,
                    contentDescription = null,
                    tint = InkBlack,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "PDF",
                    color = InkBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
