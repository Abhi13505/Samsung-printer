package com.example.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.document.MonochromeProcessor
import com.example.model.ImageAdjustmentState
import com.example.ui.theme.CobaltAccent
import com.example.ui.theme.HairlineStone
import com.example.ui.theme.InkBlack
import com.example.ui.theme.PaperWhite
import com.example.ui.theme.SheetWhite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot

@Composable
fun CropToolModal(
    imageUri: Uri,
    initialAdjustments: ImageAdjustmentState,
    onApply: (left: Float, top: Float, right: Float, bottom: Float, rotationDeg: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var rawBitmap by remember(imageUri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(imageUri) {
        rawBitmap = withContext(Dispatchers.IO) {
            MonochromeProcessor.loadScaledBitmap(context, imageUri, 1024)
        }
    }

    var left by remember { mutableFloatStateOf(initialAdjustments.cropLeft) }
    var top by remember { mutableFloatStateOf(initialAdjustments.cropTop) }
    var right by remember { mutableFloatStateOf(initialAdjustments.cropRight) }
    var bottom by remember { mutableFloatStateOf(initialAdjustments.cropBottom) }
    var rotation by remember { mutableIntStateOf(initialAdjustments.rotationDegrees) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var activeHandle by remember { mutableIntStateOf(0) } // 1=TL, 2=TR, 3=BL, 4=BR, 5=MOVE

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = PaperWhite,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, HairlineStone),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Crop & Rotate",
                        style = MaterialTheme.typography.titleLarge,
                        color = InkBlack
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("crop_close_button")
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close crop tool")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                val bmp = rawBitmap
                if (bmp != null) {
                    val aspect = (bmp.width.toFloat() / bmp.height.toFloat()).coerceIn(0.45f, 2.0f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspect)
                            .background(InkBlack)
                            .onSizeChanged { boxSize = it }
                            .pointerInput(boxSize) {
                                detectDragGestures(
                                    onDragStart = { startOffset ->
                                        if (boxSize.width == 0 || boxSize.height == 0) return@detectDragGestures
                                        val w = boxSize.width.toFloat()
                                        val h = boxSize.height.toFloat()
                                        val tl = Offset(left * w, top * h)
                                        val tr = Offset(right * w, top * h)
                                        val bl = Offset(left * w, bottom * h)
                                        val br = Offset(right * w, bottom * h)
                                        val threshold = 64f
                                        activeHandle = when {
                                            hypot(startOffset.x - tl.x, startOffset.y - tl.y) < threshold -> 1
                                            hypot(startOffset.x - tr.x, startOffset.y - tr.y) < threshold -> 2
                                            hypot(startOffset.x - bl.x, startOffset.y - bl.y) < threshold -> 3
                                            hypot(startOffset.x - br.x, startOffset.y - br.y) < threshold -> 4
                                            startOffset.x in tl.x..tr.x && startOffset.y in tl.y..bl.y -> 5
                                            else -> 0
                                        }
                                    },
                                    onDragEnd = { activeHandle = 0 },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        if (boxSize.width == 0 || boxSize.height == 0) return@detectDragGestures
                                        val dx = dragAmount.x / boxSize.width.toFloat()
                                        val dy = dragAmount.y / boxSize.height.toFloat()
                                        val minSpan = 0.15f
                                        when (activeHandle) {
                                            1 -> {
                                                left = (left + dx).coerceIn(0f, right - minSpan)
                                                top = (top + dy).coerceIn(0f, bottom - minSpan)
                                            }
                                            2 -> {
                                                right = (right + dx).coerceIn(left + minSpan, 1f)
                                                top = (top + dy).coerceIn(0f, bottom - minSpan)
                                            }
                                            3 -> {
                                                left = (left + dx).coerceIn(0f, right - minSpan)
                                                bottom = (bottom + dy).coerceIn(top + minSpan, 1f)
                                            }
                                            4 -> {
                                                right = (right + dx).coerceIn(left + minSpan, 1f)
                                                bottom = (bottom + dy).coerceIn(top + minSpan, 1f)
                                            }
                                            5 -> {
                                                val spanW = right - left
                                                val spanH = bottom - top
                                                val newL = (left + dx).coerceIn(0f, 1f - spanW)
                                                val newT = (top + dy).coerceIn(0f, 1f - spanH)
                                                left = newL
                                                right = newL + spanW
                                                top = newT
                                                bottom = newT + spanH
                                            }
                                        }
                                    }
                                )
                            }
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Crop preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )

                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val cropL = left * size.width
                            val cropT = top * size.height
                            val cropR = right * size.width
                            val cropB = bottom * size.height

                            // Dim outside crop region
                            val mask = Color.Black.copy(alpha = 0.5f)
                            drawRect(mask, Offset.Zero, Size(size.width, cropT))
                            drawRect(mask, Offset(0f, cropB), Size(size.width, size.height - cropB))
                            drawRect(mask, Offset(0f, cropT), Size(cropL, cropB - cropT))
                            drawRect(mask, Offset(cropR, cropT), Size(size.width - cropR, cropB - cropT))

                            drawRect(
                                color = Color.White,
                                topLeft = Offset(cropL, cropT),
                                size = Size(cropR - cropL, cropB - cropT),
                                style = Stroke(width = 2.5f)
                            )

                            // Corner handles
                            val handleR = 11f
                            listOf(
                                Offset(cropL, cropT),
                                Offset(cropR, cropT),
                                Offset(cropL, cropB),
                                Offset(cropR, cropB)
                            ).forEach { corner ->
                                drawCircle(color = CobaltAccent, radius = handleR, center = corner)
                                drawCircle(
                                    color = Color.White,
                                    radius = handleR,
                                    center = corner,
                                    style = Stroke(width = 2f)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { rotation = (rotation + 90) % 360 },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, HairlineStone),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("crop_rotate_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.RotateRight,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Rotate (${rotation}°)")
                    }

                    OutlinedButton(
                        onClick = {
                            left = 0f
                            top = 0f
                            right = 1f
                            bottom = 1f
                            rotation = 0
                        },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, HairlineStone),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("crop_reset_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CropFree,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Full Frame")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = { onApply(left, top, right, bottom, rotation) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = InkBlack,
                        contentColor = SheetWhite
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("crop_apply_button")
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Apply Crop & Rotation")
                }
            }
        }
    }
}
