package dev.bluefalcon.legacy

/**
 * JS doesn't require an application context
 */
actual interface ApplicationContext

/**
 * Default ApplicationContext instance for JS
 */
object JsApplicationContext : ApplicationContext
