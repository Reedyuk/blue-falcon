# Blue Falcon - Copilot Instructions

Blue Falcon is a Bluetooth Low Energy (BLE) Kotlin Multiplatform library supporting iOS, Android, macOS, Raspberry Pi, Windows, and JavaScript.

## Build, Test, and Lint Commands

All commands are run from the `library/` directory using the Gradle wrapper from the project root:

```bash
# Build the library (all targets)
cd library && ../gradlew build

# Build specific targets
cd library && ../gradlew compileKotlinAndroid
cd library && ../gradlew compileKotlinIos
cd library && ../gradlew compileKotlinWindows windowsJar
cd library && ../gradlew jsBrowserProductionWebpack

# Assemble outputs
cd library && ../gradlew assemble

# Clean build artifacts
cd library && ../gradlew clean

# Tests (platform-specific)
cd library && ../gradlew linkDebugTestIosX64
cd library && ../gradlew linkDebugTestMacosArm64
cd library && ../gradlew jsTestClasses

# Publish to Maven Central (requires credentials)
cd library && ../gradlew publish
```

**Note**: The Gradle wrapper (`gradlew`) is located at the project root, but the build configuration is in the `library/` subdirectory.

## Architecture Overview

### Multiplatform Structure

Blue Falcon uses Kotlin Multiplatform with an **expect/actual** pattern to provide a unified API while implementing platform-specific Bluetooth functionality:

- **commonMain**: Contains `expect` declarations for:
  - `BlueFalcon` - Main BLE manager class
  - `BluetoothPeripheral`, `BluetoothService`, `BluetoothCharacteristic` - Core BLE entities
  - `ApplicationContext` - Platform-specific context wrapper
  - `Uuid`, `ServiceFilter` - Platform-agnostic types
  - `BlueFalconDelegate` - Event callback interface (concrete, not expect/actual)
  - `Logger` interface and `PrintLnLogger` implementation

- **Platform implementations** (`*Main` source sets):
  - `androidMain` - Uses Android BLE APIs (`BluetoothGatt`, `BluetoothAdapter`)
  - `iosMain` - Uses CoreBluetooth framework via Kotlin/Native
  - `macosMain` - Uses CoreBluetooth framework via Kotlin/Native
  - `jsMain` - Uses Web Bluetooth API
  - `windowsMain` - Uses Windows Runtime (WinRT) Bluetooth APIs via JNI (includes native C++ code in `src/windowsMain/cpp/`)
  - `rpiMain` - Raspberry Pi-specific implementation (currently disabled in build)
  - `nativeMain` - Shared code for native targets (iOS/macOS)

### Key Architectural Patterns

1. **Delegate Pattern**: `BlueFalconDelegate` provides callback methods for BLE events (discovery, connection, characteristic changes). Multiple delegates can be registered via `blueFalcon.delegates.add(delegate)`.

2. **StateFlow for State Management**:
   - `blueFalcon.peripherals` - Emits discovered peripherals as a `Set<BluetoothPeripheral>`
   - `blueFalcon.managerState` - Emits `BluetoothManagerState` (Ready/NotReady)

3. **Platform Context Abstraction**: Each platform has its own `ApplicationContext` implementation:
   - Android: Wraps Android `Context`
   - iOS/macOS: Empty wrapper (not required)
   - Windows: Empty wrapper (not required)
   - JS: Empty wrapper (not required)

4. **Auto-Discovery**: When `autoDiscoverAllServicesAndCharacteristics = true` (default), services and characteristics are automatically discovered after connection.

### Important Files

- `library/src/commonMain/kotlin/dev/bluefalcon/BlueFalcon.kt` - Main API definition (expect class)
- `library/src/commonMain/kotlin/dev/bluefalcon/BlueFalconDelegate.kt` - Event callback interface
- `library/src/androidMain/kotlin/dev/bluefalcon/BlueFalcon.kt` - Android implementation
- `library/src/nativeMain/kotlin/dev/bluefalcon/BlueFalcon.kt` - iOS/macOS implementation
- `library/src/windowsMain/kotlin/dev/bluefalcon/BlueFalcon.kt` - Windows implementation
- `library/src/windowsMain/cpp/` - Native Windows Bluetooth bridge (JNI C++ code)

## Key Conventions

### Expect/Actual Pattern

All platform-specific types must have an `expect` declaration in `commonMain` and corresponding `actual` implementations in each platform source set. When adding new functionality:

1. Add `expect` declaration in `commonMain/kotlin/dev/bluefalcon/`
2. Add `actual` implementation in each platform's `*Main/kotlin/dev/bluefalcon/`
3. Ensure all platforms compile, even if some platforms provide no-op implementations

### Logger Usage

