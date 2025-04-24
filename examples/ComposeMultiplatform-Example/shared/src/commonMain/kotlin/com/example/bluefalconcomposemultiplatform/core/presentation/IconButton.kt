package com.example.bluefalconcomposemultiplatform.core.presentation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun IconButton(
    size: Dp = 44.dp,
    paddingValues: PaddingValues = PaddingValues(4.dp),
    iconSize: Dp = 30.dp,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Button(
        modifier = Modifier.size(size),
        contentPadding = paddingValues,
        onClick = onClick
    ) {
        Icon(
            modifier = Modifier.size(iconSize),
            imageVector = icon,
            contentDescription = contentDescription
        )
    }
}
