# ADR 0002: Adopt Plugin-Based Engine Architecture

**Status:** Proposed

**Date:** 2026-04-10

**Deciders:** Blue Falcon maintainers, community contributors

**Technical Story:** As Blue Falcon grows with more platforms and custom use cases, the monolithic architecture becomes increasingly difficult to maintain and extend. Users want to add custom functionality and platform support without forking the entire library.

## Context

Blue Falcon currently uses a monolithic Kotlin Multiplatform architecture where platform implementations are developed and published together under a single library coordinate (`dev.bluefalcon:blue-falcon:2.x.x`). This design has served the project well for initial development but faces several challenges:

### Current Architecture Limitations

1. **Shared versioning and release cycle**: Platform implementations are versioned and released together, even though consumers resolve only their platform-specific variant
2. **Difficult third-party contributions**: Community members cannot easily add new platform support without modifying core library
3. **No extensibility mechanism**: No way to add custom BLE functionality (e.g., device-specific protocols, additional abstractions)
4. **Tight coupling**: Core API and platform source set changes must evolve together within the same module and release process
5. **Monolithic releases**: A bug fix in one platform requires releasing all platforms
6. **Testing overhead**: Changes to core require testing all platform implementations

### Inspiration from Ktor

Ktor's HTTP client successfully uses an engine-based architecture:
- `ktor-client-core` - Common API and abstractions
- `ktor-client-android`, `ktor-client-ios`, `ktor-client-js` - Platform engines as separate dependencies
- Plugin system for cross-cutting concerns (logging, serialization, auth)

This architecture enables:
- Users choose only the engines they need
- Third parties can create custom engines
- Plugins extend functionality orthogonally
- Independent release cycles per engine

### Blue Falcon's Need

We need similar benefits:
- **Modularity**: Separate core API from platform implementations
- **Extensibility**: Allow community-contributed engines and plugins
- **Flexibility**: Users can create custom engines for specialized hardware
- **Backward compatibility**: Existing applications must continue to work

## Decision

We will refactor Blue Falcon into a **plugin-based engine architecture** with three layers:

### 1. Blue Falcon Core (`blue-falcon-core`)

**Purpose**: Common API, abstractions, and engine management

**Responsibilities**:
- Define core interfaces (`BlueFalconEngine`, `BluetoothPeripheral`, `BluetoothService`, etc.)
- Provide engine selection and configuration DSL
- Implement plugin installation and lifecycle management
- Manage common functionality (logging, StateFlow wrappers, error handling)
- No platform-specific code

**API Design**:
```kotlin
// Core API - platform-agnostic
interface BlueFalconEngine {
    val scope: CoroutineScope
    val peripherals: StateFlow<Set<BluetoothPeripheral>>
    val managerState: StateFlow<BluetoothManagerState>
    
    suspend fun scan(filters: List<ServiceFilter> = emptyList())
    suspend fun stopScanning()
    suspend fun connect(peripheral: BluetoothPeripheral, autoConnect: Boolean = false)
    suspend fun disconnect(peripheral: BluetoothPeripheral)
    // ... other BLE operations
}

// Core client with engine + plugins
class BlueFalcon(
    val engine: BlueFalconEngine
) {
    val plugins: PluginRegistry = PluginRegistry()
    
    // Delegates to engine
    suspend fun scan(filters: List<ServiceFilter> = emptyList()) = engine.scan(filters)
    // ...
}

// DSL for configuration
fun BlueFalcon(
    block: BlueFalconConfig.() -> Unit
): BlueFalcon {
    val config = BlueFalconConfig().apply(block)
    return BlueFalcon(config.engine)
}
```

### 2. Platform Engines (Separate Artifacts)

Each platform becomes an independent module published as separate artifacts:

- `blue-falcon-engine-android` - Android BLE implementation
- `blue-falcon-engine-ios` - iOS CoreBluetooth implementation  
- `blue-falcon-engine-macos` - macOS CoreBluetooth implementation
- `blue-falcon-engine-js` - JavaScript Web Bluetooth implementation
- `blue-falcon-engine-windows` - Windows WinRT implementation
- `blue-falcon-engine-rpi` - Raspberry Pi implementation
- Community can add: `blue-falcon-engine-linux`, `blue-falcon-engine-custom`, etc.

