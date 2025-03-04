package com.example.bluefalconcomposemultiplatform.di

import android.content.Context
import dev.bluefalcon.ApplicationContext
import dev.bluefalcon.BlueFalcon

actual class AppModule(
    private val context: Context
) {
    actual val blueFalcon: BlueFalcon
        get() = BlueFalcon(
            log = null,
            context as ApplicationContext,
        )
    actual val applicationContext: ApplicationContext
        get() = context as ApplicationContext
}