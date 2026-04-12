package dev.bluefalcon.legacy

/**
 * JVM doesn't require an application context
 */
actual interface ApplicationContext

/**
 * Default ApplicationContext instance for JVM
 */
object JvmApplicationContext : ApplicationContext
