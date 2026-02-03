#include "BluetoothLEManager.h"
#include <sstream>
#include <iomanip>
#include <algorithm>
#include <codecvt>

BluetoothLEManager& BluetoothLEManager::getInstance() {
    static BluetoothLEManager instance;
    return instance;
}

BluetoothLEManager::~BluetoothLEManager() {
    stopScan();
    if (m_javaObject) {
        JNIEnv* env = nullptr;
        m_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
        if (env) {
            env->DeleteGlobalRef(m_javaObject);
        }
    }
}

void BluetoothLEManager::initialize(JavaVM* jvm, jobject javaObject) {
    m_jvm = jvm;
    
    JNIEnv* env = nullptr;
    jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (env) {
        m_javaObject = env->NewGlobalRef(javaObject);
    }
    
    // Initialize WinRT
    init_apartment();
}

void BluetoothLEManager::startScan(JNIEnv* env, jobjectArray serviceUuids) {
    stopScan(); // Stop any existing scan
    
    // Create advertisement watcher
    m_watcher = BluetoothLEAdvertisementWatcher();
    m_watcher.ScanningMode(BluetoothLEScanningMode::Active);
    
    // Add service UUID filters if provided
    if (serviceUuids != nullptr) {
        jsize count = env->GetArrayLength(serviceUuids);
        for (jsize i = 0; i < count; i++) {
            jstring jUuid = (jstring)env->GetObjectArrayElement(serviceUuids, i);
            const char* uuidStr = env->GetStringUTFChars(jUuid, nullptr);
            
            try {
                std::wstring wUuid = stringToWString(std::string(uuidStr));
                winrt::guid guid = winrt::guid(wUuid);
                
                BluetoothLEAdvertisementFilter filter;
                BluetoothLEAdvertisement advertisement;
                advertisement.ServiceUuids().Append(guid);
                filter.Advertisement(advertisement);
                m_watcher.AdvertisementFilter(filter);
            } catch (...) {
                // Invalid UUID, skip
            }
            
            env->ReleaseStringUTFChars(jUuid, uuidStr);
            env->DeleteLocalRef(jUuid);
        }
    }
    
    // Register event handlers
    m_receivedToken = m_watcher.Received([this](BluetoothLEAdvertisementWatcher watcher,
                                                 BluetoothLEAdvertisementReceivedEventArgs args) {
        try {
            uint64_t address = args.BluetoothAddress();
            int16_t rssi = args.RawSignalStrengthInDBm();
            
            // Get device name
            std::string deviceName;
            auto localName = args.Advertisement().LocalName();
            if (!localName.empty()) {
                deviceName = wstringToString(localName.c_str());
            }
            
            // Check if connectable
            bool isConnectable = args.AdvertisementType() == 
                BluetoothLEAdvertisementType::ConnectableUndirected ||
                args.AdvertisementType() == 
                BluetoothLEAdvertisementType::ConnectableDirected;
            
            // Call Java callback
            JNIEnv* env = nullptr;
            if (m_jvm->AttachCurrentThread((void**)&env, nullptr) == JNI_OK) {
                jclass cls = env->GetObjectClass(m_javaObject);
                jmethodID mid = env->GetMethodID(cls, "onDeviceDiscovered", "(JLjava/lang/String;FZ)V");
                
                if (mid != nullptr) {
                    jstring jName = deviceName.empty() ? nullptr : 
                        env->NewStringUTF(deviceName.c_str());
                    env->CallVoidMethod(m_javaObject, mid, (jlong)address, jName, 
                                      (jfloat)rssi, (jboolean)isConnectable);
                    if (jName) env->DeleteLocalRef(jName);
                }
                
                env->DeleteLocalRef(cls);
                m_jvm->DetachCurrentThread();
            }
        } catch (...) {
            // Ignore errors
        }
    });
    
    m_stoppedToken = m_watcher.Stopped([](BluetoothLEAdvertisementWatcher watcher,
                                          BluetoothLEAdvertisementWatcherStoppedEventArgs args) {
        // Scan stopped
    });
    
    m_watcher.Start();
}

