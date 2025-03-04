package com.example.bluefalconcomposemultiplatform.di

import dev.bluefalcon.ApplicationContext
import dev.bluefalcon.BlueFalcon
import platform.UIKit.UIApplication

actual class AppModule {
    actual val blueFalcon: BlueFalcon
        get() = BlueFalcon(
            log = null,
            context = UIApplication.sharedApplication
        )
    actual val applicationContext: ApplicationContext
        get() = UIApplication.sharedApplication
}