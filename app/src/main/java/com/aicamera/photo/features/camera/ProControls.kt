package com.aicamera.photo.features.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aicamera.photo.core.camera.CameraControlRequest
import com.aicamera.photo.core.camera.CameraState
import com.aicamera.photo.core.camera.WhiteBalancePreset
import com.aicamera.photo.ui.theme.LumenColors
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** Manual controls, shown only for capabilities the camera actually reports. */
@Composable
fun ProControls(state: CameraState, onRequest: (CameraControlRequest) -> Unit, modifier: Modifier = Modifier) {
    val caps = state.capabilities ?: return
    val manual = state.manual
    val live = state.live
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(LumenColors.ScrimStrong)
            .padding(12.dp),
    ) {
        if (caps.manualSensor && caps.isoRange != null && caps.exposureTimeRangeNs != null) {
            val isoRange = caps.isoRange!!
            ManualRow(
                label = "ISO",
                value = (manual.iso ?: live.iso)?.toString() ?: "Auto",
                auto = manual.iso == null,
                onAuto = { onRequest(CameraControlRequest.ManualIso(if (it) null else (live.iso ?: 100))) },
                fraction = logFraction((manual.iso ?: live.iso ?: isoRange.first).toDouble(), isoRange.first.toDouble(), isoRange.last.toDouble()),
                onFraction = { f -> onRequest(CameraControlRequest.ManualIso(logValue(f, isoRange.first.toDouble(), isoRange.last.toDouble()).roundToInt())) },
            )
            val exp = caps.exposureTimeRangeNs!!
            // Cap the slider at 1/2 s so the live preview stays usable.
            val maxNs = minOf(exp.last, 500_000_000L).toDouble()
            ManualRow(
                label = "Shutter",
                value = (manual.exposureTimeNs ?: live.exposureTimeNs)?.let { shutterLabel(it) } ?: "Auto",
                auto = manual.exposureTimeNs == null,
                onAuto = { onRequest(CameraControlRequest.ManualShutter(if (it) null else (live.exposureTimeNs ?: 16_666_666L))) },
                fraction = logFraction((manual.exposureTimeNs ?: live.exposureTimeNs ?: exp.first).toDouble().coerceAtMost(maxNs), exp.first.toDouble(), maxNs),
                onFraction = { f -> onRequest(CameraControlRequest.ManualShutter(logValue(f, exp.first.toDouble(), maxNs).toLong())) },
            )
        }
        if (caps.manualFocus) {
            val d = manual.focusDiopters ?: live.focusDiopters ?: 0f
            ManualRow(
                label = "Focus",
                value = if (manual.focusDiopters == null) "Auto" else if (d <= 0.01f) "∞" else String.format("%.2f m", 1f / d),
                auto = manual.focusDiopters == null,
                onAuto = { onRequest(CameraControlRequest.ManualFocus(if (it) null else d)) },
                fraction = (d / caps.minFocusDistance).coerceIn(0f, 1f),
                onFraction = { f -> onRequest(CameraControlRequest.ManualFocus(f * caps.minFocusDistance)) },
            )
        }
        val presets = WhiteBalancePreset.entries.filter { it.awbMode in caps.awbModes }
        if (presets.size > 1) {
            Text("White balance", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                presets.forEach { p ->
                    FilterChip(selected = manual.whiteBalance == p, onClick = { onRequest(CameraControlRequest.WhiteBalance(p)) }, label = { Text(p.label) })
                }
            }
        }
        if (!caps.manualSensor && !caps.manualFocus && presets.size <= 1) {
            Text("This lens does not report manual controls.", color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (manual.manualExposure) "Manual exposure: the preview follows your settings." else "Auto exposure, focus and white balance.",
                style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onRequest(CameraControlRequest.ResetManual) }) { Text("Reset all") }
        }
    }
}

@Composable
private fun ManualRow(
    label: String, value: String, auto: Boolean, onAuto: (Boolean) -> Unit,
    fraction: Float, onFraction: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(60.dp))
        Slider(
            value = fraction, onValueChange = onFraction, enabled = !auto,
            modifier = Modifier.weight(1f),
        )
        Text(value, color = LumenColors.Amber, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp).padding(start = 6.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Switch(checked = auto, onCheckedChange = onAuto)
            Text("Auto", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        }
    }
}

private fun logFraction(v: Double, min: Double, max: Double): Float =
    ((ln(v.coerceIn(min, max)) - ln(min)) / (ln(max) - ln(min))).toFloat().coerceIn(0f, 1f)

private fun logValue(f: Float, min: Double, max: Double): Double = exp(ln(min) + f * (ln(max) - ln(min)))

fun shutterLabel(ns: Long): String {
    val s = ns / 1e9
    return if (s >= 0.4) String.format("%.1fs", s) else "1/${(1 / s).roundToInt()}"
}
