package dev.bluefalcon.peripheral.android

import android.content.Context
import dev.bluefalcon.core.Logger
import dev.bluefalcon.peripheral.BlueFalconPeripheral
import dev.bluefalcon.peripheral.internal.DefaultBlueFalconPeripheral
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

fun createBlueFalconPeripheral(
    context: Context,
    logger: Logger? = null,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
): BlueFalconPeripheral = createBlueFalconPeripheral(
    stack = FrameworkAndroidBluetoothStack(context, logger),
    logger = logger,
    coroutineContext = coroutineContext,
)

internal fun createBlueFalconPeripheral(
    stack: AndroidBluetoothStack,
    logger: Logger?,
    coroutineContext: CoroutineContext,
): BlueFalconPeripheral = DefaultBlueFalconPeripheral(
    backend = AndroidPeripheralBackend(stack, logger),
    coroutineContext = coroutineContext,
)
