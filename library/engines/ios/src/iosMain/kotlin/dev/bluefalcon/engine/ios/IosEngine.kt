package dev.bluefalcon.engine.ios

import dev.bluefalcon.core.BlueFalconEngine
import dev.bluefalcon.engine.apple.AppleEngine

/**
 * iOS-specific entry point backed by the shared Apple implementation.
 */
class IosEngine : BlueFalconEngine by AppleEngine()