**Monorepo Structure**: All modules remain under `library/` directory with dedicated folders for engines and plugins:
```
library/
├── settings.gradle.kts            # Include all modules
├── core/                          # blue-falcon-core
│   ├── build.gradle.kts          # Publishes: dev.bluefalcon:blue-falcon-core
│   └── src/
│       └── commonMain/
├── engines/
│   ├── android/                   # blue-falcon-engine-android
│   │   ├── build.gradle.kts      # Publishes: dev.bluefalcon:blue-falcon-engine-android
│   │   └── src/
│   │       └── androidMain/
│   ├── ios/                       # blue-falcon-engine-ios
│   │   ├── build.gradle.kts      # Publishes: dev.bluefalcon:blue-falcon-engine-ios
│   │   └── src/
│   │       ├── iosMain/
│   │       └── nativeMain/
│   ├── macos/                     # blue-falcon-engine-macos
│   │   ├── build.gradle.kts
│   │   └── src/macosMain/
│   ├── js/                        # blue-falcon-engine-js
│   │   ├── build.gradle.kts
│   │   └── src/jsMain/
│   ├── windows/                   # blue-falcon-engine-windows
│   │   ├── build.gradle.kts
│   │   └── src/
│   │       ├── windowsMain/
│   │       └── cpp/               # Native Windows code
│   └── rpi/                       # blue-falcon-engine-rpi
│       ├── build.gradle.kts
│       └── src/rpiMain/
├── plugins/
│   ├── logging/                   # blue-falcon-plugin-logging
│   │   ├── build.gradle.kts      # Publishes: dev.bluefalcon:blue-falcon-plugin-logging
│   │   └── src/commonMain/
│   ├── retry/                     # blue-falcon-plugin-retry
│   │   ├── build.gradle.kts
│   │   └── src/commonMain/
│   └── caching/                   # blue-falcon-plugin-caching
│       ├── build.gradle.kts
│       └── src/commonMain/
└── legacy/                        # blue-falcon-legacy (compatibility layer)
    ├── build.gradle.kts
    └── src/
```

Each subfolder is a Gradle module with its own `build.gradle.kts` and can be:
- Developed together in the monorepo
- Tested together with shared test utilities
- Published independently to Maven Central
- Versioned independently (or synchronized)

**Usage Example**:
```kotlin
// In build.gradle.kts
dependencies {
    implementation("dev.bluefalcon:blue-falcon-core:3.0.0")
    implementation("dev.bluefalcon:blue-falcon-engine-android:3.0.0") // Only Android
}

// In code
val blueFalcon = BlueFalcon {
    engine = AndroidEngine(context)
    install(LoggingPlugin) {
        level = LogLevel.DEBUG
    }
}
```

### 3. Plugin System

Plugins provide cross-cutting functionality and are published as separate artifacts from `library/plugins/`:

**Core Plugins** (maintained in monorepo):
- `blue-falcon-plugin-logging` (`library/plugins/logging/`) - Structured logging
- `blue-falcon-plugin-retry` (`library/plugins/retry/`) - Automatic retry on transient failures
- `blue-falcon-plugin-caching` (`library/plugins/caching/`) - Cache GATT service/characteristic metadata
- `blue-falcon-plugin-metrics` (`library/plugins/metrics/`) - Performance and usage metrics

**Community Plugins** (examples, external repositories):
- `blue-falcon-plugin-device-profiles` - High-level abstractions for common device types (heart rate monitors, thermometers, glucose meters)
- `blue-falcon-plugin-security` - Additional encryption/authentication layers
- `blue-falcon-plugin-simulator` - Mock BLE devices for testing
- `blue-falcon-plugin-nordic-ota` - Over-the-air firmware updates for Nordic chipsets (nRF52, nRF53, etc.)
- `blue-falcon-plugin-texas-instruments-ota` - OTA updates for Texas Instruments BLE devices
- `blue-falcon-plugin-analytics` - Usage analytics and telemetry

