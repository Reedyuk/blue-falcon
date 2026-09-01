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
    if (m_radioStateChangedToken.value != 0 && m_bluetoothRadio != nullptr) {
        m_bluetoothRadio.StateChanged(m_radioStateChangedToken);
        m_radioStateChangedToken = event_token{};
    }
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

    initializeRadioMonitoring();
    JNIEnv* initEnv = nullptr;
    if (jvm->GetEnv((void**)&initEnv, JNI_VERSION_1_6) == JNI_OK && initEnv != nullptr) {
        enumerateAdapters(initEnv);
    }
}

void BluetoothLEManager::initializeRadioMonitoring() {
    try {
        // Find the first Bluetooth radio and subscribe to its power state so we can
        // reflect the OS-level adapter on/off state as BluetoothManagerState, instead
        // of only reporting Ready/NotReady once at native-library load time.
        auto radiosOp = Radio::GetRadiosAsync();
        radiosOp.Completed([this](auto&& asyncInfo, AsyncStatus status) {
            if (status != AsyncStatus::Completed) return;
            try {
                auto radios = asyncInfo.GetResults();
                RadioInfo selected = getSelectedAdapter();
                bool hasSelectedAdapter = !selected.id.empty();
                for (auto radio : radios) {
                    if (radio.Kind() != RadioKind::Bluetooth) continue;
                    if (hasSelectedAdapter && radio.DeviceId() == selected.id) {
                        SRWLockGuard lock(&m_radiosMutex);
                        m_bluetoothRadio = radio;
                        break;
                    }
                    if (m_bluetoothRadio == nullptr) {
                        SRWLockGuard lock(&m_radiosMutex);
                        m_bluetoothRadio = radio;
                    }
                }
                if (m_bluetoothRadio == nullptr) {
                    notifyManagerStateChanged(false);
                    return;
                }

                notifyManagerStateChanged(
                    m_bluetoothRadio.State() == RadioState::On
                );

                m_radioStateChangedToken = m_bluetoothRadio.StateChanged(
                    [this](Radio radio, auto&& args) {
                        notifyManagerStateChanged(radio.State() == RadioState::On);
                    });
            } catch (...) {
                notifyManagerStateChanged(false);
            }
        });
    } catch (...) {
        notifyManagerStateChanged(false);
    }
}

void BluetoothLEManager::notifyManagerStateChanged(bool ready) {
    m_lastReportedRadioReady = ready;

    JNIEnv* env = nullptr;
    if (m_jvm->AttachCurrentThread((void**)&env, nullptr) == JNI_OK) {
        jclass cls = env->GetObjectClass(m_javaObject);
        jmethodID mid = env->GetMethodID(cls, "onAdapterStateChanged", "(Z)V");
        if (mid != nullptr) {
            env->CallVoidMethod(m_javaObject, mid, (jboolean)ready);
        }
        env->DeleteLocalRef(cls);
        m_jvm->DetachCurrentThread();
    }
}

void BluetoothLEManager::notifyConnectionStateChanged(uint64_t address, int state) {
    JNIEnv* env = nullptr;
    if (m_jvm->AttachCurrentThread((void**)&env, nullptr) == JNI_OK) {
        jclass cls = env->GetObjectClass(m_javaObject);
        jmethodID mid = env->GetMethodID(cls, "onConnectionStateChanged", "(JI)V");
        if (mid != nullptr) {
            env->CallVoidMethod(m_javaObject, mid, (jlong)address, (jint)state);
        }
        env->DeleteLocalRef(cls);
        m_jvm->DetachCurrentThread();
    }
}