void BluetoothLEManager::stopScan() {
    if (m_watcher != nullptr) {
        if (m_watcher.Status() == BluetoothLEAdvertisementWatcherStatus::Started ||
            m_watcher.Status() == BluetoothLEAdvertisementWatcherStatus::Created) {
            m_watcher.Stop();
        }
        
        if (m_receivedToken.value != 0) {
            m_watcher.Received(m_receivedToken);
            m_receivedToken = event_token{};
        }
        
        if (m_stoppedToken.value != 0) {
            m_watcher.Stopped(m_stoppedToken);
            m_stoppedToken = event_token{};
        }
        
        m_watcher = nullptr;
    }
}

void BluetoothLEManager::connect(uint64_t address) {
    std::lock_guard<std::mutex> lock(m_connectionsMutex);
    
    auto it = m_connections.find(address);
    if (it != m_connections.end() && it->second->connectionState == 2) {
        return; // Already connected
    }
    
    auto connection = std::make_shared<DeviceConnection>();
    connection->address = address;
    connection->connectionState = 1; // Connecting
    m_connections[address] = connection;
    
    // Connect to device asynchronously
    auto asyncOp = BluetoothLEDevice::FromBluetoothAddressAsync(address);
    asyncOp.Completed([this, address](auto&& asyncInfo, AsyncStatus status) {
        std::lock_guard<std::mutex> lock(m_connectionsMutex);
        auto it = m_connections.find(address);
        if (it == m_connections.end()) return;
        
        if (status == AsyncStatus::Completed) {
            try {
                it->second->device = asyncInfo.GetResults();
                it->second->connectionState = 2; // Connected
                
                // Notify Java
                JNIEnv* env = nullptr;
                if (m_jvm->AttachCurrentThread((void**)&env, nullptr) == JNI_OK) {
                    // Connection successful - Java code will call discoverServices
                    m_jvm->DetachCurrentThread();
                }
            } catch (...) {
                m_connections.erase(it);
            }
        } else {
            m_connections.erase(it);
        }
    });
}

void BluetoothLEManager::disconnect(uint64_t address) {
    std::lock_guard<std::mutex> lock(m_connectionsMutex);
    
    auto it = m_connections.find(address);
    if (it != m_connections.end()) {
        it->second->connectionState = 3; // Disconnecting
        
        // Close device
        if (it->second->device != nullptr) {
            it->second->device.Close();
            it->second->device = nullptr;
        }
        
        it->second->connectionState = 0; // Disconnected
        m_connections.erase(it);
    }
}

int BluetoothLEManager::getConnectionState(uint64_t address) {
    std::lock_guard<std::mutex> lock(m_connectionsMutex);
    
    auto it = m_connections.find(address);
    if (it != m_connections.end()) {
        return it->second->connectionState;
    }
    
    return 0; // Disconnected
}

