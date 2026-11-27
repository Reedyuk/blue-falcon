package dev.bluefalcon.engine.ios

import dev.bluefalcon.core.BlueFalconEngine
import dev.bluefalcon.core.Logger
import dev.bluefalcon.engine.apple.AppleEngine
import dev.bluefalcon.engine.apple.AppleEngineConfiguration
import dev.bluefalcon.engine.apple.AppleRestoredScan
import kotlinx.coroutines.flow.StateFlow

/**
 * iOS-specific entry point backed by the shared Apple implementation.
 */
class IosEngine private constructor(
    private val apple: AppleEngine,
) : BlueFalconEngine by apple {

    constructor() : this(AppleEngine())

    /**
     * Creates the engine with [configuration], for example with a scan-restoration identifier.
     * See [AppleEngineConfiguration] for what iOS restores and what it does not.
     */
    constructor(configuration: AppleEngineConfiguration, logger: Logger? = null) :
        this(AppleEngine(configuration, logger))

    /** The scan iOS restored after a background relaunch, or `null`. See [AppleEngine.restoredScan]. */
    val restoredScan: StateFlow<AppleRestoredScan?> get() = apple.restoredScan
}
