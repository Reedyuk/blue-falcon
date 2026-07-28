package dev.bluefalcon.engine.macos

import dev.bluefalcon.core.BlueFalconEngine
import dev.bluefalcon.engine.apple.AppleEngine

/**
 * macOS-specific entry point backed by the shared Apple implementation.
 */
class MacosEngine : BlueFalconEngine by AppleEngine()