void BluetoothLEManager::discoverServices(uint64_t address) {
    std::lock_guard<std::mutex> lock(m_connectionsMutex);
    
    auto it = m_connections.find(address);
    if (it == m_connections.end() || it->second->device == nullptr) {
        return;
    }
    
    auto device = it->second->device;
    
    // Get GATT services asynchronously
    auto asyncOp = device.GetGattServicesAsync();
    asyncOp.Completed([this, address](auto&& asyncInfo, AsyncStatus status) {
        if (status == AsyncStatus::Completed) {
            try {
                auto result = asyncInfo.GetResults();
                if (result.Status() == GattCommunicationStatus::Success) {
                    auto services = result.Services();
                    
                    std::lock_guard<std::mutex> lock(m_connectionsMutex);
                    auto it = m_connections.find(address);
                    if (it == m_connections.end()) return;
                    
                    // Store services and collect UUIDs
                    std::vector<std::wstring> serviceUuids;
                    for (auto service : services) {
                        auto uuid = service.Uuid();
                        it->second->services[uuid] = service;
                        
                        // Convert UUID to string
                        wchar_t uuidStr[40];
                        swprintf_s(uuidStr, 40, L"%08x-%04x-%04x-%02x%02x-%02x%02x%02x%02x%02x%02x",
                                 uuid.Data1, uuid.Data2, uuid.Data3,
                                 uuid.Data4[0], uuid.Data4[1], uuid.Data4[2], uuid.Data4[3],
                                 uuid.Data4[4], uuid.Data4[5], uuid.Data4[6], uuid.Data4[7]);
                        serviceUuids.push_back(uuidStr);
                    }
                    
                    // Notify Java
                    JNIEnv* env = nullptr;
                    if (m_jvm->AttachCurrentThread((void**)&env, nullptr) == JNI_OK) {
                        jclass stringClass = env->FindClass("java/lang/String");
                        jobjectArray jUuids = env->NewObjectArray(serviceUuids.size(), stringClass, nullptr);
                        
                        for (size_t i = 0; i < serviceUuids.size(); i++) {
                            std::string uuidStr = wstringToString(serviceUuids[i]);
                            jstring jUuid = env->NewStringUTF(uuidStr.c_str());
                            env->SetObjectArrayElement(jUuids, i, jUuid);
                            env->DeleteLocalRef(jUuid);
                        }
                        
                        jclass cls = env->GetObjectClass(m_javaObject);
                        jmethodID mid = env->GetMethodID(cls, "onServicesDiscovered", "(J[Ljava/lang/String;)V");
                        if (mid != nullptr) {
                            env->CallVoidMethod(m_javaObject, mid, (jlong)address, jUuids);
                        }
                        
                        env->DeleteLocalRef(jUuids);
                        env->DeleteLocalRef(stringClass);
                        env->DeleteLocalRef(cls);
                        m_jvm->DetachCurrentThread();
                    }
                }
            } catch (...) {
                // Ignore errors
            }
        }
    });
}

void BluetoothLEManager::discoverCharacteristics(uint64_t address, const std::wstring& serviceUuidStr) {
    std::lock_guard<std::mutex> lock(m_connectionsMutex);
    
    auto it = m_connections.find(address);
    if (it == m_connections.end()) return;
    
    // Find service by UUID
    winrt::guid serviceGuid = winrt::guid(serviceUuidStr);
    auto serviceIt = it->second->services.find(serviceGuid);
    if (serviceIt == it->second->services.end()) return;
    
    auto service = serviceIt->second;
    
    // Get characteristics asynchronously
    auto asyncOp = service.GetCharacteristicsAsync();
    asyncOp.Completed([this, address, serviceGuid](auto&& asyncInfo, AsyncStatus status) {
        if (status == AsyncStatus::Completed) {
            try {
                auto result = asyncInfo.GetResults();
                if (result.Status() == GattCommunicationStatus::Success) {
                    auto characteristics = result.Characteristics();
                    
                    std::lock_guard<std::mutex> lock(m_connectionsMutex);
                    auto it = m_connections.find(address);
                    if (it == m_connections.end()) return;
                    
                    for (auto characteristic : characteristics) {
                        auto uuid = characteristic.Uuid();
                        it->second->characteristics[uuid] = characteristic;
                        
                        // Get properties
                        int properties = 0;
                        auto charProps = characteristic.CharacteristicProperties();
                        if ((charProps & GattCharacteristicProperties::Broadcast) != GattCharacteristicProperties::None) properties |= 0x01;
                        if ((charProps & GattCharacteristicProperties::Read) != GattCharacteristicProperties::None) properties |= 0x02;
                        if ((charProps & GattCharacteristicProperties::WriteWithoutResponse) != GattCharacteristicProperties::None) properties |= 0x04;
                        if ((charProps & GattCharacteristicProperties::Write) != GattCharacteristicProperties::None) properties |= 0x08;
                        if ((charProps & GattCharacteristicProperties::Notify) != GattCharacteristicProperties::None) properties |= 0x10;
                        if ((charProps & GattCharacteristicProperties::Indicate) != GattCharacteristicProperties::None) properties |= 0x20;
                        
                        // Notify Java
                        JNIEnv* env = nullptr;
                        if (m_jvm->AttachCurrentThread((void**)&env, nullptr) == JNI_OK) {
                            wchar_t serviceUuidStr[40], charUuidStr[40];
                            swprintf_s(serviceUuidStr, 40, L"%08x-%04x-%04x-%02x%02x-%02x%02x%02x%02x%02x%02x",
                                     serviceGuid.Data1, serviceGuid.Data2, serviceGuid.Data3,
                                     serviceGuid.Data4[0], serviceGuid.Data4[1], serviceGuid.Data4[2], serviceGuid.Data4[3],
                                     serviceGuid.Data4[4], serviceGuid.Data4[5], serviceGuid.Data4[6], serviceGuid.Data4[7]);
                            
                            swprintf_s(charUuidStr, 40, L"%08x-%04x-%04x-%02x%02x-%02x%02x%02x%02x%02x%02x",
                                     uuid.Data1, uuid.Data2, uuid.Data3,
                                     uuid.Data4[0], uuid.Data4[1], uuid.Data4[2], uuid.Data4[3],
                                     uuid.Data4[4], uuid.Data4[5], uuid.Data4[6], uuid.Data4[7]);
                            
                            std::string svcUuid = wstringToString(serviceUuidStr);
                            std::string chrUuid = wstringToString(charUuidStr);
                            
                            jclass cls = env->GetObjectClass(m_javaObject);
                            jmethodID mid = env->GetMethodID(cls, "onCharacteristicDiscovered", 
                                                            "(JLjava/lang/String;Ljava/lang/String;I)V");
                            if (mid != nullptr) {
                                jstring jServiceUuid = env->NewStringUTF(svcUuid.c_str());
                                jstring jCharUuid = env->NewStringUTF(chrUuid.c_str());
                                env->CallVoidMethod(m_javaObject, mid, (jlong)address, 
                                                  jServiceUuid, jCharUuid, (jint)properties);
                                env->DeleteLocalRef(jServiceUuid);
                                env->DeleteLocalRef(jCharUuid);
                            }
                            
                            env->DeleteLocalRef(cls);
                            m_jvm->DetachCurrentThread();
                        }
                    }
                }
            } catch (...) {
                // Ignore errors
            }
        }
    });
}

