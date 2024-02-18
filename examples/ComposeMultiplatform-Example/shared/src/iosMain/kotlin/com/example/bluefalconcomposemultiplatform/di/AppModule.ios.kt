package com.example.bluefalconcomposemultiplatform.di

import dev.bluefalcon.ApplicationContext
import dev.bluefalcon.BlueFalcon

actual class AppModule {
    actual val blueFalcon: BlueFalcon
        get() = BlueFalcon(ApplicationContext(), null)
}