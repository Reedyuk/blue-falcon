package dev.bluefalcon.legacy

/**
 * iOS doesn't require an application context
 */
actual interface ApplicationContext

/**
 * Default ApplicationContext instance for iOS
 */
object IosApplicationContext : ApplicationContext