- The `Logger` interface accepts an optional `Throwable` cause parameter on all methods
- Methods signature: `error(message: String, cause: Throwable? = null)`
- `PrintLnLogger` is the default implementation (prints to stdout)
- Pass `log = null` to `BlueFalcon` constructor to disable logging

### Platform Identifiers

`retrievePeripheral(identifier: String)` uses different identifier formats per platform:
- **Android**: MAC address format (e.g., `"00:11:22:33:44:55"`)
- **iOS/Native**: UUID format (e.g., `"XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX"`)

### Windows-Specific Notes

- Windows implementation requires native DLL (`bluefalcon-windows.dll`)
- Native code is in `library/src/windowsMain/cpp/`
- Uses Windows Runtime (WinRT) APIs through JNI
- Requires Windows 10 version 1803+ and JDK 17+
- Build verification runs on `windows-latest` in CI

### Version and Publishing

- Version is defined in `library/gradle.properties` (currently `2.5.4`)
- Group ID: `dev.bluefalcon`
- Artifact ID: `blue-falcon`
- Published to Maven Central via `com.vanniktech.maven.publish` plugin
- Supports publishing all Android library variants (see `publishAllLibraryVariants()`)

### Examples Directory

Working examples are in `/examples/`:
- `ComposeMultiplatform-Example` - Full-featured Android/iOS app with Compose UI
- `Android-Example` - Android-specific example
- `KotlinMP-Example` - Kotlin Multiplatform example
- `JS-Example` - Web Bluetooth example
- `RPI-Example` - Raspberry Pi example

### Coroutines

- Uses `kotlinx-coroutines-core` version `1.9.0`
- All StateFlows are backed by coroutines (see `blueFalcon.scope`)

### Gradle Configuration

- Kotlin version: `2.3.0`
- JVM toolchain: Java 17
- Android compile SDK: 33, min SDK: 24
- JavaScript: Browser target with executable binaries
- Apple targets: `iosSimulatorArm64`, `iosX64`, `iosArm64`, `macosArm64`, `macosX64`
- Language settings: Opts-in to `kotlin.uuid.ExperimentalUuidApi`

## Architecture Decision Records (ADRs)

Blue Falcon uses ADRs to document important architectural decisions. ADRs are stored in `/docs/adr/`.

### Creating a New ADR

When making significant architectural decisions (e.g., adding a new platform, changing core APIs, adopting new patterns), create an ADR:

1. **Determine the next ADR number:**
   ```bash
   ls docs/adr/ | grep -E '^[0-9]{4}' | sort | tail -1
   # Increment the number
   ```

2. **Create the ADR file:**
   ```bash
   # Format: NNNN-brief-title.md (e.g., 0001-use-kotlin-multiplatform.md)
   cp docs/adr/ADR-TEMPLATE.md docs/adr/NNNN-your-title.md
   ```

3. **Fill in the template sections:**
   - **Status**: Start with "Proposed", change to "Accepted" when approved
   - **Date**: Current date in YYYY-MM-DD format
   - **Context**: Explain the problem and why a decision is needed
   - **Decision**: State clearly what was decided (use "We will...")
   - **Consequences**: List positive, negative, and neutral impacts
   - **Alternatives Considered**: Document other options and why they weren't chosen
   - **Implementation Notes**: Any migration or rollout considerations

4. **Update the index:**
   - Add a link to your ADR in `/docs/adr/README.md` under the Index section

5. **Commit with a descriptive message:**
   ```bash
   git add docs/adr/
   git commit -m "docs: add ADR NNNN for [decision topic]"
   ```

### ADR Naming Convention

- Use zero-padded 4-digit numbers: `0001`, `0002`, etc.
- Use kebab-case for titles: `use-kotlin-multiplatform`, not `Use Kotlin Multiplatform`
- Keep titles brief but descriptive

### When to Create an ADR

Create an ADR for decisions that:
- Affect the core architecture or public API
- Have long-term implications (difficult to reverse)
- Involve significant tradeoffs
- Impact multiple platforms or components
- Are likely to be questioned or revisited later

Examples: Platform support strategy, state management approach, dependency choices, API design patterns, build system changes.

### When NOT to Create an ADR

Don't create ADRs for:
- Minor implementation details
- Obvious or standard practices
- Temporary workarounds
- Decisions that can be easily reversed

### AI-Assisted ADR Creation

When using AI to create an ADR, provide:
- The decision topic and context
- Alternatives you've considered
- Key constraints or requirements
- Relevant discussion links or issues

Example prompt: "Create ADR for adopting StateFlow for peripheral state management. We currently use delegates only. Consider RxJava and LiveData as alternatives. Main concern is multiplatform compatibility."