void BluetoothLEManager::readCharacteristic(uint64_t address, const std::wstring& characteristicUuidStr) {
    std::lock_guard<std::mutex> lock(m_connectionsMutex);
    
    auto it = m_connections.find(address);
    if (it == m_connections.end()) return;
    
    winrt::guid charGuid = winrt::guid(characteristicUuidStr);
    auto charIt = it->second->characteristics.find(charGuid);
    if (charIt == it->second->characteristics.end()) return;
    
    auto characteristic = charIt->second;
    
    // Read value asynchronously
    auto asyncOp = characteristic.ReadValueAsync();
    asyncOp.Completed([this, address, characteristicUuidStr](auto&& asyncInfo, AsyncStatus status) {
        if (status == AsyncStatus::Completed) {
            try {
                auto result = asyncInfo.GetResults();
                if (result.Status() == GattCommunicationStatus::Success) {
                    auto value = result.Value();
                    
                    // Convert IBuffer to byte array
                    auto reader = Windows::Storage::Streams::DataReader::FromBuffer(value);
                    std::vector<uint8_t> data(value.Length());
                    if (value.Length() > 0) {
                        reader.ReadBytes(data);
                    }
                    
                    // Notify Java
                    JNIEnv* env = nullptr;
                    if (m_jvm->AttachCurrentThread((void**)&env, nullptr) == JNI_OK) {
                        std::string uuidStr = wstringToString(characteristicUuidStr);
                        jstring jUuid = env->NewStringUTF(uuidStr.c_str());
                        jbyteArray jData = env->NewByteArray(data.size());
                        env->SetByteArrayRegion(jData, 0, data.size(), (jbyte*)data.data());
                        
                        jclass cls = env->GetObjectClass(m_javaObject);
                        jmethodID mid = env->GetMethodID(cls, "onCharacteristicRead", 
                                                        "(JLjava/lang/String;[B)V");
                        if (mid != nullptr) {
                            env->CallVoidMethod(m_javaObject, mid, (jlong)address, jUuid, jData);
                        }
                        
                        env->DeleteLocalRef(jUuid);
                        env->DeleteLocalRef(jData);
                        env->DeleteLocalRef(cls);
                        m_jvm->DetachCurrentThread();
                    }
                }
            } catch (...) {
                // Ignore errors
            }
        }
    });
}

