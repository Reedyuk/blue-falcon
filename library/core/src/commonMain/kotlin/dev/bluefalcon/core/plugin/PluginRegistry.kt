package dev.bluefalcon.core.plugin

import dev.bluefalcon.core.CharacteristicWriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.TimeSource

/**
 * Registry for managing installed plugins.
 *
 * @param client The owning client, passed to each plugin's [BlueFalconPlugin.install] callback.
 *   May be `null` only for legacy/test call sites that construct a bare [PluginRegistry]; in that
 *   case [install] will skip invoking [BlueFalconPlugin.install] since there is no client to pass.
 */
class PluginRegistry(private val client: BlueFalconClient? = null) {
    @PublishedApi
    internal val plugins = mutableListOf<BlueFalconPlugin>()
    
    /**
     * Install a plugin.
     *
     * Invokes [BlueFalconPlugin.install] with the owning client so plugins that need to subscribe
     * to client state/flows (e.g. RSSI updates, peripheral sets) are wired up immediately,
     * regardless of whether the plugin was installed via the `BlueFalcon { install(...) }` DSL or
     * by calling `blueFalcon.plugins.install(...)` directly.
     */
    fun <T : BlueFalconPlugin> install(plugin: T, configure: PluginConfig.() -> Unit = {}) {
        val config = PluginConfig().apply(configure)
        client?.let { plugin.install(it, config) }
        plugins.add(plugin)
    }
    
    /**
     * Get all installed plugins
     */
    fun getAll(): List<BlueFalconPlugin> = plugins.toList()
    
    /**
     * Find a plugin by type
     */
    inline fun <reified T : BlueFalconPlugin> find(): T? {
        return plugins.firstOrNull { it is T } as? T
    }
    
    /**
     * Execute scan interceptors
     */
    suspend fun interceptScan(call: ScanCall, proceed: suspend (ScanCall) -> Unit) {
        var currentCall = call
        for (plugin in plugins) {
            currentCall = plugin.onBeforeScan(currentCall)
        }
        val start = TimeSource.Monotonic.markNow()
        try {
            proceed(currentCall)
        } catch (t: Throwable) {
            dispatchOperationCompleted(
                operation = BlueFalconOperationKind.SCAN,
                peripheralUuid = null,
                success = false,
                start = start,
                attempts = 1,
                byteCount = null
            )
            throw t
        }
        for (plugin in plugins.reversed()) {
            plugin.onAfterScan(currentCall)
        }
        dispatchOperationCompleted(
            operation = BlueFalconOperationKind.SCAN,
            peripheralUuid = null,
            success = true,
            start = start,
            attempts = 1,
            byteCount = null
        )
    }
    
    /**
     * Plugins that can decide whether a failed operation should be retried.
     */
    private val retryCapablePlugins: List<RetryCapable>
        get() = plugins.filterIsInstance<RetryCapable>()

    /**
     * The outcome of [retryUntilSatisfied]: the last observed result plus the total number of
     * attempts made (1 if no retry occurred).
     */
    private data class RetryOutcome<R>(val result: R, val attempts: Int)

    /**
     * Re-invokes [proceed] while any installed [RetryCapable] plugin requests a retry after
     * [result] represents a failure. Returns the last result observed plus how many attempts
     * were made in total.
     */
    private suspend fun <C, R> retryUntilSatisfied(
        operation: RetryableOperation,
        call: C,
        initialResult: R,
        isFailure: (R) -> Boolean,
        failureCause: (R) -> Throwable?,
        proceed: suspend (C) -> R
    ): RetryOutcome<R> {
        val retryPlugins = retryCapablePlugins
        if (retryPlugins.isEmpty()) {
            return RetryOutcome(initialResult, attempts = 1)
        }

        var result = initialResult
        var attempt = 0
        while (isFailure(result)) {
            val error = failureCause(result) ?: break
            val delayDuration = retryPlugins.firstNotNullOfOrNull {
                it.retryDelay(operation, attempt, error)
            } ?: break
            delay(delayDuration)
            attempt++
            result = proceed(call)
        }
        return RetryOutcome(result, attempts = attempt + 1)
    }

    /**
     * Notifies every installed plugin's [BlueFalconPlugin.onOperationCompleted] hook, in the same
     * reversed (last-installed-first) order used for `onAfterX` hooks.
     */
    private suspend fun dispatchOperationCompleted(
        operation: BlueFalconOperationKind,
        peripheralUuid: String?,
        success: Boolean,
        start: TimeSource.Monotonic.ValueTimeMark,
        attempts: Int,
        byteCount: Int?
    ) {
        val telemetry = OperationTelemetry(
            operation = operation,
            peripheralUuid = peripheralUuid,
            success = success,
            durationMillis = start.elapsedNow().inWholeMilliseconds,
            attempts = attempts,
            byteCount = byteCount
        )
        for (plugin in plugins.reversed()) {
            plugin.onOperationCompleted(telemetry)
        }
    }

