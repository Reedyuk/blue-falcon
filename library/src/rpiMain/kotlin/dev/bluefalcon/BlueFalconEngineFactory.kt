package dev.bluefalcon

actual fun createDefaultBlueFalconEngine(
    log: Logger?,
    context: ApplicationContext,
    autoDiscoverAllServicesAndCharacteristics: Boolean
): BlueFalconEngine = RpiBlueFalconEngine(log, context, autoDiscoverAllServicesAndCharacteristics)
