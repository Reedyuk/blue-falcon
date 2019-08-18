# ![Blue Falcon](bluefalcon.png) Blue-Falcon

A Bluetooth Kotlin Multiplatform library for iOS and Android. 

Bluetooth in general has the same functionality for all platforms, e.g. connect to device, fetch services, fetch characteristics.

This library is the glue that brings those together so that mobile developers can use one common api to perform the bluetooth actions.

The idea is to have a common api for using bluetooth as the principle of bluetooth is the same but each platform ios and android has different apis which means you have to duplicate the logic for each platform.

## Known Issues

Threading in iOS :(

## TODO:

Create android project
Fix iOS project

## Basic Usage

### iOS

Create an instance of BlueFalcon and then call the scan method.

```swift
let blueFalcon = BlueFalcon()
blueFalcon.scan()
```

### Android

The Android sdk requires an Application context, we do this by using the init method on BlueFalcon.

```kotlin
BlueFalcon.init(PlatformBluetooth(PlatformContext(this)))
BlueFalcon.scan()
```

## Examples

This repo contains examples for ios(todo) and android(todo) in the examples folder, install its dependencies, and run it locally:

## Support

For a **bug, feature request, or cool idea**, please [file a Github issue](https://github.com/Reedyuk/blue-falcon/issues/new).

### Two big little things

Keep in mind that Blue-Falcon is maintained by volunteers. Please be patient if you don’t immediately get an answer to your question; we all have jobs, families, obligations, and lives beyond this project.