void BluetoothLEManager::subscribeToDeviceConnectionStatus(std::shared_ptr<DeviceConnection> connection) {
    if (connection->device == nullptr) return;

    uint64_t address = connection->address;
    connection->connectionStatusChangedToken = connection->device.ConnectionStatusChanged(
        [this, address](BluetoothLEDevice device, auto&& args) {
            int newState;
            if (device.ConnectionStatus() == BluetoothConnectionStatus::Connected) {
                newState = 2; // Connected
            } else {
                newState = 0; // Disconnected
            }

            {
                SRWLockGuard lock(&m_connectionsMutex);
                auto it = m_connections.find(address);
                if (it == m_connections.end()) return;
                if (it->second->connectionState == newState) return;
                it->second->connectionState = newState;
                if (newState == 0) {
                    // The remote device (or its own radio) dropped the link outside of
                    // our own disconnect() call - e.g. the peripheral's Bluetooth was
                    // turned off. Clear cached GATT state so a future connect() starts
                    // clean, but keep the map entry removed only after notifying Java.
                    if (it->second->device != nullptr) {
                        it->second->device.ConnectionStatusChanged(
                            it->second->connectionStatusChangedToken);
                    }
                    m_connections.erase(it);
                }
            }

            notifyConnectionStateChanged(address, newState);
        });
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

            // Build manufacturer data bytes: [companyId LSB, companyId MSB, payload...]
            // Use the first manufacturer-specific data entry if present.
            std::vector<uint8_t> mfrBytes;
            auto mfrDataList = args.Advertisement().ManufacturerData();
            if (mfrDataList.Size() > 0) {
                auto entry = mfrDataList.GetAt(0);
                uint16_t companyId = entry.CompanyId();
                mfrBytes.push_back(static_cast<uint8_t>(companyId & 0xFF));
                mfrBytes.push_back(static_cast<uint8_t>((companyId >> 8) & 0xFF));
                auto dataBuffer = entry.Data();
                auto dataReader = winrt::Windows::Storage::Streams::DataReader::FromBuffer(dataBuffer);
                while (dataReader.UnconsumedBufferLength() > 0) {
                    mfrBytes.push_back(dataReader.ReadByte());
                }
            }
            
            // Call Java callback
            JNIEnv* env = nullptr;
            if (m_jvm->AttachCurrentThread((void**)&env, nullptr) == JNI_OK) {
                jclass cls = env->GetObjectClass(m_javaObject);
                jmethodID mid = env->GetMethodID(cls, "onDeviceDiscovered", "(JLjava/lang/String;FZ[B)V");
                
                if (mid != nullptr) {
                    jstring jName = deviceName.empty() ? nullptr : 
                        env->NewStringUTF(deviceName.c_str());
                    jbyteArray jMfr = nullptr;
                    if (!mfrBytes.empty()) {
                        jMfr = env->NewByteArray(static_cast<jsize>(mfrBytes.size()));
                        if (jMfr) {
                            env->SetByteArrayRegion(jMfr, 0, static_cast<jsize>(mfrBytes.size()),
                                                    reinterpret_cast<const jbyte*>(mfrBytes.data()));
                        }
                    }
                    env->CallVoidMethod(m_javaObject, mid, (jlong)address, jName,
                                      (jfloat)rssi, (jboolean)isConnectable, jMfr);
                    if (jName) env->DeleteLocalRef(jName);
                    if (jMfr)  env->DeleteLocalRef(jMfr);
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
    SRWLockGuard lock(&m_connectionsMutex);

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
        std::shared_ptr<DeviceConnection> connectedConnection;
        {
            SRWLockGuard lock(&m_connectionsMutex);
            auto it = m_connections.find(address);
            if (it == m_connections.end()) return;

            if (status == AsyncStatus::Completed) {
                try {
                    it->second->device = asyncInfo.GetResults();
                    it->second->connectionState = 2; // Connected
                    connectedConnection = it->second;
                } catch (...) {
                    m_connections.erase(it);
                    return;
                }
            } else {
                m_connections.erase(it);
                return;
            }
        }

        // Subscribe outside the lock so the callback (which re-acquires the lock)
        // can never deadlock against this completion handler.
        if (connectedConnection) {
            subscribeToDeviceConnectionStatus(connectedConnection);
        }
    });
}

void BluetoothLEManager::disconnect(uint64_t address) {
    SRWLockGuard lock(&m_connectionsMutex);

    auto it = m_connections.find(address);
    if (it != m_connections.end()) {
        it->second->connectionState = 3; // Disconnecting
        
        // Close device
        if (it->second->device != nullptr) {
            it->second->device.ConnectionStatusChanged(it->second->connectionStatusChangedToken);
            it->second->device.Close();
            it->second->device = nullptr;
        }
        
        it->second->connectionState = 0; // Disconnected
        m_connections.erase(it);
    }
}

