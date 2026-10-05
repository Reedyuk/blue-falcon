package dev.bluefalcon.engine.apple

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred

internal fun snapshotCallbackPayload(value: ByteArray?): ByteArray? = value?.copyOf()

internal class AppleNativeConnectionToken<T : Any>(val peripheralUuid: String, val owner: T, val origin: Any? = null) {
    val terminated = CompletableDeferred<Unit>()
    internal val acceptingCallbacks = MutableStateFlow(true)
}

/** A delegate keeps this binding even if the same native object is reconnected. */
internal class AppleNativeConnectionCallbacks<T : Any>(
    private val token: AppleNativeConnectionToken<T>,
    private val ownership: AppleNativeConnectionOwnership<T>,
) {
    fun forward(callback: (AppleNativeConnectionToken<T>) -> Unit) {
        if (ownership.isActive(token)) callback(token)
    }
}

internal class AppleNativeConnectionOwnership<T : Any> {
    private val owners = MutableStateFlow<Map<String, AppleNativeConnectionToken<T>>>(emptyMap())

    fun connected(peripheralUuid: String, owner: T, origin: Any? = null): AppleNativeConnectionToken<T> {
        val token = AppleNativeConnectionToken(peripheralUuid, owner, origin)
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
        owners.value[token.peripheralUuid] === token && token.acceptingCallbacks.value

    fun beginRetirement(token: AppleNativeConnectionToken<T>): Boolean =
        owners.value[token.peripheralUuid] === token && token.acceptingCallbacks.compareAndSet(true, false)

    fun current(peripheralUuid: String): AppleNativeConnectionToken<T>? =
        owners.value[peripheralUuid]

    fun capture(peripheralUuid: String, owner: T): AppleNativeConnectionToken<T>? =
        current(peripheralUuid)?.takeIf { it.owner === owner && it.acceptingCallbacks.value }
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