**Plugin API**:
```kotlin
interface BlueFalconPlugin {
    fun install(client: BlueFalcon, config: PluginConfig)
    suspend fun onScan(call: ScanCall, next: suspend (ScanCall) -> Unit)
    suspend fun onConnect(call: ConnectCall, next: suspend (ConnectCall) -> Unit)
    suspend fun onRead(call: ReadCall, next: suspend (ReadCall) -> Unit)
    suspend fun onWrite(call: WriteCall, next: suspend (WriteCall) -> Unit)
    // ... interceptors for all operations
}

// Example: Nordic OTA Plugin
class NordicOTAPlugin(
    private val config: NordicOTAConfig
) : BlueFalconPlugin {
    
    suspend fun updateFirmware(
        peripheral: BluetoothPeripheral,
        firmwareData: ByteArray,
        onProgress: (Int) -> Unit
    ) {
        // Nordic DFU protocol implementation
        // - Enter bootloader mode
        // - Send firmware packets
        // - Verify and reboot
    }
    
    override suspend fun onConnect(call: ConnectCall, next: suspend (ConnectCall) -> Unit) {
        next(call)
        // Detect Nordic bootloader service UUID if present
        if (call.peripheral.hasService(NORDIC_DFU_SERVICE_UUID)) {
            // Mark peripheral as OTA-capable
        }
    }
}

// Usage
val blueFalcon = BlueFalcon {
    engine = AndroidEngine(context)
    install(NordicOTAPlugin) {
        enableAutoBootloaderDetection = true
        packetSize = 20
    }
}
```

### 4. Backward Compatibility Layer

**Legacy API** (`blue-falcon-legacy` or within `blue-falcon-core`):

Maintain the existing `expect/actual BlueFalcon` API but mark as deprecated:

```kotlin
@Deprecated(
    message = "Use the new engine-based API. See migration guide.",
    replaceWith = ReplaceWith("BlueFalcon { engine = AndroidEngine(context) }"),
    level = DeprecationLevel.WARNING
)
expect class BlueFalcon(
    log: Logger?,
    context: ApplicationContext,
    autoDiscoverAllServicesAndCharacteristics: Boolean = true
)
```

The deprecated API internally delegates to the new engine system, ensuring existing code continues to work.

## Consequences

### Positive

- **Modularity**: Core and engines can evolve independently
- **Smaller dependencies**: Users include only needed engines (~50-70% size reduction per platform)
- **Extensibility**: Third parties can create engines without forking
- **Plugin ecosystem**: Community can build reusable functionality
- **Independent releases**: Bug fix in one engine doesn't require full release
- **Testing isolation**: Engine changes don't require testing all platforms
- **Custom implementations**: Organizations can create proprietary engines
- **Better separation of concerns**: Clear boundaries between core and platform code
- **Future-proof**: Easier to add new platforms (Linux, embedded systems, etc.)
- **Monorepo benefits**: All core code in one repository under `library/` for easier development and testing
- **Flexible publishing**: Each module publishes independently despite shared repository

### Negative

- **Breaking change**: Major version bump required (2.x → 3.0)
- **Migration effort**: Users must update dependencies and initialization code
- **Increased complexity**: More modules to maintain
- **Documentation overhead**: Need comprehensive guides for engine selection, plugins
- **Initial development cost**: Significant refactoring required (~3-6 months)
- **Backward compatibility layer**: Additional code to maintain during transition period
- **Community fragmentation risk**: Some users may stay on 2.x longer
- **Plugin coordination**: Need governance for community plugins

### Neutral

- **Dependency count increases**: Core + engine vs single library
- **Learning curve**: New concepts (engines, plugins) to understand
- **Build configuration**: Slightly more complex Gradle setup
- **Release coordination**: Need to coordinate core + engine releases initially

## Alternatives Considered

### Alternative 1: Keep Current Monolithic Architecture

Maintain the existing `expect/actual` pattern with all platforms in one artifact.

**Pros:**
- No breaking changes
- Simpler dependency management
- Proven approach for KMP libraries

**Cons:**
- Cannot address extensibility needs
- Continues to grow larger with each platform
- Third-party contributions remain difficult
- No plugin capability

**Why not chosen:** Does not solve the core extensibility and modularity problems driving this proposal.

### Alternative 2: Separate Artifacts Without Engine Abstraction

Split into multiple artifacts but keep platform-specific APIs:
- `blue-falcon-android`
- `blue-falcon-ios`
- etc.

