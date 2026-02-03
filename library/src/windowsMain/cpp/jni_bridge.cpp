#include "BluetoothLEManager.h"
#include <jni.h>

// JNI method implementations
extern "C" {

JNIEXPORT void JNICALL
Java_dev_bluefalcon_BlueFalcon_nativeInitialize(JNIEnv* env, jobject thiz) {
    JavaVM* jvm;
    env->GetJavaVM(&jvm);
    BluetoothLEManager::getInstance().initialize(jvm, thiz);
}

JNIEXPORT void JNICALL
Java_dev_bluefalcon_BlueFalcon_nativeScan(JNIEnv* env, jobject thiz, jobjectArray serviceUuids) {
    BluetoothLEManager::getInstance().startScan(env, serviceUuids);
}

JNIEXPORT void JNICALL
Java_dev_bluefalcon_BlueFalcon_nativeStopScan(JNIEnv* env, jobject thiz) {
    BluetoothLEManager::getInstance().stopScan();
}

JNIEXPORT void JNICALL
Java_dev_bluefalcon_BlueFalcon_nativeConnect(JNIEnv* env, jobject thiz, jlong address) {
    BluetoothLEManager::getInstance().connect(static_cast<uint64_t>(address));
}

JNIEXPORT void JNICALL
Java_dev_bluefalcon_BlueFalcon_nativeDisconnect(JNIEnv* env, jobject thiz, jlong address) {
    BluetoothLEManager::getInstance().disconnect(static_cast<uint64_t>(address));
}

JNIEXPORT jint JNICALL
Java_dev_bluefalcon_BlueFalcon_nativeGetConnectionState(JNIEnv* env, jobject thiz, jlong address) {
    return BluetoothLEManager::getInstance().getConnectionState(static_cast<uint64_t>(address));
}

JNIEXPORT void JNICALL
Java_dev_bluefalcon_BlueFalcon_nativeDiscoverServices(JNIEnv* env, jobject thiz, jlong address) {
    BluetoothLEManager::getInstance().discoverServices(static_cast<uint64_t>(address));
}

JNIEXPORT void JNICALL
Java_dev_bluefalcon_BlueFalcon_nativeDiscoverCharacteristics(JNIEnv* env, jobject thiz, 
                                                             jlong address, jstring serviceUuid) {
    const char* uuidStr = env->GetStringUTFChars(serviceUuid, nullptr);
    std::wstring wUuid = BluetoothLEManager::stringToWString(std::string(uuidStr));
    
    BluetoothLEManager::getInstance().discoverCharacteristics(static_cast<uint64_t>(address), wUuid);
    
    env->ReleaseStringUTFChars(serviceUuid, uuidStr);
}

JNIEXPORT void JNICALL
Java_dev_bluefalcon_BlueFalcon_nativeReadCharacteristic(JNIEnv* env, jobject thiz, 
                                                        jlong address, jstring characteristicUuid) {
    const char* uuidStr = env->GetStringUTFChars(characteristicUuid, nullptr);
    std::wstring wUuid = BluetoothLEManager::stringToWString(std::string(uuidStr));
    
    BluetoothLEManager::getInstance().readCharacteristic(static_cast<uint64_t>(address), wUuid);
    
    env->ReleaseStringUTFChars(characteristicUuid, uuidStr);
}

JNIEXPORT void JNICALL
Java_dev_bluefalcon_BlueFalcon_nativeWriteCharacteristic(JNIEnv* env, jobject thiz, 
                                                         jlong address, jstring characteristicUuid,
                                                         jbyteArray value, jboolean withResponse) {
    const char* uuidStr = env->GetStringUTFChars(characteristicUuid, nullptr);
    std::wstring wUuid = BluetoothLEManager::stringToWString(std::string(uuidStr));
    
    jsize length = env->GetArrayLength(value);
    jbyte* data = env->GetByteArrayElements(value, nullptr);
    
    BluetoothLEManager::getInstance().writeCharacteristic(
        static_cast<uint64_t>(address), wUuid, 
        reinterpret_cast<const uint8_t*>(data), length, withResponse);
    
    env->ReleaseByteArrayElements(value, data, JNI_ABORT);
    env->ReleaseStringUTFChars(characteristicUuid, uuidStr);
}

JNIEXPORT void JNICALL
Java_dev_bluefalcon_BlueFalcon_nativeSetNotify(JNIEnv* env, jobject thiz, 
                                               jlong address, jstring characteristicUuid, jboolean enable) {
    const char* uuidStr = env->GetStringUTFChars(characteristicUuid, nullptr);
    std::wstring wUuid = BluetoothLEManager::stringToWString(std::string(uuidStr));
    
    BluetoothLEManager::getInstance().setNotify(static_cast<uint64_t>(address), wUuid, enable);
    
    env->ReleaseStringUTFChars(characteristicUuid, uuidStr);
}

JNIEXPORT void JNICALL
Java_dev_bluefalcon_BlueFalcon_nativeSetIndicate(JNIEnv* env, jobject thiz, 
                                                 jlong address, jstring characteristicUuid, jboolean enable) {
    const char* uuidStr = env->GetStringUTFChars(characteristicUuid, nullptr);
    std::wstring wUuid = BluetoothLEManager::stringToWString(std::string(uuidStr));
    
    BluetoothLEManager::getInstance().setIndicate(static_cast<uint64_t>(address), wUuid, enable);
    
    env->ReleaseStringUTFChars(characteristicUuid, uuidStr);
}

JNIEXPORT void JNICALL
Java_dev_bluefalcon_BlueFalcon_nativeReadDescriptor(JNIEnv* env, jobject thiz, 
                                                    jlong address, jstring descriptorUuid) {
    const char* uuidStr = env->GetStringUTFChars(descriptorUuid, nullptr);
    std::wstring wUuid = BluetoothLEManager::stringToWString(std::string(uuidStr));
    
    BluetoothLEManager::getInstance().readDescriptor(static_cast<uint64_t>(address), wUuid);
    
    env->ReleaseStringUTFChars(descriptorUuid, uuidStr);
}

JNIEXPORT void JNICALL
Java_dev_bluefalcon_BlueFalcon_nativeWriteDescriptor(JNIEnv* env, jobject thiz, 
                                                     jlong address, jstring descriptorUuid, jbyteArray value) {
    const char* uuidStr = env->GetStringUTFChars(descriptorUuid, nullptr);
    std::wstring wUuid = BluetoothLEManager::stringToWString(std::string(uuidStr));
    
    jsize length = env->GetArrayLength(value);
    jbyte* data = env->GetByteArrayElements(value, nullptr);
    
    BluetoothLEManager::getInstance().writeDescriptor(
        static_cast<uint64_t>(address), wUuid, 
        reinterpret_cast<const uint8_t*>(data), length);
    
    env->ReleaseByteArrayElements(value, data, JNI_ABORT);
    env->ReleaseStringUTFChars(descriptorUuid, uuidStr);
}

JNIEXPORT void JNICALL
Java_dev_bluefalcon_BlueFalcon_nativeChangeMTU(JNIEnv* env, jobject thiz, jlong address, jint mtu) {
    BluetoothLEManager::getInstance().changeMTU(static_cast<uint64_t>(address), mtu);
}

} // extern "C"
