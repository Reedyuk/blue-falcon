package dev.bluefalcon.engine.android

import dev.bluefalcon.core.CharacteristicWriteCapability
import dev.bluefalcon.core.CharacteristicWriteKey
import dev.bluefalcon.core.CharacteristicWriteReady
import dev.bluefalcon.core.CharacteristicWriteResult
import dev.bluefalcon.core.CharacteristicWriteType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

internal class AndroidCentralWriteState {
    private val lock = Any()
    private val generations = mutableMapOf<String, Long>()

    private val _capabilities = MutableStateFlow<
        Map<CharacteristicWriteKey, CharacteristicWriteCapability>
    >(emptyMap())
    val capabilities: StateFlow<
        Map<CharacteristicWriteKey, CharacteristicWriteCapability>
    > = _capabilities.asStateFlow()

    private val _writeReady = MutableSharedFlow<CharacteristicWriteReady>(
        extraBufferCapacity = 64,
    )
    val writeReady: SharedFlow<CharacteristicWriteReady> = _writeReady.asSharedFlow()

    fun onConnected(peripheralUuid: String): Long = synchronized(lock) {
        val generation = (generations[peripheralUuid] ?: 0L) + 1L
        generations[peripheralUuid] = generation
        replaceCapabilities(
            peripheralUuid = peripheralUuid,
            maximumLength = DEFAULT_WRITE_PAYLOAD_LENGTH,
            ready = true,
        )
        generation
    }

    fun currentGeneration(peripheralUuid: String): Long? = synchronized(lock) {
        generations[peripheralUuid]
    }

    fun onMtuChanged(
        peripheralUuid: String,
        generation: Long,
        mtu: Int,
        successful: Boolean,
    ) = synchronized(lock) {
        if (!successful || generations[peripheralUuid] != generation) return@synchronized
        replaceCapabilities(
            peripheralUuid = peripheralUuid,
            maximumLength = (mtu - ATT_HEADER_LENGTH).coerceAtLeast(0),
            ready = currentReady(peripheralUuid),
        )
    }

    fun onBusy(
        peripheralUuid: String,
        generation: Long,
    ) = synchronized(lock) {
        if (generations[peripheralUuid] != generation) return@synchronized
        replaceCapabilities(
            peripheralUuid = peripheralUuid,
            maximumLength = currentMaximum(peripheralUuid),
            ready = false,
        )
    }

    fun onReady(
        peripheralUuid: String,
        generation: Long,
    ) {
        val readyKeys = synchronized(lock) {
            if (generations[peripheralUuid] != generation) return
            val keys = CharacteristicWriteType.entries.map { writeType ->
                CharacteristicWriteKey(peripheralUuid, writeType)
            }
            val transitioned = keys.filter { key ->
                _capabilities.value[key]?.ready == false
            }
            replaceCapabilities(
                peripheralUuid = peripheralUuid,
                maximumLength = currentMaximum(peripheralUuid),
                ready = true,
            )
            transitioned
        }
        readyKeys.forEach { key ->
            _writeReady.tryEmit(CharacteristicWriteReady(key))
        }
    }

    fun onDisconnected(
        peripheralUuid: String,
        generation: Long,
    ) = synchronized(lock) {
        if (generations[peripheralUuid] != generation) return@synchronized
        _capabilities.value = _capabilities.value.filterKeys { key ->
            key.peripheralUuid != peripheralUuid
        }
    }

    fun validateWrite(
        peripheralUuid: String,
        generation: Long,
        writeType: CharacteristicWriteType,
        payloadSize: Int,
    ): CharacteristicWriteResult? = synchronized(lock) {
        if (generations[peripheralUuid] != generation) {
            return CharacteristicWriteResult.Disconnected
        }
        val capability = _capabilities.value[
            CharacteristicWriteKey(peripheralUuid, writeType)
        ] ?: return CharacteristicWriteResult.Disconnected
        if (!capability.supported) return CharacteristicWriteResult.Unsupported
        if (!capability.ready) return CharacteristicWriteResult.Backpressured
        val maximumLength = capability.maximumLength
        if (maximumLength != null && payloadSize > maximumLength) {
            return CharacteristicWriteResult.PayloadTooLarge(maximumLength)
        }
        null
    }

    private fun replaceCapabilities(
        peripheralUuid: String,
        maximumLength: Int?,
        ready: Boolean,
    ) {
        val retained = _capabilities.value.filterKeys { key ->
            key.peripheralUuid != peripheralUuid
        }
        val replacements = CharacteristicWriteType.entries.associate { writeType ->
            CharacteristicWriteKey(peripheralUuid, writeType) to
                CharacteristicWriteCapability(
                    maximumLength = maximumLength,
                    ready = ready,
                    supported = true,
                )
        }
        _capabilities.value = retained + replacements
    }

    private fun currentMaximum(peripheralUuid: String): Int? =
        _capabilities.value[
            CharacteristicWriteKey(
                peripheralUuid,
                CharacteristicWriteType.WithoutResponse,
            )
        ]?.maximumLength

    private fun currentReady(peripheralUuid: String): Boolean =
        _capabilities.value[
            CharacteristicWriteKey(
                peripheralUuid,
                CharacteristicWriteType.WithoutResponse,
            )
        ]?.ready ?: false

    companion object {
        private const val DEFAULT_WRITE_PAYLOAD_LENGTH = 20
        private const val ATT_HEADER_LENGTH = 3
    }
}

internal fun CentralGattOperationOutcome.toWriteResult(): CharacteristicWriteResult =
    when (this) {
        is CentralGattOperationOutcome.Success -> CharacteristicWriteResult.Sent
        is CentralGattOperationOutcome.StatusFailure ->
            CharacteristicWriteResult.Failed(
                IllegalStateException("GATT write failed with status $status")
            )
        is CentralGattOperationOutcome.Rejected ->
            CharacteristicWriteResult.Failed(
                cause ?: IllegalStateException("GATT write was rejected by the local stack")
            )
        CentralGattOperationOutcome.TimedOut ->
            CharacteristicWriteResult.Failed(
                IllegalStateException("GATT write callback timed out")
            )
        CentralGattOperationOutcome.Disconnected ->
            CharacteristicWriteResult.Disconnected
    }
