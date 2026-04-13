package dev.bluefalcon.legacy

import android.content.Context

/**
 * Android implementation wraps Context
 */
actual interface ApplicationContext

/**
 * Extension to get Android Context from ApplicationContext
 */
val ApplicationContext.androidContext: Context
    get() = this as Context

/**
 * Create ApplicationContext from Android Context
 */
fun ApplicationContext(context: Context): ApplicationContext = context as ApplicationContext
