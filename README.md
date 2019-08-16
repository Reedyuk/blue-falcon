# Multi-Blue

A Bluetooth Kotlin Multiplatform library for iOS and Android. 

Bluetooth in general has the same functionality for all platforms, e.g. connect to device, fetch services, fetch characteristics.

This library is the glue that brings those together so that mobile developers can use one common api to perform the bluetooth actions.

The idea is to have a common api for using bluetooth as the principle of bluetooth is the same but each platform ios and android has different apis which means you have to duplicate the logic for each platform.