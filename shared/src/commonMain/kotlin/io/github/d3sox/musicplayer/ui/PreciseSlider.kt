package io.github.d3sox.musicplayer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.round

@Composable
internal fun PreciseSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int,
    enabled: Boolean = true, change: (Float) -> Unit, finish: () -> Unit) {
    val step = (range.endInclusive - range.start) / if (steps > 0) steps + 1 else 100
    fun adjust(direction: Int) {
        change((range.start + round((value - range.start) / step + direction) * step).coerceIn(range))
        finish()
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AppIconButton(AppIcon.MINUS, "Decrease $label", enabled = enabled && value > range.start) { adjust(-1) }
        Slider(value, change, Modifier.weight(1f).semantics { contentDescription = label }, enabled = enabled,
            valueRange = range, steps = steps, colors = appSliderColors(), onValueChangeFinished = finish)
        AppIconButton(AppIcon.PLUS, "Increase $label", enabled = enabled && value < range.endInclusive) { adjust(1) }
    }
}