**Pros:**
- Modularity benefits
- Simpler than full engine system
- Each platform can have optimized API

**Cons:**
- No common abstraction layer
- Cannot share core logic effectively
- No plugin system
- Harder to write cross-platform code
- Platform-switching requires code changes

**Why not chosen:** Loses the key benefit of a unified API. Users want one API that works everywhere.

### Alternative 3: Interface-Based Approach Without DSL

Create `BlueFalconEngine` interface but use direct constructor injection instead of DSL:

```kotlin
val engine = AndroidEngine(context)
val blueFalcon = BlueFalcon(engine)
```

**Pros:**
- Simpler than DSL approach
- More explicit
- No magic

**Cons:**
- Less ergonomic than Ktor-style DSL
- Plugin installation less discoverable
- Configuration less readable for multiple plugins

**Why not chosen:** While simpler, the DSL provides better developer experience and aligns with Kotlin ecosystem conventions (Ktor, Koin, etc.). We can provide both approaches.

### Alternative 4: Keep Expect/Actual + Add Extension Points

Add hooks/callbacks to current architecture without full refactor:

```kotlin
expect class BlueFalcon {
    var extensionHandler: BlueFalconExtension?
}
```

**Pros:**
- Minimal refactoring
- Backward compatible
- Incremental adoption

**Cons:**
- Extension mechanism would be limited
- No true modularity
- Still coupled architecture
- Half-measure that delays inevitable refactor

**Why not chosen:** Doesn't provide sufficient long-term value. If we're going to break things, do it right once.

## Implementation Notes

### Repository Structure

All modules remain under the `library/` directory as a monorepo:

```
library/
├── settings.gradle.kts           # Include all modules
├── core/
│   ├── build.gradle.kts         # Publishes as blue-falcon-core
│   └── src/commonMain/
├── engines/
│   ├── android/
│   │   ├── build.gradle.kts     # Publishes as blue-falcon-engine-android
│   │   └── src/androidMain/
│   ├── ios/
│   │   ├── build.gradle.kts     # Publishes as blue-falcon-engine-ios
│   │   └── src/{iosMain,nativeMain}/
│   ├── macos/
│   │   └── src/macosMain/
│   ├── js/
│   │   └── src/jsMain/
│   ├── windows/
│   │   └── src/{windowsMain,cpp}/
│   └── rpi/
│       └── src/rpiMain/
├── plugins/
│   ├── logging/
│   │   └── src/commonMain/
│   ├── retry/
│   └── caching/
└── legacy/                       # Compatibility layer
    └── src/
```

**Gradle Configuration**:
```kotlin
// library/settings.gradle.kts
include(
    ":core",
    ":engines:android",
    ":engines:ios",
    ":engines:macos",
    ":engines:js",
    ":engines:windows",
    ":engines:rpi",
    ":plugins:logging",
    ":plugins:retry",
    ":plugins:caching",
    ":legacy"
)
```

Each module has its own `build.gradle.kts` with independent:
- Version management (can version independently or sync)
- Publishing configuration (to Maven Central)
- Dependencies (engines depend on core, plugins depend on core)

### Phase 1: Core Extraction (Months 1-2)

1. Create `library/core/` module structure
2. Define `BlueFalconEngine` interface in `core/src/commonMain/`
3. Extract common types (BluetoothPeripheral, BluetoothService, etc.) to core
4. Implement plugin infrastructure in core
5. Create DSL API in core
6. Update `library/settings.gradle.kts` to include `:core`

### Phase 2: Engine Migration (Months 2-4)

1. Create `library/engines/` directory and engine module directories:
   - `library/engines/android/`
   - `library/engines/ios/`
   - `library/engines/macos/`
   - `library/engines/js/`
   - `library/engines/windows/`
2. Migrate platform implementations from `library/src/*Main/` to respective engine modules
3. Configure each engine's `build.gradle.kts` for independent publishing
4. Update `library/settings.gradle.kts` to include all engines (`:engines:android`, `:engines:ios`, etc.)
5. Ensure feature parity with 2.x API

### Phase 3: Backward Compatibility (Month 4)

