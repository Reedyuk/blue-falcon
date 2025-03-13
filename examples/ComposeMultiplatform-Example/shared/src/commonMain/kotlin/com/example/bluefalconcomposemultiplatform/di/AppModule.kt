package com.example.bluefalconcomposemultiplatform.di

import dev.bluefalcon.ApplicationContext

expect class AppModule {
    val applicationContext: ApplicationContext
}
