package dev.bluefalcon.peripheral.apple

import dev.bluefalcon.core.Logger
import dev.bluefalcon.peripheral.BlueFalconPeripheral
import dev.bluefalcon.peripheral.internal.DefaultBlueFalconPeripheral
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

fun createBlueFalconPeripheral(
    logger: Logger? = null,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
): BlueFalconPeripheral = createBlueFalconPeripheral(
    stack = FrameworkApplePeripheralStack(logger),
    logger = logger,
    coroutineContext = coroutineContext,
)

internal fun createBlueFalconPeripheral(
    stack: ApplePeripheralStack,
    logger: Logger?,
    coroutineContext: CoroutineContext,
): BlueFalconPeripheral = DefaultBlueFalconPeripheral(
    backend = ApplePeripheralBackend(stack, logger),
    coroutineContext = coroutineContext,
)
