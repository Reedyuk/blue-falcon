# ![Blue Falcon](bluefalcon.png) Blue-Falcon

A Bluetooth Kotlin Multiplatform library for iOS and Android. 

Bluetooth in general has the same functionality for all platforms, e.g. connect to device, fetch services, fetch characteristics.

This library is the glue that brings those together so that mobile developers can use one common api to perform the bluetooth actions.

The idea is to have a common api for using bluetooth as the principle of bluetooth is the same but each platform ios and android has different apis which means you have to duplicate the logic for each platform.

## Known Issues

No callbacks on requests e.g. new devices

## TODO:

An example using kotlin multiplatform.

Handle different scenarios for permissions e.g. bluetooth off, permission denied

## Basic Usage

### iOS

Create an instance of BlueFalcon and then call the scan method.

```swift
let blueFalcon = BlueFalcon()
blueFalcon.scan()
```

### Android

The Android sdk requires an Application context, we do this by passing in on the BlueFalcon constructor, in this example we are calling the code from an activity(this).

```kotlin
try {
    val blueFalcon = BlueFalcon(this)
    blueFalcon.scan()
} catch (exception: PermissionException) {
    //request the ACCESS_COARSE_LOCATION permission
}
```

## Examples

This repo contains examples for ios and android in the examples folder, install their dependencies, and run it locally:

## Support

For a **bug, feature request, or cool idea**, please [file a Github issue](https://github.com/Reedyuk/blue-falcon/issues/new).

### Two big little things

Keep in mind that Blue-Falcon is maintained by volunteers. Please be patient if you don’t immediately get an answer to your question; we all have jobs, families, obligations, and lives beyond this project.
