package com.example.bluefalconcomposemultiplatform.core.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.example.bluefalconcomposemultiplatform.ui.theme.DarkColorScheme
import com.example.bluefalconcomposemultiplatform.ui.theme.LightColorScheme
import com.example.bluefalconcomposemultiplatform.ui.theme.Typography

@Composable
actual fun BlueFalconTheme(
    darkTheme: Boolean,
    dynamicColor: Boolean,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if(darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}