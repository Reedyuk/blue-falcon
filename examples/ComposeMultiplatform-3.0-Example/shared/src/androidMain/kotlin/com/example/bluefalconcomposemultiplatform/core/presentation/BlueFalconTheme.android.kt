package com.example.bluefalconcomposemultiplatform.core.presentation

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.bluefalconcomposemultiplatform.ui.theme.DarkColorScheme
import com.example.bluefalconcomposemultiplatform.ui.theme.LightColorScheme
import com.example.bluefalconcomposemultiplatform.ui.theme.Typography

@Composable
actual fun BlueFalconTheme(
    darkTheme: Boolean,
    dynamicColor: Boolean,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if(darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if(!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(
                window,
                view
            ).isAppearanceLightStatusBars = darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
    ) {
        // MaterialTheme alone only publishes the color scheme via composition
        // locals - it paints nothing and leaves LocalContentColor at its
        // hardcoded default (black), regardless of the scheme's onBackground.
        // A Surface is what actually paints colorScheme.background and sets
        // the default text/icon color to colorScheme.onBackground, which is
        // why dark mode previously showed a light system background with
        // unreadable dark-on-dark (or invisible) text.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colorScheme.background,
            contentColor = colorScheme.onBackground,
        ) {
            content()
        }
    }
}