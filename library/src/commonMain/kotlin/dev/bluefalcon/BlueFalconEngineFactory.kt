package dev.bluefalcon

/**
 * Creates a default BlueFalconEngine for the current platform.
 * 
 * This function provides the default engine implementation for each platform:
 * - Android: AndroidBlueFalconEngine
 * - iOS/Native: NativeBlueFalconEngine
 * - JavaScript: JsBlueFalconEngine
 * - Raspberry Pi: RpiBlueFalconEngine
 * 
 * @param log Optional logger for debugging
 * @param context Platform-specific application context
 * @param autoDiscoverAllServicesAndCharacteristics Whether to automatically discover all services and characteristics after connection
 * @return Platform-specific BlueFalconEngine implementation
 */
expect fun createDefaultBlueFalconEngine(
    log: Logger? = PrintLnLogger,
    context: ApplicationContext,
    autoDiscoverAllServicesAndCharacteristics: Boolean = true
): BlueFalconEngine