void BluetoothLEManager::writeCharacteristic(uint64_t address, const std::wstring& characteristicUuidStr,
                                             const uint8_t* data, size_t length, bool withResponse) {
    std::lock_guard<std::mutex> lock(m_connectionsMutex);
    
    auto it = m_connections.find(address);
    if (it == m_connections.end()) return;
    
    winrt::guid charGuid = winrt::guid(characteristicUuidStr);
    auto charIt = it->second->characteristics.find(charGuid);
    if (charIt == it->second->characteristics.end()) return;
    
    auto characteristic = charIt->second;
    
    // Create IBuffer from data
    auto writer = Windows::Storage::Streams::DataWriter();
    writer.WriteBytes(array_view<const uint8_t>(data, data + length));
    auto buffer = writer.DetachBuffer();
    
    // Write value asynchronously
    GattWriteOption writeOption = withResponse ? 
        GattWriteOption::WriteWithResponse : GattWriteOption::WriteWithoutResponse;
    
    auto asyncOp = characteristic.WriteValueAsync(buffer, writeOption);
    asyncOp.Completed([this, address, characteristicUuidStr](auto&& asyncInfo, AsyncStatus status) {
        bool success = (status == AsyncStatus::Completed);
        if (success) {
            try {
                auto result = asyncInfo.GetResults();
                success = (result == GattCommunicationStatus::Success);
            } catch (...) {
                success = false;
            }
        }
        
        // Notify Java
        JNIEnv* env = nullptr;
        if (m_jvm->AttachCurrentThread((void**)&env, nullptr) == JNI_OK) {
            std::string uuidStr = wstringToString(characteristicUuidStr);
            jstring jUuid = env->NewStringUTF(uuidStr.c_str());
            
            jclass cls = env->GetObjectClass(m_javaObject);
            jmethodID mid = env->GetMethodID(cls, "onCharacteristicWritten", 
                                            "(JLjava/lang/String;Z)V");
            if (mid != nullptr) {
                env->CallVoidMethod(m_javaObject, mid, (jlong)address, jUuid, (jboolean)success);
            }
            
            env->DeleteLocalRef(jUuid);
            env->DeleteLocalRef(cls);
            m_jvm->DetachCurrentThread();
        }
    });
}

void BluetoothLEManager::setNotify(uint64_t address, const std::wstring& characteristicUuidStr, bool enable) {
    std::lock_guard<std::mutex> lock(m_connectionsMutex);
    
    auto it = m_connections.find(address);
    if (it == m_connections.end()) return;
    
    winrt::guid charGuid = winrt::guid(characteristicUuidStr);
    auto charIt = it->second->characteristics.find(charGuid);
    if (charIt == it->second->characteristics.end()) return;
    
    auto characteristic = charIt->second;
    
    GattClientCharacteristicConfigurationDescriptorValue value = enable ?
        GattClientCharacteristicConfigurationDescriptorValue::Notify :
        GattClientCharacteristicConfigurationDescriptorValue::None;
    
    auto asyncOp = characteristic.WriteClientCharacteristicConfigurationDescriptorAsync(value);
    asyncOp.Completed([this, address, characteristicUuidStr, enable](auto&& asyncInfo, AsyncStatus status) {
        if (status == AsyncStatus::Completed && enable) {
            try {
                auto result = asyncInfo.GetResults();
                if (result == GattCommunicationStatus::Success) {
                    std::lock_guard<std::mutex> lock(m_connectionsMutex);
                    auto it = m_connections.find(address);
                    if (it == m_connections.end()) return;
                    
                    winrt::guid charGuid = winrt::guid(characteristicUuidStr);
                    auto charIt = it->second->characteristics.find(charGuid);
                    if (charIt == it->second->characteristics.end()) return;
                    
                    auto characteristic = charIt->second;
                    
                    // Register for value changed events
                    characteristic.ValueChanged([this, address, characteristicUuidStr]
                        (GattCharacteristic characteristic, GattValueChangedEventArgs args) {
                        auto value = args.CharacteristicValue();
                        
                        // Convert IBuffer to byte array
                        auto reader = Windows::Storage::Streams::DataReader::FromBuffer(value);
                        std::vector<uint8_t> data(value.Length());
                        if (value.Length() > 0) {
                            reader.ReadBytes(data);
                        }
                        
                        // Notify Java
                        JNIEnv* env = nullptr;
                        if (m_jvm->AttachCurrentThread((void**)&env, nullptr) == JNI_OK) {
                            std::string uuidStr = wstringToString(characteristicUuidStr);
                            jstring jUuid = env->NewStringUTF(uuidStr.c_str());
                            jbyteArray jData = env->NewByteArray(data.size());
                            env->SetByteArrayRegion(jData, 0, data.size(), (jbyte*)data.data());
                            
                            jclass cls = env->GetObjectClass(m_javaObject);
                            jmethodID mid = env->GetMethodID(cls, "onCharacteristicChanged", 
                                                            "(JLjava/lang/String;[B)V");
                            if (mid != nullptr) {
                                env->CallVoidMethod(m_javaObject, mid, (jlong)address, jUuid, jData);
                            }
                            
                            env->DeleteLocalRef(jUuid);
                            env->DeleteLocalRef(jData);
                            env->DeleteLocalRef(cls);
                            m_jvm->DetachCurrentThread();
                        }
                    });
                }
            } catch (...) {
                // Ignore errors
            }
        }
    });
}

