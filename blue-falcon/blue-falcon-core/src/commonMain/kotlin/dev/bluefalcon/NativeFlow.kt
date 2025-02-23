package dev.bluefalcon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.plus

internal fun <T> Flow<T>.toNativeType(scope: CoroutineScope): NativeFlow<T> = NativeFlow(this, scope)

class NativeFlow<T>(private val origin: Flow<T>, private val scope: CoroutineScope) : Flow<T> by origin {
    fun collect(block: (T) -> Unit) = onEach { block(it) }.launchIn(scope)
    fun collectOnMain(block: (T) -> Unit) = onEach { block(it) }.launchIn(scope + Dispatchers.Main)
}
