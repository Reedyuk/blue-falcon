package com.example.bluefalconcomposemultiplatform.di

import android.content.Context
import dev.bluefalcon.ApplicationContext

actual class AppModule(
    private val context: Context
) {
    actual val applicationContext: ApplicationContext
        get() = context as ApplicationContext
}