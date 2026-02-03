# Building Windows Bluetooth Native Library

## Prerequisites

To build the Windows Bluetooth native library, you need:

1. **Windows 10 SDK** (version 10.0.17763.0 or later)
2. **Visual Studio 2019 or later** with C++ development tools
3. **CMake** (version 3.14 or later)
4. **Java Development Kit (JDK)** 11 or later

## Building the Native Library

### Using CMake and Visual Studio

1. Open a Visual Studio Developer Command Prompt
2. Navigate to the cpp directory:
   ```
   cd library\src\windowsMain\cpp
   ```

3. Create a build directory and run CMake:
   ```
   mkdir build
   cd build
   cmake .. -G "Visual Studio 16 2019" -A x64
   ```

4. Build the library:
   ```
   cmake --build . --config Release
   ```

5. The compiled `bluefalcon-windows.dll` will be in the `Release` directory

6. Copy the DLL to your Java library path or to:
   ```
   library\src\windowsMain\resources\
   ```

### Alternative: Using MSBuild directly

You can also build directly with MSBuild:
```
msbuild bluefalcon-windows.sln /p:Configuration=Release /p:Platform=x64
```

## Troubleshooting

### Missing Windows SDK
If you get errors about missing Windows SDK, install the Windows 10 SDK from:
https://developer.microsoft.com/en-us/windows/downloads/windows-10-sdk/

### JNI Headers Not Found
Make sure JAVA_HOME environment variable is set correctly:
```
set JAVA_HOME=C:\Path\To\JDK
```

### C++/WinRT Errors
The Windows SDK includes C++/WinRT headers. Make sure you have Windows 10 SDK version 10.0.17763.0 or later.

## Architecture Support

The library currently supports x64 (64-bit) architecture. To build for other architectures:
- For x86 (32-bit): Use `-A Win32` with CMake
- For ARM64: Use `-A ARM64` with CMake

## Notes

- The native library requires Windows 10 version 1803 (April 2018 Update) or later for full Bluetooth LE support
- The library uses Windows Runtime (WinRT) APIs which are standard on Windows 10
- No third-party dependencies are required
