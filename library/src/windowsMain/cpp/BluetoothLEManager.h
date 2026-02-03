#pragma once

#include <jni.h>
#include <windows.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Devices.Bluetooth.h>
#include <winrt/Windows.Devices.Bluetooth.GenericAttributeProfile.h>
#include <winrt/Windows.Devices.Bluetooth.Advertisement.h>
#include <winrt/Windows.Devices.Enumeration.h>
#include <string>
#include <map>
#include <memory>

using namespace winrt;
using namespace Windows::Foundation;
using namespace Windows::Foundation::Collections;
using namespace Windows::Devices::Bluetooth;
using namespace Windows::Devices::Bluetooth::GenericAttributeProfile;
using namespace Windows::Devices::Bluetooth::Advertisement;
using namespace Windows::Devices::Enumeration;

// Forward declarations
class BluetoothLEManager;

// Structure to hold device connection state
struct DeviceConnection {
    uint64_t address;
    BluetoothLEDevice device{nullptr};
    std::map<winrt::guid, GattDeviceService> services;
    std::map<winrt::guid, GattCharacteristic> characteristics;
    int connectionState; // 0=disconnected, 1=connecting, 2=connected, 3=disconnecting
};

// Singleton manager for Bluetooth LE operations
class BluetoothLEManager {
public:
    static BluetoothLEManager& getInstance();
    
    void initialize(JavaVM* jvm, jobject javaObject);
    void startScan(JNIEnv* env, jobjectArray serviceUuids);
    void stopScan();
    void connect(uint64_t address);
    void disconnect(uint64_t address);
    int getConnectionState(uint64_t address);
    void discoverServices(uint64_t address);
    void discoverCharacteristics(uint64_t address, const std::wstring& serviceUuid);
    void readCharacteristic(uint64_t address, const std::wstring& characteristicUuid);
    void writeCharacteristic(uint64_t address, const std::wstring& characteristicUuid, 
                            const uint8_t* data, size_t length, bool withResponse);
    void setNotify(uint64_t address, const std::wstring& characteristicUuid, bool enable);
    void setIndicate(uint64_t address, const std::wstring& characteristicUuid, bool enable);
    void readDescriptor(uint64_t address, const std::wstring& descriptorUuid);
    void writeDescriptor(uint64_t address, const std::wstring& descriptorUuid, 
                        const uint8_t* data, size_t length);
    void changeMTU(uint64_t address, int mtu);

private:
    BluetoothLEManager() = default;
    ~BluetoothLEManager();
    
    // Helper methods
    void callJavaMethod(const char* methodName, const char* signature, ...);
    std::wstring stringToWString(const std::string& str);
    std::string wstringToString(const std::wstring& wstr);
    uint64_t bluetoothAddressToUint64(uint64_t address);
    
    // Advertisement watcher
    BluetoothLEAdvertisementWatcher m_watcher{nullptr};
    event_token m_receivedToken;
    event_token m_stoppedToken;
    
    // Java VM and object references
    JavaVM* m_jvm = nullptr;
    jobject m_javaObject = nullptr;
    
    // Device connections
    std::map<uint64_t, std::shared_ptr<DeviceConnection>> m_connections;
    std::mutex m_connectionsMutex;
};