1. Create `library/legacy/` module
2. Implement compatibility layer that wraps new engine API
3. Mark old API as deprecated with migration hints
4. Configure legacy module to publish as separate artifact (optional)
5. Ensure all examples work with both APIs

### Phase 4: Plugin Development (Months 4-5)

1. Create `library/plugins/` directory structure
2. Implement core plugins under `library/plugins/`:
   - `library/plugins/logging/`
   - `library/plugins/retry/`
   - `library/plugins/caching/`
3. Configure each plugin's `build.gradle.kts` for independent publishing
4. Create plugin development guide for community plugins (external repos)
5. Develop example community plugin (e.g., Nordic OTA proof-of-concept)

### Phase 5: Testing & Documentation (Month 5-6)

1. Comprehensive testing across all engines
2. Migration guide from 2.x to 3.0
3. Engine development guide for third parties
4. Plugin development guide
5. Update all examples

### Phase 6: Release (Month 6)

1. Alpha releases for community feedback
2. Beta releases with migration tooling
3. Final 3.0.0 release
4. Maintain 2.x with critical bug fixes for 6-12 months

### Migration Path for Users

**Before (2.x)**:
```kotlin
dependencies {
    implementation("dev.bluefalcon:blue-falcon:2.5.4")
}

val blueFalcon = BlueFalcon(PrintLnLogger, ApplicationContext())
blueFalcon.scan()
```

**After (3.0) - New API**:
```kotlin
dependencies {
    implementation("dev.bluefalcon:blue-falcon-core:3.0.0")
    implementation("dev.bluefalcon:blue-falcon-engine-android:3.0.0")
}

val blueFalcon = BlueFalcon {
    engine = AndroidEngine(context)
    install(LoggingPlugin)
}
blueFalcon.scan()
```

**After (3.0) - Compatibility API**:
```kotlin
dependencies {
    implementation("dev.bluefalcon:blue-falcon-core:3.0.0")
    implementation("dev.bluefalcon:blue-falcon-engine-android:3.0.0")
    implementation("dev.bluefalcon:blue-falcon-legacy:3.0.0") // compat layer
}

// Works unchanged, but shows deprecation warnings
val blueFalcon = BlueFalcon(PrintLnLogger, ApplicationContext())
blueFalcon.scan()
```

### Breaking Changes

- Package restructuring: `dev.bluefalcon` → `dev.bluefalcon.core`, `dev.bluefalcon.engine.*`
- Initialization API change (unless using compatibility layer)
- Dependency changes (one artifact → core + engine)
- File structure: Code moves from `library/src/*Main/` to `library/engines/*/src/*Main/`
- Some internal APIs may be removed or moved

### Repository Structure Benefits

Keeping all modules under `library/` with dedicated `engines/` and `plugins/` folders provides:
- **Clear organization**: Engines grouped together, plugins grouped together
- **Unified development**: All code in one place for local development
- **Shared build logic**: Common Gradle scripts and conventions
- **Atomic changes**: Cross-module refactoring in single commits
- **Easier testing**: Can test engine changes against core in same repo
- **Independent publishing**: Each module still publishes separately to Maven Central
- **Familiar structure**: Maintains existing `library/` organization
- **CI/CD efficiency**: Single repository for builds and releases
- **Discoverability**: Easy to find all engines in `library/engines/` directory

### Versioning Strategy

- **2.x**: Current stable, maintain for 6-12 months post-3.0 release
- **3.0-alpha**: Early preview releases
- **3.0-beta**: Feature complete, migration testing
- **3.0.0**: Stable release
- **3.x**: Evolution of engine architecture

## Related Decisions

- Future ADR may address specific plugin APIs and governance
- Future ADR may address engine certification/testing requirements
- Future ADR may address release coordination between core and engines

## References

- Ktor client architecture: https://ktor.io/docs/http-client-engines.html
- Ktor plugins: https://ktor.io/docs/http-client-plugins.html
- Koin DSL: https://insert-koin.io/docs/reference/koin-core/dsl
- Kotlin Multiplatform libraries best practices: https://kotlinlang.org/docs/multiplatform-library.html
- Blue Falcon current architecture: `/library/src/commonMain/kotlin/dev/bluefalcon/`
- Plugin pattern in Kotlin: https://kotlinlang.org/docs/delegation.html
