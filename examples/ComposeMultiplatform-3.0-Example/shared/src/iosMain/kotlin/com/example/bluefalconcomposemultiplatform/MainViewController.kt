package com.example.bluefalconcomposemultiplatform

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.window.ComposeUIViewController
import com.example.bluefalconcomposemultiplatform.di.AppModule
import platform.UIKit.UIScreen
import platform.UIKit.UIUserInterfaceStyle

fun MainViewController() = ComposeUIViewController {
    val isDarkTheme =
        UIScreen.mainScreen.traitCollection.userInterfaceStyle ==
                UIUserInterfaceStyle.UIUserInterfaceStyleDark
    App(
        darkTheme = isDarkTheme,
        dynamicColor = false,
        appModule = AppModule()
    )
}