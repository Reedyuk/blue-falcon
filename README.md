# ![Blue Falcon](bluefalcon.png) Blue-Falcon ![CI](https://github.com/Reedyuk/blue-falcon/workflows/CI/badge.svg) [![Kotlin](https://img.shields.io/badge/kotlin-1.6.20-blue.svg)](http://kotlinlang.org) ![badge][badge-android] ![badge][badge-native] ![badge][badge-mac] ![badge][badge-rpi] ![badge][badge-js]

A Bluetooth "Cross Platform" Kotlin Multiplatform library for iOS, Android, MacOS, Raspberry Pi and Javascript.

Bluetooth in general has the same functionality for all platforms, e.g. connect to device, fetch services, fetch characteristics.

This library is the glue that brings those together so that mobile developers can use one common api to perform the bluetooth actions.

The idea is to have a common api for using bluetooth as the principle of bluetooth is the same but each platform ios and android has different apis which means you have to duplicate the logic for each platform.

What this library isn't? It is not a cross platform library, this is a multiplatform library. The difference? each platform is compiled down to the native code, so when you use the library in iOS, you are consuming an obj-c library and same principle for Android and so on.

## Basic Usage

### iOS & MacOS

Create an instance of BlueFalcon and then call the scan method. 

By passing in a string uuid of the service uuid, you can filter to scan for only devices that have that service.

```swift
let blueFalcon = BlueFalcon(serviceUUID: nil)
blueFalcon.scan()
```

### Android

#### Install

```kotlin
implementation 'dev.bluefalcon:blue-falcon-android:0.10.6'
```

And if you are using the debug variant:

```kotlin
implementation 'dev.bluefalcon:blue-falcon-android-debug:0.10.6'
```

The Android sdk requires an Application context, we do this by passing in on the BlueFalcon constructor, in this example we are calling the code from an activity(this).

By passing in a string uuid of the service uuid, you can filter to scan for only devices that have that service.

```kotlin
try {
    val blueFalcon = BlueFalcon(this, null)
    blueFalcon.scan()
} catch (exception: PermissionException) {
    //request the ACCESS_COARSE_LOCATION permission
}
```

### Raspberry Pi

#### Install

The Raspberry Pi library is using Java.

```kotlin
implementation 'dev.bluefalcon:blue-falcon-rpi:0.10.6'
```

### Javascript 

#### Install

Simply copy the compiled javascript file (blue-falcon.js) to your web directory.

See the JS-Example for details on how to use.

### Kotlin Multiplatform

### Install

```kotlin
implementation 'dev.bluefalcon:blue-falcon:0.10.6'
```

Please look at the Kotlin Multiplatform example in the Examples folder.


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

### Kotlin MP

Open the kotlin MP example directory in InteliJ and then run the install targets.

### iOS & MacOS

To run the MacOS & iOS example, you need to reference the relevant framework by including it in your poroject. Ensure your project has the Framework search paths referencing the framework directory.

### Android

Open the root directory of the project in Android Studio and run the Android app target from the ide.

### Raspberry Pi

This example can only be ran on a Raspberry pi, it will crash otherwise.

### Javascript

Open the index.html file in a web browser.

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
