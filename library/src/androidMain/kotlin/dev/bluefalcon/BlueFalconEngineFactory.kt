package dev.bluefalcon

actual fun createDefaultBlueFalconEngine(
    log: Logger?,
    context: ApplicationContext,
    autoDiscoverAllServicesAndCharacteristics: Boolean
): BlueFalconEngine = AndroidBlueFalconEngine(log, context, autoDiscoverAllServicesAndCharacteristics)
