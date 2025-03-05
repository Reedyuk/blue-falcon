package com.example.bluefalconcomposemultiplatform.di

import dev.bluefalcon.ApplicationContext
import platform.UIKit.UIApplication

actual class AppModule {
    actual val applicationContext: ApplicationContext
        get() = UIApplication.sharedApplication
}