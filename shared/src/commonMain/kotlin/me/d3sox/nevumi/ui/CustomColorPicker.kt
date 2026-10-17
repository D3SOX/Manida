package me.d3sox.nevumi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val customColors = listOf(
    "Red" to 0xFFE53935, "Coral" to 0xFFFF7043, "Orange" to 0xFFFB8C00, "Amber" to 0xFFFFB300,
    "Yellow" to 0xFFFDD835, "Lime" to 0xFFC0CA33, "Green" to 0xFF4CAF50, "Emerald" to 0xFF00875A,
    "Mint" to 0xFF4DB6AC, "Teal" to 0xFF00897B, "Cyan" to 0xFF00ACC1, "Sky blue" to 0xFF29B6F6,
    "Blue" to 0xFF1E88E5, "Indigo" to 0xFF3949AB, "Purple" to 0xFF6750A4, "Violet" to 0xFF8E24AA,
    "Pink" to 0xFFEC407A, "Rose" to 0xFFAD1457, "Brown" to 0xFF795548, "Slate" to 0xFF607D8B,
)

@Composable
internal fun CustomColorPicker(value: Long, change: (Long) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Custom color", style = MaterialTheme.typography.bodyLarge)
                Text(customColors.firstOrNull { it.second == value }?.first ?: "#${(value and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()}",
                    style = MaterialTheme.typography.labelLarge)
            }
            FlowRow(Modifier.widthIn(max = 440.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                customColors.forEach { (name, rgb) ->
                    val color = Color(rgb)
                    val selected = value == rgb
                    IconToggleButton(selected, { change(rgb) }, Modifier.size(48.dp).semantics { contentDescription = "Custom color: $name" },
                        colors = IconButtonDefaults.iconToggleButtonColors(checkedContainerColor = Color.Transparent)) {
                        Box(Modifier.size(40.dp).then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                            .padding(if (selected) 4.dp else 2.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
                            if (selected) Icon(AppIcon.CHECK.vector, null, Modifier.size(22.dp),
                                tint = if (color.luminance() > .179f) Color.Black else Color.White)
                        }
                    }
                }
            }
        }
    }
}
