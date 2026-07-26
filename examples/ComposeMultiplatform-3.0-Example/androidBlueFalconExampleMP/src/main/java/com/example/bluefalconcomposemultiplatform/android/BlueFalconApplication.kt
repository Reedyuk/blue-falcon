package com.example.bluefalconcomposemultiplatform.android

import android.app.Application
import com.example.bluefalconcomposemultiplatform.di.AppModule

class BlueFalconApplication : Application() {
    lateinit var appModule: AppModule
        private set

    override fun onCreate() {
        super.onCreate()
        appModule = AppModule(applicationContext)
    }
}
