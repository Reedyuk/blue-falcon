package com.example.bluefalconcomposemultiplatform.core.presentation

import androidx.compose.runtime.Composable

@Composable
expect fun BlueFalconTheme(
    darkTheme: Boolean,
    dynamicColor: Boolean,
    content: @Composable () -> Unit
)