package com.example.bluefalconcomposemultiplatform.core.presentation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.bluefalconcomposemultiplatform.ui.theme.DarkColorScheme
import com.example.bluefalconcomposemultiplatform.ui.theme.LightColorScheme
import com.example.bluefalconcomposemultiplatform.ui.theme.Typography

@Composable
actual fun BlueFalconTheme(
    darkTheme: Boolean,
    dynamicColor: Boolean,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
    ) {
        // MaterialTheme alone only publishes the color scheme via composition
        // locals - it paints nothing and leaves LocalContentColor at its
        // hardcoded default (black), regardless of the scheme's onBackground.
        // A Surface is what actually paints colorScheme.background and sets
        // the default text/icon color to colorScheme.onBackground.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colorScheme.background,
            contentColor = colorScheme.onBackground,
        ) {
            content()
        }
    }
}