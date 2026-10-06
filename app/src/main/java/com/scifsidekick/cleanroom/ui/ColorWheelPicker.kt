package com.scifsidekick.cleanroom.ui

import android.graphics.Bitmap
import android.graphics.Paint as AndroidPaint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val WHEEL_BITMAP_PX = 512
private val WHEEL_DIAMETER = 240.dp

/**
 * Renders a hue/saturation wheel as a bitmap once: a full-saturation hue sweep around the
 * circle (SweepGradient), overlaid with a white-center radial gradient composited with
 * SRC_ATOP so saturation fades to white toward the center. Value/brightness is handled by a
 * separate slider rather than baked into the wheel, which is the standard split for this kind
 * of picker.
 *
 * A 512x512 ARGB_8888 bitmap plus a 361-stop SweepGradient is real Canvas work, not something to
 * run inline in a `remember { }` block during composition -- that would execute synchronously on
 * the UI thread the instant the dialog opens, a needless hitch right when the dialog's own open
 * animation is running. [rememberHueSaturationWheel] below computes it on [Dispatchers.Default]
 * instead.
 */
private fun buildHueSaturationWheel(sizePx: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val radius = sizePx / 2f

    val hueColors = IntArray(361) { i -> android.graphics.Color.HSVToColor(floatArrayOf(i.toFloat(), 1f, 1f)) }
    val huePaint =
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            shader = SweepGradient(radius, radius, hueColors, null)
        }
    canvas.drawCircle(radius, radius, radius, huePaint)

    val saturationPaint =
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            shader =
                RadialGradient(
                    radius,
                    radius,
                    radius,
                    android.graphics.Color.WHITE,
                    android.graphics.Color.TRANSPARENT,
                    Shader.TileMode.CLAMP,
                )
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        }
    canvas.drawCircle(radius, radius, radius, saturationPaint)
    return bitmap
}

/** [buildHueSaturationWheel], off the UI thread. Null until the first frame after the dialog
 *  opens; the dialog itself (slider, hex field, drag/tap handling -- none of which read the
 *  bitmap) is fully interactive before that, so there's nothing to block on while it's generated. */
@Composable
private fun rememberHueSaturationWheel(sizePx: Int): ImageBitmap? {
    val state =
        produceState<ImageBitmap?>(initialValue = null, sizePx) {
            value = withContext(Dispatchers.Default) { buildHueSaturationWheel(sizePx).asImageBitmap() }
        }
    return state.value
}

private fun Color.toHsv(): FloatArray {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    return hsv
}

fun Color.toHexString(): String = "#%06X".format(toArgb() and 0x00FFFFFF)

private fun hexOrNull(text: String): Color? {
    val cleaned = text.trim().removePrefix("#")
    if (cleaned.length != 6 || cleaned.any { it.digitToIntOrNull(16) == null }) return null
    return runCatching { Color(android.graphics.Color.parseColor("#$cleaned")) }.getOrNull()
}

/** Converts a touch offset within a square of side [boxSizePx] into (hue, saturation). */
private fun offsetToHueSaturation(
    offset: Offset,
    boxSizePx: Float,
): Pair<Float, Float> {
    val center = boxSizePx / 2f
    val dx = offset.x - center
    val dy = offset.y - center
    val distance = sqrt(dx * dx + dy * dy).coerceAtMost(center)
    val angle = ((atan2(dy, dx) * 180f / Math.PI.toFloat()) + 360f) % 360f
    val saturation = if (center > 0f) (distance / center).coerceIn(0f, 1f) else 0f
    return angle to saturation
}

@Composable
fun ColorWheelDialog(
    initialColor: Color,
    onDismiss: () -> Unit,
    onConfirm: (Color) -> Unit,
) {
    val initialHsv = remember(initialColor) { initialColor.toHsv() }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    // Never start fully black: a value of 0 would make the wheel/hex feedback look "stuck".
    var value by remember { mutableFloatStateOf(initialHsv[2].coerceAtLeast(0.2f)) }
    var hexText by remember { mutableStateOf(initialColor.toHexString()) }

    val currentColor = Color.hsv(hue, saturation, value)
    val wheelBitmap = rememberHueSaturationWheel(WHEEL_BITMAP_PX)

    fun applyHueSaturation(
        newHue: Float,
        newSaturation: Float,
    ) {
        hue = newHue
        saturation = newSaturation
        hexText = Color.hsv(hue, saturation, value).toHexString()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom accent color") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier =
                        Modifier
                            .size(WHEEL_DIAMETER)
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    val (h, s) = offsetToHueSaturation(offset, size.width.toFloat())
                                    applyHueSaturation(h, s)
                                }
                            }.pointerInput(Unit) {
                                detectDragGestures { change, _ ->
                                    change.consume()
                                    val (h, s) = offsetToHueSaturation(change.position, size.width.toFloat())
                                    applyHueSaturation(h, s)
                                }
                            },
                ) {
                    wheelBitmap?.let {
                        Image(
                            bitmap = it,
                            contentDescription = "Hue and saturation",
                            modifier = Modifier.size(WHEEL_DIAMETER),
                        )
                    }
                    Canvas(Modifier.size(WHEEL_DIAMETER)) {
                        val cx = this.size.width / 2f
                        val cy = this.size.height / 2f
                        val radius = min(cx, cy)
                        val angleRad = hue * Math.PI.toFloat() / 180f
                        val marker = Offset(cx + cos(angleRad) * saturation * radius, cy + sin(angleRad) * saturation * radius)
                        drawCircle(color = Color.White, radius = 9f, center = marker, style = Stroke(width = 3f))
                        drawCircle(color = Color.Black, radius = 9f, center = marker, style = Stroke(width = 1f))
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("Brightness", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = value,
                    onValueChange = {
                        value = it
                        hexText = Color.hsv(hue, saturation, value).toHexString()
                    },
                    valueRange = 0.1f..1f,
                )

                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Canvas(Modifier.size(40.dp).clip(CircleShape)) {
                        drawCircle(color = currentColor)
                    }
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { text ->
                            hexText = text
                            hexOrNull(text)?.let { parsed ->
                                val hsv = parsed.toHsv()
                                hue = hsv[0]
                                saturation = hsv[1]
                                value = hsv[2].coerceAtLeast(0.1f)
                            }
                        },
                        label = { Text("Hex") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(currentColor) }) { Text("Use this color") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
