package dev.bluefalcon.plugins.queue

import dev.bluefalcon.peripheral.BlueFalconPeripheral
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.NotificationMode
import dev.bluefalcon.peripheral.PeripheralPlugin
import dev.bluefalcon.peripheral.PeripheralPluginConfig
import dev.bluefalcon.peripheral.PeripheralPluginFactory
import dev.bluefalcon.peripheral.PeripheralSession
import kotlinx.coroutines.CoroutineScope

enum class QueueOverflowPolicy {
    RejectNewest,
}

sealed interface QueueSendResult {
    data object Sent : QueueSendResult
    data object QueueFull : QueueSendResult
    data object PayloadTooLarge : QueueSendResult
    data object Disconnected : QueueSendResult
    data object Unsupported : QueueSendResult
    data class Failed(val cause: Throwable) : QueueSendResult
}

interface PeripheralQueue {
    suspend fun send(
        session: PeripheralSession,
        characteristic: GattCharacteristicId,
        value: ByteArray,
        mode: NotificationMode = NotificationMode.Notification,
    ): QueueSendResult
}

object QueuePlugin : PeripheralPluginFactory<QueuePlugin.Config, PeripheralQueue> {

    class Config : PeripheralPluginConfig() {
        var maxPendingItemsPerSession: Int = 64
        var maxPendingBytes: Int = 64 * 1024
        var overflowPolicy: QueueOverflowPolicy = QueueOverflowPolicy.RejectNewest
    }

    override fun createConfig() = Config()

    override fun create(config: Config): PeripheralPlugin<PeripheralQueue> =
        InstalledQueuePlugin(config.snapshot())

    private fun Config.snapshot(): QueueConfig {
        require(maxPendingItemsPerSession > 0) {
            "maxPendingItemsPerSession must be positive"
        }
        require(maxPendingBytes > 0) { "maxPendingBytes must be positive" }
        return QueueConfig(
            maxPendingItemsPerSession = maxPendingItemsPerSession,
            maxPendingBytes = maxPendingBytes,
            overflowPolicy = overflowPolicy,
        )
    }
}

private class InstalledQueuePlugin(
    private val config: QueueConfig,
) : PeripheralPlugin<PeripheralQueue>, PeripheralQueue {

    private var installed = false

    override fun install(
        peripheral: BlueFalconPeripheral,
        scope: CoroutineScope,
    ): PeripheralQueue {
        check(!installed) { "QueuePlugin instance is already installed" }
        installed = true
        return this
    }

    override suspend fun send(
        session: PeripheralSession,
        characteristic: GattCharacteristicId,
        value: ByteArray,
        mode: NotificationMode,
    ): QueueSendResult {
        val maximum = session.maximumUpdateValueLength.value
        if (maximum != null && value.size > maximum) {
            return QueueSendResult.PayloadTooLarge
        }
        return QueueSendResult.Unsupported
    }

    override suspend fun close() = Unit
}

private data class QueueConfig(
    val maxPendingItemsPerSession: Int,
    val maxPendingBytes: Int,
    val overflowPolicy: QueueOverflowPolicy,
)