int BluetoothLEManager::getConnectionState(uint64_t address) {
    SRWLockGuard lock(&m_connectionsMutex);

    auto it = m_connections.find(address);
    if (it != m_connections.end()) {
        return it->second->connectionState;
    }
    
    return 0; // Disconnected
}

void BluetoothLEManager::discoverServices(uint64_t address) {
    SRWLockGuard lock(&m_connectionsMutex);

    auto it = m_connections.find(address);
    if (it == m_connections.end() || it->second->device == nullptr) {
        return;
    }
    
    auto device = it->second->device;

    // Uncached forces a live GATT query to the device. The default Cached mode
    // returns empty results on fresh connections because the OS cache has not
    // yet been populated, causing service discovery to silently report 0 services.
    auto asyncOp = device.GetGattServicesAsync(BluetoothCacheMode::Uncached);
    asyncOp.Completed([this, address](auto&& asyncInfo, AsyncStatus status) {
        if (status == AsyncStatus::Completed) {
            try {
                auto result = asyncInfo.GetResults();
                if (result.Status() == GattCommunicationStatus::Success) {
                    auto services = result.Services();

                    SRWLockGuard lock(&m_connectionsMutex);
                    auto it = m_connections.find(address);
                    if (it == m_connections.end()) return;
                    
                    // Store services and collect UUIDs
                    std::vector<std::wstring> serviceUuids;
                    for (auto service : services) {
                        auto uuid = service.Uuid();
                        it->second->services.insert_or_assign(uuid, service);

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
    SRWLockGuard lock(&m_connectionsMutex);

    auto it = m_connections.find(address);
    if (it == m_connections.end()) return;
    
    // Find service by UUID
    winrt::guid serviceGuid = winrt::guid(serviceUuidStr);
    auto serviceIt = it->second->services.find(serviceGuid);
    if (serviceIt == it->second->services.end()) return;
    
    auto service = serviceIt->second;

    // Uncached forces a live GATT query. See discoverServices for rationale.
    auto asyncOp = service.GetCharacteristicsAsync(BluetoothCacheMode::Uncached);
    asyncOp.Completed([this, address, serviceGuid](auto&& asyncInfo, AsyncStatus status) {
        if (status == AsyncStatus::Completed) {
            try {
                auto result = asyncInfo.GetResults();
                if (result.Status() == GattCommunicationStatus::Success) {
                    auto characteristics = result.Characteristics();

                    SRWLockGuard lock(&m_connectionsMutex);
                    auto it = m_connections.find(address);
                    if (it == m_connections.end()) return;
                    
                    for (auto characteristic : characteristics) {
                        auto uuid = characteristic.Uuid();
                        it->second->characteristics.insert_or_assign(uuid, characteristic);

                        // Get properties
                        int properties = 0;
                        auto charProps = characteristic.CharacteristicProperties();
                        if ((charProps & GattCharacteristicProperties::Broadcast) != GattCharacteristicProperties::None) properties |= 0x01;
                        if ((charProps & GattCharacteristicProperties::Read) != GattCharacteristicProperties::None) properties |= 0x02;
                        if ((charProps & GattCharacteristicProperties::WriteWithoutResponse) != GattCharacteristicProperties::None) properties |= 0x04;
                        if ((charProps & GattCharacteristicProperties::Write) != GattCharacteristicProperties::None) properties |= 0x08;
                        if ((charProps & GattCharacteristicProperties::Notify) != GattCharacteristicProperties::None) properties |= 0x10;
                        if ((charProps & GattCharacteristicProperties::Indicate) != GattCharacteristicProperties::None) properties |= 0x20;
                        
                        // Notify Java per-characteristic
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

                    // Notify Java that all characteristics for this service are ready.
                    JNIEnv* env = nullptr;
                    if (m_jvm->AttachCurrentThread((void**)&env, nullptr) == JNI_OK) {
                        wchar_t serviceUuidStr[40];
                        swprintf_s(serviceUuidStr, 40, L"%08x-%04x-%04x-%02x%02x-%02x%02x%02x%02x%02x%02x",
                                 serviceGuid.Data1, serviceGuid.Data2, serviceGuid.Data3,
                                 serviceGuid.Data4[0], serviceGuid.Data4[1], serviceGuid.Data4[2], serviceGuid.Data4[3],
                                 serviceGuid.Data4[4], serviceGuid.Data4[5], serviceGuid.Data4[6], serviceGuid.Data4[7]);
                        std::string svcUuid = wstringToString(serviceUuidStr);

                        jclass cls = env->GetObjectClass(m_javaObject);
                        jmethodID mid = env->GetMethodID(cls, "onCharacteristicsDiscoveredForService",
                                                        "(JLjava/lang/String;)V");
                        if (mid != nullptr) {
                            jstring jSvcUuid = env->NewStringUTF(svcUuid.c_str());
                            env->CallVoidMethod(m_javaObject, mid, (jlong)address, jSvcUuid);
                            env->DeleteLocalRef(jSvcUuid);
                        }
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

void BluetoothLEManager::readCharacteristic(uint64_t address, const std::wstring& characteristicUuidStr) {
    SRWLockGuard lock(&m_connectionsMutex);

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
    SRWLockGuard lock(&m_connectionsMutex);

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
    SRWLockGuard lock(&m_connectionsMutex);

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
                    SRWLockGuard lock(&m_connectionsMutex);
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
    SRWLockGuard lock(&m_connectionsMutex);

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
    SRWLockGuard lock(&m_connectionsMutex);

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


jobjectArray BluetoothLEManager::enumerateAdapters(JNIEnv* env) {
    std::vector<RadioInfo> radiosInfo;
    try {
        auto radios = Radio::GetRadiosAsync().get();
        bool defaultAssigned = false;
        for (auto const& radio : radios) {
            if (radio.Kind() != RadioKind::Bluetooth) continue;

            RadioInfo info;
            info.id = radio.DeviceId().c_str();
            info.name = radio.Name().c_str();
            info.address = L"";
            info.isLowEnergySupported = true;
            info.isDefault = !defaultAssigned;
            defaultAssigned = true;

            try {
                auto adapter = BluetoothAdapter::FromIdAsync(radio.DeviceId()).get();
                if (adapter != nullptr) {
                    auto addressValue = adapter.BluetoothAddress();
                    if (addressValue != 0) {
                        std::wstringstream ss;
                        ss << std::uppercase << std::hex << std::setfill(L'0');
                        for (int i = 5; i >= 0; --i) {
                            ss << std::setw(2) << ((addressValue >> (i * 8)) & 0xFF);
                            if (i > 0) ss << L":";
                        }
                        info.address = ss.str();
                    }
                    info.isLowEnergySupported = adapter.IsLowEnergySupported();
                }
            } catch (...) {
            }

            radiosInfo.push_back(info);
        }
    } catch (...) {
    }

    {
        SRWLockGuard lock(&m_radiosMutex);
        m_radios = radiosInfo;
        if (m_radios.empty()) {
            m_selectedRadioIndex = -1;
            m_bluetoothRadio = nullptr;
        } else if (m_selectedRadioIndex < 0 || m_selectedRadioIndex >= static_cast<int>(m_radios.size())) {
            auto selectedIt = std::find_if(m_radios.begin(), m_radios.end(), [](const RadioInfo& info) { return info.isDefault; });
            m_selectedRadioIndex = selectedIt != m_radios.end() ? static_cast<int>(selectedIt - m_radios.begin()) : 0;
        }
    }

    jclass adapterClass = env->FindClass("dev/bluefalcon/engine/windows/BluetoothAdapterData");
    if (adapterClass == nullptr) return nullptr;

    jmethodID ctor = env->GetMethodID(adapterClass, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZZ)V");
    if (ctor == nullptr) {
        env->DeleteLocalRef(adapterClass);
        return nullptr;
    }

    jobjectArray result = env->NewObjectArray(static_cast<jsize>(radiosInfo.size()), adapterClass, nullptr);
    for (jsize i = 0; i < static_cast<jsize>(radiosInfo.size()); ++i) {
        const auto& info = radiosInfo[i];
        jstring id = env->NewStringUTF(wstringToString(info.id).c_str());
        jstring name = env->NewStringUTF(wstringToString(info.name).c_str());
        jstring address = info.address.empty() ? nullptr : env->NewStringUTF(wstringToString(info.address).c_str());
        jobject adapter = env->NewObject(adapterClass, ctor, id, name, address, static_cast<jboolean>(info.isDefault), static_cast<jboolean>(info.isLowEnergySupported));
        env->SetObjectArrayElement(result, i, adapter);
        env->DeleteLocalRef(id);
        env->DeleteLocalRef(name);
        if (address) env->DeleteLocalRef(address);
        env->DeleteLocalRef(adapter);
    }
    env->DeleteLocalRef(adapterClass);
    return result;
}

int BluetoothLEManager::selectAdapter(JNIEnv* env, const std::string& adapterId) {
    auto requestedId = stringToWString(adapterId);
    jobjectArray refreshed = enumerateAdapters(env);
    if (refreshed != nullptr) {
        env->DeleteLocalRef(refreshed);
    }

    std::wstring selectedId;
    {
        SRWLockGuard lock(&m_radiosMutex);
        auto it = std::find_if(m_radios.begin(), m_radios.end(), [&](const RadioInfo& info) { return info.id == requestedId; });
        if (it == m_radios.end()) return 1;

        m_selectedRadioIndex = static_cast<int>(it - m_radios.begin());
        selectedId = it->id;
        for (size_t i = 0; i < m_radios.size(); ++i) {
            m_radios[i].isDefault = static_cast<int>(i) == m_selectedRadioIndex;
        }
    }

    // Release lock before blocking WinRT call to avoid deadlock
    try {
        auto radios = Radio::GetRadiosAsync().get();
        for (auto const& radio : radios) {
            if (radio.Kind() == RadioKind::Bluetooth && radio.DeviceId() == selectedId) {
                {
                    SRWLockGuard lock(&m_radiosMutex);
                    if (m_radioStateChangedToken.value != 0 && m_bluetoothRadio != nullptr) {
                        m_bluetoothRadio.StateChanged(m_radioStateChangedToken);
                        m_radioStateChangedToken = event_token{};
                    }
                    m_bluetoothRadio = radio;
                }
                notifyManagerStateChanged(m_bluetoothRadio.State() == RadioState::On);
                {
                    SRWLockGuard lock(&m_radiosMutex);
                    m_radioStateChangedToken = m_bluetoothRadio.StateChanged([this](Radio radio, auto&& args) {
                        notifyManagerStateChanged(radio.State() == RadioState::On);
                    });
                }
                return 0;
            }
        }
    } catch (...) {
        return 3;
    }
    return 2;
}

RadioInfo BluetoothLEManager::getSelectedAdapter() const {
    SRWSharedLockGuard lock(&m_radiosMutex);
    if (m_selectedRadioIndex < 0 || m_selectedRadioIndex >= static_cast<int>(m_radios.size())) {
        return RadioInfo{};  // Return default-constructed empty RadioInfo
    }
    return m_radios[m_selectedRadioIndex];  // Return a copy, not a pointer
}

// Helper methods
std::wstring BluetoothLEManager::stringToWString(const std::string& str) {
    if (str.empty()) return std::wstring();
    
    int size_needed = MultiByteToWideChar(CP_UTF8, 0, str.c_str(), (int)str.length(), nullptr, 0);
    std::wstring wstr(size_needed, 0);
    MultiByteToWideChar(CP_UTF8, 0, str.c_str(), (int)str.length(), &wstr[0], size_needed);
    return wstr;
}

std::string BluetoothLEManager::wstringToString(const std::wstring& wstr) {
    if (wstr.empty()) return std::string();
    
    int size_needed = WideCharToMultiByte(CP_UTF8, 0, wstr.c_str(), (int)wstr.length(), nullptr, 0, nullptr, nullptr);
    std::string str(size_needed, 0);
    WideCharToMultiByte(CP_UTF8, 0, wstr.c_str(), (int)wstr.length(), &str[0], size_needed, nullptr, nullptr);
    return str;
}

uint64_t BluetoothLEManager::bluetoothAddressToUint64(uint64_t address) {
    return address;
}
