# Blue Falcon Tests

## Notification State Callback Tests

This directory contains tests for the `didUpdateNotificationStateFor` callback functionality.

### Test Coverage

1. **BlueFalconDelegateTest.kt**
   - Tests the basic structure of the callback
   - Verifies default implementation works without errors
   - Tests that callbacks can be invoked properly

2. **NotificationStateCallbackTest.kt**
   - Tests callback override capabilities
   - Verifies default implementation safety
   - Tests multiple delegate implementations

### Running Tests

To run the tests, execute:

```bash
./gradlew test
```

For specific platform tests:

```bash
# Android tests
./gradlew :library:testDebugUnitTest

# iOS tests (requires macOS)
./gradlew :library:iosTest
```

### Platform-Specific Testing

#### Android
The Android implementation triggers `didUpdateNotificationStateFor` when:
- A CCCD (Client Characteristic Configuration Descriptor) write completes successfully
- The descriptor UUID matches `00002902-0000-1000-8000-00805f9b34fb`

**Manual Testing on Android:**
1. Connect to a BLE device with notification-capable characteristics
2. Call `notifyCharacteristic(peripheral, characteristic, true)`
3. Verify `didUpdateNotificationStateFor` is called with `isNotifying = true`
4. Call `notifyCharacteristic(peripheral, characteristic, false)`
5. Verify `didUpdateNotificationStateFor` is called with `isNotifying = false`

#### iOS/macOS
The iOS implementation triggers `didUpdateNotificationStateFor` when:
- CoreBluetooth's `peripheral:didUpdateNotificationStateForCharacteristic:error:` delegate method is called
- This happens automatically after `setNotifyValue(_:for:)` completes

**Manual Testing on iOS:**
1. Connect to a BLE device with notification-capable characteristics
2. Call `notifyCharacteristic(peripheral, characteristic, true)`
3. Verify `didUpdateNotificationStateFor` is called with `isNotifying = true`
4. The characteristic's `isNotifying` property should reflect the new state

### Integration Testing

For full integration testing, use the example applications:

1. **Compose Multiplatform Example**
   - Located in `examples/ComposeMultiplatform-Example`
   - Implements the callback in `BleDelegate.kt`
   - Shows practical usage with logging

### Expected Behavior

When notification state changes:
1. The platform-specific implementation detects the change
2. `didUpdateNotificationStateFor` is called on all registered delegates
3. The `BluetoothCharacteristic` parameter has updated `isNotifying` property
4. Developers can verify subscription success by checking `isNotifying`

### Notes

- The callback works for both notifications and indications (both use CCCD)
- On Android, the callback is only triggered for successful CCCD writes
- On iOS, the callback includes error information through the delegate
- The default implementation is empty, so existing code won't break
