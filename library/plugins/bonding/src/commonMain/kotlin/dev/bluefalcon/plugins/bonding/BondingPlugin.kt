package dev.bluefalcon.plugins.bonding

import dev.bluefalcon.core.*
import dev.bluefalcon.core.plugin.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Observable bond state for a peripheral, combining the platform bond state with capability info.
 */
data class BondState(
    val peripheralUuid: String,
    val state: BlueFalconBondState,
    val capability: BondCapability,
)

/**
 * Result of a bond or unbond request.
 */
sealed class BondResult {
    /** Bonding succeeded. */
    data class Bonded(val peripheralUuid: String) : BondResult()
    /** Unbonding succeeded. */
    data class Unbonded(val peripheralUuid: String) : BondResult()
    /** Bonding failed with an error. */
    data class Failed(val cause: Throwable) : BondResult()
    /** The platform does not support programmatic bonding. */
    data object Unsupported : BondResult()
    /** The bond request timed out without a definitive result. */
    data object TimedOut : BondResult()
}

/**
 * Plugin that provides observable, typed bonding/pairing workflow for BLE peripherals.
 *
 * Where the platform can request and observe bond state changes (Android), the plugin
 * awaits the result reactively. Where the platform cannot (Apple, Windows, JS), the
 * plugin returns [BondResult.Unsupported] immediately rather than silently no-op-ing.
 *
 * Usage:
 * ```
 * val bonding = BondingPlugin.create {
 *     bondTimeout = 30.seconds
 * }
 * val falcon = BlueFalcon {
 *     engine = myEngine
 *     install(bonding)
 * }
 * bonding.bind(falcon)
 * val result = bonding.requestBond(peripheral)
 * ```
 */
class BondingPlugin(private val config: Config) : BlueFalconPlugin {

    class Config : PluginConfig() {
        /** Maximum time to wait for a bond state resolution on supported platforms. */
        var bondTimeout: Duration = 30.seconds
    }

    private var client: BlueFalcon? = null

    private val _bondStates = MutableStateFlow<Map<String, BondState>>(emptyMap())

    /** Current bond state for every peripheral this plugin has observed, keyed by uuid. */
    val bondStates: StateFlow<Map<String, BondState>> = _bondStates.asStateFlow()

    override fun install(client: BlueFalconClient, config: PluginConfig) {
        // Plugin registration only; call bind(BlueFalcon) to wire bond state collection.
    }

    /**
     * Binds this plugin to a [BlueFalcon] instance, wiring up bond state observation.
     * Must be called after construction to enable [requestBond]/[requestUnbond].
     */
    fun bind(blueFalcon: BlueFalcon) {
        client = blueFalcon
        blueFalcon.engine.scope.launch {
            blueFalcon.bondStateUpdates.collect { update ->
                val capability = blueFalcon.centralCapabilities.bondCapability
                _bondStates.update {
                    it + (update.peripheralUuid to BondState(
                        peripheralUuid = update.peripheralUuid,
                        state = update.state,
                        capability = capability,
                    ))
                }
            }
        }
    }

    private fun requireClient(): BlueFalcon =
        client ?: throw IllegalStateException("BondingPlugin.bind(BlueFalcon) must be called before use")

    /**
     * Requests a bond and suspends until it resolves, times out, or the platform can't support it.
     */
    suspend fun requestBond(peripheral: BluetoothPeripheral): BondResult {
        val bf = requireClient()
        val capability = bf.centralCapabilities.bondCapability
        if (capability != BondCapability.Supported) {
            return BondResult.Unsupported
        }

        return try {
            bf.createBond(peripheral)
            awaitBondState(bf, peripheral.uuid, BlueFalconBondState.Bonded)
        } catch (e: Throwable) {
            BondResult.Failed(e)
        }
    }

    /**
     * Requests removal of a bond. On platforms where this isn't programmatically possible,
     * returns [BondResult.Unsupported] immediately.
     */
    suspend fun requestUnbond(peripheral: BluetoothPeripheral): BondResult {
        val bf = requireClient()
        val capability = bf.centralCapabilities.bondCapability
        if (capability != BondCapability.Supported) {
            return BondResult.Unsupported
        }

        return try {
            bf.removeBond(peripheral)
            awaitUnbondState(bf, peripheral.uuid)
        } catch (e: Throwable) {
            BondResult.Failed(e)
        }
    }

    private suspend fun awaitBondState(
        bf: BlueFalcon,
        peripheralUuid: String,
        targetState: BlueFalconBondState,
    ): BondResult {
        val result = withTimeoutOrNull(config.bondTimeout) {
            bf.bondStateUpdates.first { update ->
                update.peripheralUuid == peripheralUuid &&
                    (update.state == targetState || update.state == BlueFalconBondState.None)
            }
        } ?: return BondResult.TimedOut

        return if (result.state == targetState) {
            BondResult.Bonded(peripheralUuid)
        } else {
            BondResult.Failed(IllegalStateException("Bond state changed to ${result.state} instead of $targetState"))
        }
    }

    private suspend fun awaitUnbondState(
        bf: BlueFalcon,
        peripheralUuid: String,
    ): BondResult {
        val completed = withTimeoutOrNull(config.bondTimeout) {
            bf.bondStateUpdates.first { update ->
                update.peripheralUuid == peripheralUuid && update.state == BlueFalconBondState.None
            }
        }

        return if (completed != null) BondResult.Unbonded(peripheralUuid) else BondResult.TimedOut
    }

    companion object {
        fun create(configure: Config.() -> Unit = {}): BondingPlugin {
            return BondingPlugin(Config().apply(configure))
        }
    }
}

/**
 * DSL function to install bonding plugin.
 */
fun installBonding(configure: BondingPlugin.Config.() -> Unit): BondingPlugin {
    return BondingPlugin.create(configure)
}
