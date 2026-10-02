package dev.bluefalcon.engine.apple

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred

internal fun snapshotCallbackPayload(value: ByteArray?): ByteArray? = value?.copyOf()

internal class AppleNativeConnectionToken<T : Any>(val peripheralUuid: String, val owner: T) {
    val terminated = CompletableDeferred<Unit>()
}

internal class AppleNativeConnectionOwnership<T : Any> {
    private val owners = MutableStateFlow<Map<String, AppleNativeConnectionToken<T>>>(emptyMap())

    fun connected(peripheralUuid: String, owner: T): AppleNativeConnectionToken<T> {
        val token = AppleNativeConnectionToken(peripheralUuid, owner)
        while (true) {
            val current = owners.value
            if (owners.compareAndSet(current, current + (peripheralUuid to token))) {
                return token
            }
        }
    }

    fun disconnected(peripheralUuid: String, owner: T): Boolean {
        val token = capture(peripheralUuid, owner) ?: return false
        return disconnected(token)
    }

    fun disconnected(token: AppleNativeConnectionToken<T>): Boolean {
        while (true) {
            val current = owners.value
            if (current[token.peripheralUuid] !== token) return false
            if (owners.compareAndSet(current, current - token.peripheralUuid)) return true
        }
    }

    fun isActive(peripheralUuid: String, owner: T): Boolean =
        capture(peripheralUuid, owner) != null

    fun isActive(token: AppleNativeConnectionToken<T>): Boolean =
        owners.value[token.peripheralUuid] === token

    fun current(peripheralUuid: String): AppleNativeConnectionToken<T>? =
        owners.value[peripheralUuid]

    fun capture(peripheralUuid: String, owner: T): AppleNativeConnectionToken<T>? =
        current(peripheralUuid)?.takeIf { it.owner === owner }
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
