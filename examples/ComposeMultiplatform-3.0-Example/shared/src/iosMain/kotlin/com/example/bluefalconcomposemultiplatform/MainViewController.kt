package com.example.bluefalconcomposemultiplatform

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import com.example.bluefalconcomposemultiplatform.di.AppModule
import platform.UIKit.UIScreen
import platform.UIKit.UIUserInterfaceStyle

fun MainViewController(appModule: AppModule) = ComposeUIViewController(
    configure = {
        // The default (FocusableAboveKeyboard) pans the *entire* Compose view up so a
        // focused field clears the keyboard. Screens like the mesh chat already use
        // Modifier.imePadding() to shrink just the scrollable content and keep their
        // header/controls fixed; combining that with the view-level pan double-shifted
        // everything, pushing the input bar up past the header. DoNothing leaves
        // keyboard avoidance entirely to each screen's own imePadding()/insets, giving
        // one consistent, single source of truth on every platform.
        onFocusBehavior = OnFocusBehavior.DoNothing
    }
) {
    val isDarkTheme =
        UIScreen.mainScreen.traitCollection.userInterfaceStyle ==
                UIUserInterfaceStyle.UIUserInterfaceStyleDark
    App(
        darkTheme = isDarkTheme,
        dynamicColor = false,
        appModule = appModule
    )
}
