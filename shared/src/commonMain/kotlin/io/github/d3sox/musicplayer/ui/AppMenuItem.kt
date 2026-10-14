package io.github.d3sox.musicplayer.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
internal fun AppMenuItem(text: @Composable () -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null, enabled: Boolean = true) {
    DropdownMenuItem(text, onClick, modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(12.dp)),
        leadingIcon = leadingIcon, trailingIcon = trailingIcon, enabled = enabled)
}
