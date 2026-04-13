package dev.bluefalcon.legacy

/**
 * macOS doesn't require an application context
 */
actual interface ApplicationContext

/**
 * Default ApplicationContext instance for macOS
 */
object MacosApplicationContext : ApplicationContext
