package dev.bluefalcon

actual fun createDefaultBlueFalconEngine(
    log: Logger?,
    context: ApplicationContext,
    autoDiscoverAllServicesAndCharacteristics: Boolean
): BlueFalconEngine = JsBlueFalconEngine(log, context, autoDiscoverAllServicesAndCharacteristics)
