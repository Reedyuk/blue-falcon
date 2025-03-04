package com.example.bluefalconcomposemultiplatform.di

import dev.bluefalcon.BlueFalcon
import dev.bluefalcon.ApplicationContext

expect class AppModule {
    val blueFalcon: BlueFalcon
    val applicationContext: ApplicationContext
}
