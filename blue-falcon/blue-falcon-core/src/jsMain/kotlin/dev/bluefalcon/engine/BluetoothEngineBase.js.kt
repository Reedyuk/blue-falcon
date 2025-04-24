package dev.bluefalcon.engine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual fun ioDispatcher(): CoroutineDispatcher = Dispatchers.Default