void BluetoothLEManager::setIndicate(uint64_t address, const std::wstring& characteristicUuidStr, bool enable) {
    std::lock_guard<std::mutex> lock(m_connectionsMutex);
    
    auto it = m_connections.find(address);
    if (it == m_connections.end()) return;
    
    winrt::guid charGuid = winrt::guid(characteristicUuidStr);
    auto charIt = it->second->characteristics.find(charGuid);
    if (charIt == it->second->characteristics.end()) return;
    
    auto characteristic = charIt->second;
    
    GattClientCharacteristicConfigurationDescriptorValue value = enable ?
        GattClientCharacteristicConfigurationDescriptorValue::Indicate :
        GattClientCharacteristicConfigurationDescriptorValue::None;
    
    auto asyncOp = characteristic.WriteClientCharacteristicConfigurationDescriptorAsync(value);
    asyncOp.Completed([](auto&& asyncInfo, AsyncStatus status) {
        // Just complete, notification handling is the same as notify
    });
}

void BluetoothLEManager::readDescriptor(uint64_t address, const std::wstring& descriptorUuidStr) {
    // Descriptor read would require more complex tracking of descriptors
    // This is a simplified stub
}

void BluetoothLEManager::writeDescriptor(uint64_t address, const std::wstring& descriptorUuidStr,
                                        const uint8_t* data, size_t length) {
    // Descriptor write would require more complex tracking of descriptors
    // This is a simplified stub
}

void BluetoothLEManager::changeMTU(uint64_t address, int mtu) {
    // Windows automatically negotiates MTU
    // We can notify Java with the current MTU
    std::lock_guard<std::mutex> lock(m_connectionsMutex);
    
    auto it = m_connections.find(address);
    if (it == m_connections.end() || it->second->device == nullptr) return;
    
    // Windows BLE MTU is typically negotiated automatically
    // Default is 23 bytes for BLE 4.0, up to 517 for BLE 4.2+
    // We'll just notify with requested value for now
    
    JNIEnv* env = nullptr;
    if (m_jvm->AttachCurrentThread((void**)&env, nullptr) == JNI_OK) {
        jclass cls = env->GetObjectClass(m_javaObject);
        jmethodID mid = env->GetMethodID(cls, "onMtuChanged", "(JI)V");
        if (mid != nullptr) {
            env->CallVoidMethod(m_javaObject, mid, (jlong)address, (jint)mtu);
        }
        
        env->DeleteLocalRef(cls);
        m_jvm->DetachCurrentThread();
    }
}

// Helper methods
std::wstring BluetoothLEManager::stringToWString(const std::string& str) {
    std::wstring_convert<std::codecvt_utf8_utf16<wchar_t>> converter;
    return converter.from_bytes(str);
}

std::string BluetoothLEManager::wstringToString(const std::wstring& wstr) {
    std::wstring_convert<std::codecvt_utf8_utf16<wchar_t>> converter;
    return converter.to_bytes(wstr);
}

uint64_t BluetoothLEManager::bluetoothAddressToUint64(uint64_t address) {
    return address;
}