    /**
     * Execute connect interceptors
     */
    suspend fun interceptConnect(call: ConnectCall, proceed: suspend (ConnectCall) -> Result<Unit>): Result<Unit> {
        var currentCall = call
        for (plugin in plugins) {
            currentCall = plugin.onBeforeConnect(currentCall)
        }
        val start = TimeSource.Monotonic.markNow()
        val initialResult = proceed(currentCall)
        val outcome = retryUntilSatisfied(
            operation = RetryableOperation.CONNECT,
            call = currentCall,
            initialResult = initialResult,
            isFailure = { it.isFailure },
            failureCause = { it.exceptionOrNull() },
            proceed = proceed
        )
        val result = outcome.result
        for (plugin in plugins.reversed()) {
            plugin.onAfterConnect(currentCall, result)
        }
        dispatchOperationCompleted(
            operation = BlueFalconOperationKind.CONNECT,
            peripheralUuid = currentCall.peripheral.uuid,
            success = result.isSuccess,
            start = start,
            attempts = outcome.attempts,
            byteCount = null
        )
        return result
    }
    
    /**
     * Execute read interceptors
     */
    suspend fun interceptRead(call: ReadCall, proceed: suspend (ReadCall) -> Result<ByteArray?>): Result<ByteArray?> {
        var currentCall = call
        for (plugin in plugins) {
            currentCall = plugin.onBeforeRead(currentCall)
        }
        val start = TimeSource.Monotonic.markNow()
        val initialResult = proceed(currentCall)
        val outcome = retryUntilSatisfied(
            operation = RetryableOperation.READ,
            call = currentCall,
            initialResult = initialResult,
            isFailure = { it.isFailure },
            failureCause = { it.exceptionOrNull() },
            proceed = proceed
        )
        val result = outcome.result
        for (plugin in plugins.reversed()) {
            plugin.onAfterRead(currentCall, result)
        }
        dispatchOperationCompleted(
            operation = BlueFalconOperationKind.READ,
            peripheralUuid = currentCall.peripheral.uuid,
            success = result.isSuccess,
            start = start,
            attempts = outcome.attempts,
            byteCount = result.getOrNull()?.size
        )
        return result
    }
    
    /**
     * Execute write interceptors
     */
    suspend fun interceptWrite(call: WriteCall, proceed: suspend (WriteCall) -> Result<Unit>): Result<Unit> {
        var currentCall = call
        for (plugin in plugins) {
            currentCall = plugin.onBeforeWrite(currentCall)
        }
        val start = TimeSource.Monotonic.markNow()
        val initialResult = proceed(currentCall)
        val outcome = retryUntilSatisfied(
            operation = RetryableOperation.WRITE,
            call = currentCall,
            initialResult = initialResult,
            isFailure = { it.isFailure },
            failureCause = { it.exceptionOrNull() },
            proceed = proceed
        )
        val result = outcome.result
        for (plugin in plugins.reversed()) {
            plugin.onAfterWrite(currentCall, result)
        }
        dispatchOperationCompleted(
            operation = BlueFalconOperationKind.WRITE,
            peripheralUuid = currentCall.peripheral.uuid,
            success = result.isSuccess,
            start = start,
            attempts = outcome.attempts,
            byteCount = if (result.isSuccess) currentCall.value.size else null
        )
        return result
    }

    suspend fun interceptCentralWrite(
        call: CentralWriteCall,
        proceed: suspend (CentralWriteCall) -> CharacteristicWriteResult,
    ): CharacteristicWriteResult {
        var currentCall = call
        for (plugin in plugins) {
            currentCall = plugin.onBeforeCentralWrite(currentCall)
        }
        val result = proceed(currentCall)
        for (plugin in plugins.reversed()) {
            try {
                plugin.onAfterCentralWrite(currentCall, result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Observer failures must not replace the already completed platform write outcome.
            }
        }
        return result
    }
    
    /**
     * Execute disconnect interceptors
     */
    suspend fun interceptDisconnect(call: DisconnectCall, proceed: suspend (DisconnectCall) -> Result<Unit>): Result<Unit> {
        var currentCall = call
        for (plugin in plugins) {
            currentCall = plugin.onBeforeDisconnect(currentCall)
        }
        val start = TimeSource.Monotonic.markNow()
        val result = proceed(currentCall)
        for (plugin in plugins.reversed()) {
            plugin.onAfterDisconnect(currentCall, result)
        }
        dispatchOperationCompleted(
            operation = BlueFalconOperationKind.DISCONNECT,
            peripheralUuid = currentCall.peripheral.uuid,
            success = result.isSuccess,
            start = start,
            attempts = 1,
            byteCount = null
        )
        return result
    }

    /**
     * Dispatch incoming characteristic notifications to plugins.
     */
    suspend fun dispatchNotification(call: NotificationCall) {
        for (plugin in plugins) {
            plugin.onNotificationReceived(call.peripheral, call.characteristic, call.value)
        }
    }
}
