package dev.bluefalcon.engine.apple

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal fun snapshotCallbackPayload(value: ByteArray?): ByteArray? = value?.copyOf()

internal class AppleNativeConnectionOwnership<T : Any> {
    private val owners = MutableStateFlow<Map<String, T>>(emptyMap())

    fun connected(peripheralUuid: String, owner: T) {
        while (true) {
            val current = owners.value
            if (owners.compareAndSet(current, current + (peripheralUuid to owner))) {
                return
            }
        }
    }

    fun disconnected(peripheralUuid: String, owner: T): Boolean {
        while (true) {
            val current = owners.value
            if (current[peripheralUuid] !== owner) return false
            if (owners.compareAndSet(current, current - peripheralUuid)) return true
        }
    }

    fun isActive(peripheralUuid: String, owner: T): Boolean =
        owners.value[peripheralUuid] === owner
}

internal class AppleCentralCallbackDispatcher(
    scope: CoroutineScope,
) {
    private val callbacks = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (callback in callbacks) {
                try {
                    callback()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // One malformed platform event must not stop delivery of later BLE callbacks.
                }
            }
        }
    }

    fun dispatch(callback: suspend () -> Unit): Boolean =
        callbacks.trySend(callback).isSuccess
}
