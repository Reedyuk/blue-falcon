# ![Blue Falcon](bluefalcon.png) Blue-Falcon ![CI](https://github.com/Reedyuk/blue-falcon/actions/workflows/release.yml/badge.svg) [![Kotlin](https://img.shields.io/badge/kotlin-2.0.0-blue.svg)](http://kotlinlang.org) ![badge][badge-android] ![badge][badge-native] ![badge][badge-mac] ![badge][badge-rpi] ![badge][badge-js] ![badge][badge-windows] <a href="https://git.live"><img src="https://img.shields.io/endpoint?url=https%3A%2F%2Fteamhub-dev.web.app%2Fbadge%3Forg%3DReedyuk%26repo%3Dblue-falcon"></a>

A Bluetooth BLE "Cross Platform" Kotlin Multiplatform library for iOS, Android, MacOS, Raspberry Pi, Windows and Javascript.

BLE in general has the same functionality for all platforms, e.g. connect to device, fetch services, fetch characteristics.

This library is the glue that brings those together so that mobile developers can use one common api to perform the BLE actions.

The idea is to have a common api for BLE devices as the principle of BLE is the same but each platform ios and android has different apis which means you have to duplicate the logic for each platform.

What this library isn't? It is not a cross platform library, this is a multiplatform library. The difference? each platform is compiled down to the native code, so when you use the library in iOS, you are consuming an obj-c library and same principle for Android and so on.

## Basic Usage

Include the library in your own KMP project as a dependency on your common target.

```
commonMain.dependencies {
    implementation("dev.bluefalcon:blue-falcon:2.0.0")
}
```

Once you have included it then you will need to create an instance of BlueFalcon and pass in an application context.

The Android sdk requires an Application context, we do this by passing in on the BlueFalcon constructor, in this example we are calling the code from an activity(this).

By passing in a string uuid of the service uuid, you can filter to scan for only devices that have that service.

```kotlin
try {
    val blueFalcon = BlueFalcon(log = null, ApplicationContext())
    blueFalcon.scan()
} catch (exception: PermissionException) {
    //request the ACCESS_COARSE_LOCATION permission
}
```

### Javascript 

#### Install

Simply copy the compiled javascript file (blue-falcon.js) to your web directory.

See the JS-Example for details on how to use.

### Windows

#### Requirements

- Windows 10 version 1803 (April 2018 Update) or later
- Java Development Kit (JDK) 11 or later

#### Building Native Library

The Windows implementation uses native Windows Bluetooth LE APIs through JNI (Java Native Interface). To build the native library:

1. Install Visual Studio 2019 or later with C++ development tools
2. Install Windows 10 SDK (version 10.0.17763.0 or later)
3. Navigate to `library/src/windowsMain/cpp`
4. Follow the build instructions in the README.md file in that directory

The implementation uses Windows Runtime (WinRT) APIs which are built into Windows 10, so no third-party dependencies are required.

#### Usage

```kotlin
// On Windows, ApplicationContext is empty but still required
val blueFalcon = BlueFalcon(log = null, ApplicationContext())
blueFalcon.scan()
```

Make sure the `bluefalcon-windows.dll` is in your Java library path or in the application's working directory.

### BlueFalcon API

The basic functionality of the api is listed below, this should be a simplistic as possible and is the same in any platform.

```kotlin
    fun connect(bluetoothPeripheral: BluetoothPeripheral, autoConnect: Boolean)
    fun disconnect(bluetoothPeripheral: BluetoothPeripheral)
    fun scan()
    fun stopScanning()
    fun readCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    )
    fun notifyCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        notify: Boolean
    )
    fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: String
    )
    fun readDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor
    )
    fun changeMTU(bluetoothPeripheral: BluetoothPeripheral, mtuSize: Int)
```

## Examples

This repo contains examples for kotlin MP, ios and android in the examples folder, install their dependencies, and run it locally:

### Compose Multiplatform

This example demonstrates using Kotlin Multiplatform Compose and Blue Falcon to scan for bluetooth devices and rendering using Multiplatform Compose

### Kotlin MP

This example demonstrates how to integrate Blue Falcon in your own project as a dependency on your library/project.

### Raspberry Pi

This example can only be ran on a Raspberry pi, it will crash otherwise.

### Javascript

Open the index.html file in a web browser.

## Logger

BlueFalcon has a constructor that takes a Logger, you can implement your own logger, to handle and reduce or add to the noise generated.

Look at the PrintLnLogger object of an example of how to do this.

## Support

For a **bug, feature request, or cool idea**, please [file a Github issue](https://github.com/Reedyuk/blue-falcon/issues/new).

### Two big little things

Keep in mind that Blue-Falcon is maintained by volunteers. Please be patient if you don’t immediately get an answer to your question; we all have jobs, families, obligations, and lives beyond this project.

Many thanks to everyone so far who has contributed to the project, it really means alot.


[badge-android]: http://img.shields.io/badge/platform-android-brightgreen.svg?style=flat
[badge-native]: http://img.shields.io/badge/platform-native-lightgrey.svg?style=flat
[badge-js]: http://img.shields.io/badge/platform-js-yellow.svg?style=flat
[badge-mac]: http://img.shields.io/badge/platform-macos-lightgrey.svg?style=flat
[badge-rpi]: http://img.shields.io/badge/platform-rpi-lightgrey.svg?style=flat
[badge-windows]: http://img.shields.io/badge/platform-windows-blue.svg?style=flat
