# ADR 0001: Add Windows 10 Platform Support Using Native WinRT APIs

**Status:** Accepted

**Date:** 2026-04-10

**Deciders:** Blue Falcon maintainers

**Technical Story:** Windows desktop users requested native BLE support to enable cross-platform Kotlin applications spanning mobile, web, and desktop environments.

## Context

Blue Falcon is a Kotlin Multiplatform BLE library supporting Android, iOS, macOS, JavaScript, and Raspberry Pi. Windows desktop was notably absent as a supported platform, limiting the library's usefulness for developers building truly cross-platform applications.

The primary challenge was determining how to implement Windows BLE support while:
- Maintaining API consistency with existing platforms
- Avoiding third-party dependencies (per project requirements)
- Ensuring native performance
- Working within Kotlin Multiplatform's JVM target constraints

Windows 10 (version 1803+) provides built-in Bluetooth LE support through the Windows Runtime (WinRT) APIs in the `Windows.Devices.Bluetooth` namespace, but these are C++ APIs that require bridging to the JVM.

## Decision

We will implement Windows platform support using a three-layer architecture:

1. **Kotlin/JVM Layer**: Platform-specific implementation of the Blue Falcon API using Kotlin coroutines for async operations
2. **JNI Bridge Layer**: Native interface layer for marshaling calls between Kotlin/Java and C++
3. **Native Windows Layer**: Direct C++ implementation using Windows Runtime (WinRT) Bluetooth LE APIs

This implementation will:
- Use **only** built-in Windows APIs (no third-party Bluetooth libraries)
- Target the `windows` JVM platform in Kotlin Multiplatform
- Require a native DLL (`bluefalcon-windows.dll`) built with CMake and Visual Studio
- Support Windows 10 version 1803 (April 2018 Update) or later
- Provide complete feature parity with other platforms

## Consequences

### Positive

- **Zero third-party dependencies**: Uses only Windows built-in APIs, reducing maintenance burden and security surface
- **Native performance**: Direct WinRT API calls without abstraction overhead
- **Full platform coverage**: Blue Falcon now supports all major desktop and mobile platforms
- **API consistency**: Same Blue Falcon API works identically across all platforms
- **Modern C++ patterns**: Uses C++17, WinRT async patterns, and thread-safe design
- **Complete feature set**: All BLE operations supported (scan, connect, GATT operations, notifications, MTU, descriptors)

### Negative

- **Complex build process**: Requires Visual Studio, Windows 10 SDK, and CMake to build the native library
- **JNI overhead**: Method calls cross JNI boundary, adding minimal latency compared to pure native
- **Platform-specific testing**: Requires actual Windows hardware for integration testing
- **Binary distribution**: Must distribute pre-compiled DLL or provide build instructions
- **Maintenance burden**: Need to maintain C++ codebase alongside Kotlin implementations

### Neutral

- **Development requirements**: Windows 10 SDK (10.0.17763.0+), Visual Studio 2019+, JDK 11+
- **Runtime requirements**: Windows 10 version 1803+, JRE 11+
- **Architecture support**: Initially x64, can be extended to x86 and ARM64
- **File structure**: Adds `windowsMain/kotlin/` and `windowsMain/cpp/` directories

## Alternatives Considered

### Alternative 1: Use blessed-bluez or TinyB Library

A third-party Bluetooth library wrapper that could potentially work on Windows.

**Pros:**
- Potentially simpler implementation
- Might provide cross-platform Linux/Windows support

**Cons:**
- Violates project requirement of "no third-party dependencies"
- blessed-bluez is primarily for Linux (used in RPI implementation)
- TinyB adds external dependency and maintenance risk
- May not support all Windows BLE features
- Additional library to maintain and update

**Why not chosen:** Explicitly rejected per project requirements to avoid third-party Bluetooth libraries.

### Alternative 2: Pure JVM Implementation Using Java Bluetooth APIs

Attempt to use Java's built-in Bluetooth APIs (JSR-82) or newer Java libraries.

**Pros:**
- No native code required
- Simpler build process
- Cross-platform JVM compatibility

**Cons:**
- JSR-82 is outdated and doesn't support BLE properly
- No standard Java BLE API exists in modern JDK
- Third-party Java BLE libraries are limited and Windows support is poor
- Would still require native libraries under the hood

**Why not chosen:** No viable pure-JVM BLE solution exists for Windows with required feature completeness.

### Alternative 3: Kotlin/Native Windows Target

Use Kotlin/Native instead of Kotlin/JVM for Windows platform.

**Pros:**
- Native compilation, no JVM required
- Direct C interop without JNI
- Potentially better performance

**Cons:**
- Kotlin/Native Windows support is less mature than JVM
- Most Kotlin multiplatform projects target JVM for desktop
- Would diverge from standard Kotlin desktop deployment patterns
- Limited ecosystem compared to JVM
- Harder to integrate with existing JVM-based Kotlin applications

**Why not chosen:** JVM target is more practical for desktop Kotlin applications and aligns better with typical deployment scenarios.

### Alternative 4: Wrapper Around Windows CLI Tools

Use Windows PowerShell or command-line Bluetooth utilities.

**Pros:**
- No native code compilation needed
- Simple process execution from JVM

**Cons:**
- Extremely limited functionality
- No support for notifications/indications
- High latency for operations
- Poor error handling
- Not suitable for production applications
- No proper async support

**Why not chosen:** Insufficient functionality and performance for a production BLE library.

## Implementation Notes

### Build Process

The native library must be built separately before the Kotlin library can be used:

1. Navigate to `library/src/windowsMain/cpp`
2. Use CMake to generate Visual Studio project
3. Compile with Visual Studio C++ compiler
4. Resulting DLL must be in Java library path or application working directory

### Migration Path

This is a new platform addition, not a migration. Existing platforms are unaffected.

### Files Created

**Kotlin Layer:**
- `windowsMain/kotlin/dev/bluefalcon/BlueFalcon.kt` (507 lines)
- `windowsMain/kotlin/dev/bluefalcon/BluetoothPeripheral*.kt`
- `windowsMain/kotlin/dev/bluefalcon/BluetoothService.kt`
- `windowsMain/kotlin/dev/bluefalcon/BluetoothCharacteristic.kt`
- Supporting type definitions

**Native Layer:**
- `windowsMain/cpp/BluetoothLEManager.cpp` (618 lines)
- `windowsMain/cpp/BluetoothLEManager.h` (70 lines)
- `windowsMain/cpp/jni_bridge.cpp` (137 lines)
- `windowsMain/cpp/CMakeLists.txt`

**Documentation:**
- `WINDOWS_IMPLEMENTATION_SUMMARY.md`
- `windowsMain/cpp/README.md`
- Updated main `README.md`

### CI/CD Changes

Added Windows verification job to CI workflows:
- `pull-requests.yml`: Build verification on `windows-latest`
- `release.yml`: Compilation verification before publishing

## Related Decisions

- Future ADR may address distribution strategy for pre-compiled native libraries
- Future ADR may address support for additional desktop platforms (Linux desktop)

## References

- Windows Bluetooth LE APIs: https://docs.microsoft.com/en-us/windows/uwp/devices-sensors/bluetooth
- WinRT C++ reference: https://docs.microsoft.com/en-us/uwp/api/windows.devices.bluetooth
- JNI specification: https://docs.oracle.com/en/java/javase/17/docs/specs/jni/index.html
- Original implementation summary: `/WINDOWS_IMPLEMENTATION_SUMMARY.md`
- Build instructions: `/library/src/windowsMain/cpp/README.md`
