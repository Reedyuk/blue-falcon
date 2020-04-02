# ![Blue Falcon](bluefalcon.png) Blue-Falcon [![Build Status](https://api.travis-ci.com/Reedyuk/blue-falcon.svg?branch=master)](https://api.travis-ci.com/Reedyuk/blue-falcon) [![Kotlin](https://img.shields.io/badge/kotlin-1.3.71-blue.svg)](http://kotlinlang.org)

A Bluetooth "Cross Platform" Kotlin Multiplatform library for iOS and Android. 

Bluetooth in general has the same functionality for all platforms, e.g. connect to device, fetch services, fetch characteristics.

This library is the glue that brings those together so that mobile developers can use one common api to perform the bluetooth actions.

The idea is to have a common api for using bluetooth as the principle of bluetooth is the same but each platform ios and android has different apis which means you have to duplicate the logic for each platform.

What this library isn't? It is not a cross platform library, this is a multiplatform library. The difference? each platform is compiled down to the native code, so when you use the library in iOS, you are consuming an obj-c library and same principle for Android.

## Known issues:

On the android example we have an issue where the characteristic which was originally stored has changed and we are holding onto an old version.

## Basic Usage

### iOS

Create an instance of BlueFalcon and then call the scan method. 

By passing in a string uuid of the service uuid, you can filter to scan for only devices that have that service.

```swift
let blueFalcon = BlueFalcon(serviceUUID: nil)
blueFalcon.scan()
```

### Android

#### Install

```kotlin
implementation 'dev.bluefalcon:library-android:0.6.1'
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

### BlueFalcon API

The basic functionality of the api is listed below, this should be a simplistic as possible and is the same in any platform.

```kotlin
    fun connect(bluetoothPeripheral: BluetoothPeripheral)
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

### iOS

To run the iOS example, simply perform a pod install and run from xcode.

```bash
pod install
```

### Android

Open the root directory of the project in Android Studio and run the Android app target from the ide.

## Support

For a **bug, feature request, or cool idea**, please [file a Github issue](https://github.com/Reedyuk/blue-falcon/issues/new).

### Two big little things

Keep in mind that Blue-Falcon is maintained by volunteers. Please be patient if you don’t immediately get an answer to your question; we all have jobs, families, obligations, and lives beyond this project.
