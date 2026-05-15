#import <Foundation/Foundation.h>
#import <CoreBluetooth/CoreBluetooth.h>
#include <jni.h>
#include <unistd.h>

// Forward declaration
@interface BlueFalconBLEBridge : NSObject <CBCentralManagerDelegate, CBPeripheralDelegate>
@end

// Global state — single engine instance (sufficient for the single-process app model)
static JavaVM *gJvm = NULL;
static jobject gEngineRef = NULL;
static CBCentralManager *gCentralManager = NULL;
static BlueFalconBLEBridge *gBridge = NULL;
// All discovered/connected peripherals keyed by NSUUID string
static NSMutableDictionary<NSString*, CBPeripheral*> *gPeripherals = NULL;
static dispatch_queue_t gBLEQueue = NULL;

// ---------------------------------------------------------------------------
// L2CAP channel support
// ---------------------------------------------------------------------------

// Owns one CBL2CAPChannel and pumps its NSStream pair on a dedicated run loop.
@interface L2CapChannelHolder : NSObject <NSStreamDelegate>
@property (assign) jlong handle;
@property (retain) CBL2CAPChannel *channel;
@property (retain) NSInputStream *input;
@property (retain) NSOutputStream *output;
@property (retain) NSMutableData *outBuffer;
@property (assign) BOOL outputHasSpace;
@property (assign) BOOL torndown;
- (id)initWithHandle:(jlong)h channel:(CBL2CAPChannel *)channel;
- (void)scheduleStreams;
- (void)enqueueWrite:(NSData *)data;
- (void)teardown;
@end

// handle (jlong) -> L2CapChannelHolder
static NSMutableDictionary<NSNumber*, L2CapChannelHolder*> *gL2capChannels = NULL;
static jlong gL2capNextHandle = 1;
static NSThread *gL2capThread = NULL;
static NSRunLoop *gL2capRunLoop = NULL;

static void ensureL2capThread(void);

// ---------------------------------------------------------------------------
// JNI thread helpers
// ---------------------------------------------------------------------------

static JNIEnv* getEnv(jboolean *wasAttached) {
    *wasAttached = JNI_FALSE;
    if (!gJvm) return NULL;
    JNIEnv *env = NULL;
    jint ret = (*gJvm)->GetEnv(gJvm, (void **)&env, JNI_VERSION_1_6);
    if (ret == JNI_EDETACHED) {
        if ((*gJvm)->AttachCurrentThread(gJvm, (void **)&env, NULL) == JNI_OK) {
            *wasAttached = JNI_TRUE;
        } else {
            return NULL;
        }
    }
    return env;
}

static void releaseEnv(jboolean wasAttached) {
    if (wasAttached && gJvm) {
        (*gJvm)->DetachCurrentThread(gJvm);
    }
}

// ---------------------------------------------------------------------------
// Conversion helpers
// ---------------------------------------------------------------------------

static jstring toJString(JNIEnv *env, NSString *str) {
    if (!str) return NULL;
    return (*env)->NewStringUTF(env, [str UTF8String]);
}

static jbyteArray toJByteArray(JNIEnv *env, NSData *data) {
    if (!data) return NULL;
    jsize len = (jsize)[data length];
    jbyteArray arr = (*env)->NewByteArray(env, len);
    if (arr && len > 0) {
        (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *)[data bytes]);
    }
    return arr;
}

static NSString* toString(JNIEnv *env, jstring jstr) {
    if (!jstr) return nil;
    const char *cstr = (*env)->GetStringUTFChars(env, jstr, NULL);
    NSString *result = [NSString stringWithUTF8String:cstr];
    (*env)->ReleaseStringUTFChars(env, jstr, cstr);
    return result;
}

static NSData* toNSData(JNIEnv *env, jbyteArray arr) {
    if (!arr) return nil;
    jsize len = (*env)->GetArrayLength(env, arr);
    jbyte *bytes = (*env)->GetByteArrayElements(env, arr, NULL);
    NSData *data = [NSData dataWithBytes:bytes length:len];
    (*env)->ReleaseByteArrayElements(env, arr, bytes, JNI_ABORT);
    return data;
}

// ---------------------------------------------------------------------------
// Peripheral / service / characteristic lookup
// ---------------------------------------------------------------------------

static CBService* findService(CBPeripheral *p, NSString *svcUuid) {
    for (CBService *s in p.services) {
        if ([s.UUID.UUIDString caseInsensitiveCompare:svcUuid] == NSOrderedSame) return s;
    }
    return nil;
}

static CBCharacteristic* findCharacteristic(CBPeripheral *p, NSString *svcUuid, NSString *charUuid) {
    CBService *s = findService(p, svcUuid);
    if (!s) return nil;
    for (CBCharacteristic *c in s.characteristics) {
        if ([c.UUID.UUIDString caseInsensitiveCompare:charUuid] == NSOrderedSame) return c;
    }
    return nil;
}

static CBDescriptor* findDescriptor(CBPeripheral *p, NSString *svcUuid, NSString *charUuid, NSString *descUuid) {
    CBCharacteristic *c = findCharacteristic(p, svcUuid, charUuid);
    if (!c) return nil;
    for (CBDescriptor *d in c.descriptors) {
        if ([d.UUID.UUIDString caseInsensitiveCompare:descUuid] == NSOrderedSame) return d;
    }
    return nil;
}

// ---------------------------------------------------------------------------
// Callback into the Kotlin engine
// ---------------------------------------------------------------------------

static void callVoid0(JNIEnv *env, const char *name, const char *sig) {
    jclass cls = (*env)->GetObjectClass(env, gEngineRef);
    jmethodID m = (*env)->GetMethodID(env, cls, name, sig);
    (*env)->DeleteLocalRef(env, cls);
    if (m) (*env)->CallVoidMethod(env, gEngineRef, m);
}

// ---------------------------------------------------------------------------
// L2CAP channel holder
// ---------------------------------------------------------------------------

@implementation L2CapChannelHolder

- (id)initWithHandle:(jlong)h channel:(CBL2CAPChannel *)channel {
    self = [super init];
    if (self) {
        _handle = h;
        _channel = [channel retain];
        _input = [[channel inputStream] retain];
        _output = [[channel outputStream] retain];
        _outBuffer = [[NSMutableData alloc] init];
        _outputHasSpace = NO;
        _torndown = NO;
    }
    return self;
}

// Runs on the dedicated L2CAP run-loop thread.
- (void)scheduleStreams {
    [_input setDelegate:self];
    [_output setDelegate:self];
    [_input scheduleInRunLoop:[NSRunLoop currentRunLoop] forMode:NSDefaultRunLoopMode];
    [_output scheduleInRunLoop:[NSRunLoop currentRunLoop] forMode:NSDefaultRunLoopMode];
    [_input open];
    [_output open];
}

- (void)drainOutput {
    while (_outputHasSpace && [_outBuffer length] > 0) {
        NSInteger written = [_output write:(const uint8_t *)[_outBuffer bytes]
                                 maxLength:[_outBuffer length]];
        if (written > 0) {
            [_outBuffer replaceBytesInRange:NSMakeRange(0, written) withBytes:NULL length:0];
        } else {
            _outputHasSpace = NO;
            break;
        }
    }
}

// Runs on the L2CAP run-loop thread (via performSelector:onThread:).
- (void)enqueueWrite:(NSData *)data {
    if (_torndown || data == nil) return;
    [_outBuffer appendData:data];
    [self drainOutput];
}

// Runs on the L2CAP run-loop thread (via performSelector:onThread:).
- (void)teardown {
    if (_torndown) return;
    _torndown = YES;
    [_input setDelegate:nil];
    [_output setDelegate:nil];
    [_input removeFromRunLoop:[NSRunLoop currentRunLoop] forMode:NSDefaultRunLoopMode];
    [_output removeFromRunLoop:[NSRunLoop currentRunLoop] forMode:NSDefaultRunLoopMode];
    [_input close];
    [_output close];
}

- (void)notifyClosed {
    jboolean att; JNIEnv *env = getEnv(&att);
    if (env && gEngineRef) {
        jclass cls = (*env)->GetObjectClass(env, gEngineRef);
        jmethodID m = (*env)->GetMethodID(env, cls, "onL2capChannelClosed", "(J)V");
        (*env)->DeleteLocalRef(env, cls);
        if (m) (*env)->CallVoidMethod(env, gEngineRef, m, _handle);
    }
    releaseEnv(att);
}

- (void)stream:(NSStream *)stream handleEvent:(NSStreamEvent)event {
    switch (event) {
        case NSStreamEventHasBytesAvailable: {
            if (stream != _input) break;
            uint8_t buf[4096];
            while ([_input hasBytesAvailable]) {
                NSInteger n = [_input read:buf maxLength:sizeof(buf)];
                if (n <= 0) break;
                jboolean att; JNIEnv *env = getEnv(&att);
                if (env && gEngineRef) {
                    jbyteArray arr = (*env)->NewByteArray(env, (jsize)n);
                    if (arr) {
                        (*env)->SetByteArrayRegion(env, arr, 0, (jsize)n, (const jbyte *)buf);
                        jclass cls = (*env)->GetObjectClass(env, gEngineRef);
                        jmethodID m = (*env)->GetMethodID(env, cls, "onL2capDataReceived", "(J[B)V");
                        (*env)->DeleteLocalRef(env, cls);
                        if (m) (*env)->CallVoidMethod(env, gEngineRef, m, _handle, arr);
                        (*env)->DeleteLocalRef(env, arr);
                    }
                }
                releaseEnv(att);
            }
            break;
        }
        case NSStreamEventHasSpaceAvailable: {
            if (stream != _output) break;
            _outputHasSpace = YES;
            [self drainOutput];
            break;
        }
        case NSStreamEventEndEncountered:
        case NSStreamEventErrorOccurred: {
            [self notifyClosed];
            break;
        }
        default:
            break;
    }
}

- (void)dealloc {
    [_outBuffer release];
    [_input release];
    [_output release];
    [_channel release];
    [super dealloc];
}

@end

// Lazily starts a dedicated thread whose run loop drives all L2CAP streams.
// Called only from the serial gBLEQueue, so no extra locking is needed.
static void ensureL2capThread(void) {
    if (gL2capThread) return;
    gL2capThread = [[NSThread alloc] initWithBlock:^{
        gL2capRunLoop = [NSRunLoop currentRunLoop];
        [gL2capRunLoop addPort:[NSMachPort port] forMode:NSDefaultRunLoopMode];
        while (1) {
            [gL2capRunLoop runMode:NSDefaultRunLoopMode beforeDate:[NSDate distantFuture]];
        }
    }];
    [gL2capThread start];
    while (!gL2capRunLoop) { usleep(1000); }
}

// ---------------------------------------------------------------------------
// Delegate class
// ---------------------------------------------------------------------------

@implementation BlueFalconBLEBridge

#pragma mark - CBCentralManagerDelegate

- (void)centralManagerDidUpdateState:(CBCentralManager *)central {
    jboolean att; JNIEnv *env = getEnv(&att);
    if (!env || !gEngineRef) { releaseEnv(att); return; }

    jclass cls = (*env)->GetObjectClass(env, gEngineRef);
    jmethodID m = (*env)->GetMethodID(env, cls, "onManagerStateChanged", "(I)V");
    (*env)->DeleteLocalRef(env, cls);
    if (m) (*env)->CallVoidMethod(env, gEngineRef, m, (jint)central.state);
    releaseEnv(att);
}

- (void)centralManager:(CBCentralManager *)central
 didDiscoverPeripheral:(CBPeripheral *)peripheral
     advertisementData:(NSDictionary<NSString *,id> *)advertisementData
                  RSSI:(NSNumber *)RSSI {
    NSString *uuid = peripheral.identifier.UUIDString;
    @synchronized(gPeripherals) { gPeripherals[uuid] = peripheral; }

    jboolean att; JNIEnv *env = getEnv(&att);
    if (!env || !gEngineRef) { releaseEnv(att); return; }

    jclass cls = (*env)->GetObjectClass(env, gEngineRef);
    jmethodID m = (*env)->GetMethodID(env, cls, "onDeviceDiscovered",
        "(Ljava/lang/String;Ljava/lang/String;F)V");
    (*env)->DeleteLocalRef(env, cls);

    if (m) {
        NSString *name = peripheral.name
            ?: (NSString *)advertisementData[CBAdvertisementDataLocalNameKey];
        jstring juuid = toJString(env, uuid);
        jstring jname = name ? toJString(env, name) : NULL;
        (*env)->CallVoidMethod(env, gEngineRef, m, juuid, jname, [RSSI floatValue]);
        (*env)->DeleteLocalRef(env, juuid);
        if (jname) (*env)->DeleteLocalRef(env, jname);
    }
    releaseEnv(att);
}

- (void)centralManager:(CBCentralManager *)central
  didConnectPeripheral:(CBPeripheral *)peripheral {
    peripheral.delegate = self;

    jboolean att; JNIEnv *env = getEnv(&att);
    if (!env || !gEngineRef) { releaseEnv(att); return; }

    jclass cls = (*env)->GetObjectClass(env, gEngineRef);
    jmethodID m = (*env)->GetMethodID(env, cls, "onConnected", "(Ljava/lang/String;)V");
    (*env)->DeleteLocalRef(env, cls);
    if (m) {
        jstring juuid = toJString(env, peripheral.identifier.UUIDString);
        (*env)->CallVoidMethod(env, gEngineRef, m, juuid);
        (*env)->DeleteLocalRef(env, juuid);
    }
    releaseEnv(att);
}

- (void)centralManager:(CBCentralManager *)central
didDisconnectPeripheral:(CBPeripheral *)peripheral
                 error:(NSError *)error {
    jboolean att; JNIEnv *env = getEnv(&att);
    if (!env || !gEngineRef) { releaseEnv(att); return; }

    jclass cls = (*env)->GetObjectClass(env, gEngineRef);
    jmethodID m = (*env)->GetMethodID(env, cls, "onDisconnected", "(Ljava/lang/String;)V");
    (*env)->DeleteLocalRef(env, cls);
    if (m) {
        jstring juuid = toJString(env, peripheral.identifier.UUIDString);
        (*env)->CallVoidMethod(env, gEngineRef, m, juuid);
        (*env)->DeleteLocalRef(env, juuid);
    }
    releaseEnv(att);
}

- (void)centralManager:(CBCentralManager *)central
didFailToConnectPeripheral:(CBPeripheral *)peripheral
                 error:(NSError *)error {
    // Treat failed connection as disconnected
    jboolean att; JNIEnv *env = getEnv(&att);
    if (!env || !gEngineRef) { releaseEnv(att); return; }

    jclass cls = (*env)->GetObjectClass(env, gEngineRef);
    jmethodID m = (*env)->GetMethodID(env, cls, "onDisconnected", "(Ljava/lang/String;)V");
    (*env)->DeleteLocalRef(env, cls);
    if (m) {
        jstring juuid = toJString(env, peripheral.identifier.UUIDString);
        (*env)->CallVoidMethod(env, gEngineRef, m, juuid);
        (*env)->DeleteLocalRef(env, juuid);
    }
    releaseEnv(att);
}

#pragma mark - CBPeripheralDelegate

- (void)peripheral:(CBPeripheral *)peripheral didDiscoverServices:(NSError *)error {
    if (error) return;

    NSArray<CBService *> *services = peripheral.services ?: @[];

    jboolean att; JNIEnv *env = getEnv(&att);
    if (!env || !gEngineRef) { releaseEnv(att); return; }

    jclass strCls = (*env)->FindClass(env, "java/lang/String");
    jobjectArray arr = (*env)->NewObjectArray(env, (jsize)services.count, strCls, NULL);
    (*env)->DeleteLocalRef(env, strCls);

    for (NSUInteger i = 0; i < services.count; i++) {
        jstring js = toJString(env, services[i].UUID.UUIDString);
        (*env)->SetObjectArrayElement(env, arr, (jsize)i, js);
        (*env)->DeleteLocalRef(env, js);
    }

    jclass cls = (*env)->GetObjectClass(env, gEngineRef);
    jmethodID m = (*env)->GetMethodID(env, cls, "onServicesDiscovered",
        "(Ljava/lang/String;[Ljava/lang/String;)V");
    (*env)->DeleteLocalRef(env, cls);
    if (m) {
        jstring juuid = toJString(env, peripheral.identifier.UUIDString);
        (*env)->CallVoidMethod(env, gEngineRef, m, juuid, arr);
        (*env)->DeleteLocalRef(env, juuid);
    }
    (*env)->DeleteLocalRef(env, arr);
    releaseEnv(att);
}

- (void)peripheral:(CBPeripheral *)peripheral
didDiscoverCharacteristicsForService:(CBService *)service
             error:(NSError *)error {
    if (error) return;

    NSArray<CBCharacteristic *> *chars = service.characteristics ?: @[];
    NSString *svcUuid = service.UUID.UUIDString;
    NSString *periphUuid = peripheral.identifier.UUIDString;

    jboolean att; JNIEnv *env = getEnv(&att);
    if (!env || !gEngineRef) { releaseEnv(att); return; }

    jclass cls = (*env)->GetObjectClass(env, gEngineRef);
    jmethodID m = (*env)->GetMethodID(env, cls, "onCharacteristicDiscovered",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V");
    (*env)->DeleteLocalRef(env, cls);

    if (m) {
        jstring jpuuid = toJString(env, periphUuid);
        jstring jsuuid = toJString(env, svcUuid);
        for (CBCharacteristic *c in chars) {
            jstring jcuuid = toJString(env, c.UUID.UUIDString);
            (*env)->CallVoidMethod(env, gEngineRef, m, jpuuid, jsuuid, jcuuid, (jint)c.properties);
            (*env)->DeleteLocalRef(env, jcuuid);
            // Kick off descriptor discovery so they're ready when needed
            [peripheral discoverDescriptorsForCharacteristic:c];
        }
        (*env)->DeleteLocalRef(env, jpuuid);
        (*env)->DeleteLocalRef(env, jsuuid);
    }
    releaseEnv(att);
}

- (void)peripheral:(CBPeripheral *)peripheral
didUpdateValueForCharacteristic:(CBCharacteristic *)characteristic
             error:(NSError *)error {
    if (error || !characteristic.value) return;

    jboolean att; JNIEnv *env = getEnv(&att);
    if (!env || !gEngineRef) { releaseEnv(att); return; }

    // Notify path for subscribed characteristics; read path for one-shot reads
    const char *cbName = characteristic.isNotifying ? "onCharacteristicChanged" : "onCharacteristicRead";

    jclass cls = (*env)->GetObjectClass(env, gEngineRef);
    jmethodID m = (*env)->GetMethodID(env, cls, cbName,
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[B)V");
    (*env)->DeleteLocalRef(env, cls);

    if (m) {
        jstring jpuuid = toJString(env, peripheral.identifier.UUIDString);
        jstring jsuuid = toJString(env, characteristic.service.UUID.UUIDString);
        jstring jcuuid = toJString(env, characteristic.UUID.UUIDString);
        jbyteArray jval = toJByteArray(env, characteristic.value);
        (*env)->CallVoidMethod(env, gEngineRef, m, jpuuid, jsuuid, jcuuid, jval);
        (*env)->DeleteLocalRef(env, jpuuid);
        (*env)->DeleteLocalRef(env, jsuuid);
        (*env)->DeleteLocalRef(env, jcuuid);
        if (jval) (*env)->DeleteLocalRef(env, jval);
    }
    releaseEnv(att);
}

- (void)peripheral:(CBPeripheral *)peripheral
didWriteValueForCharacteristic:(CBCharacteristic *)characteristic
             error:(NSError *)error {
    jboolean att; JNIEnv *env = getEnv(&att);
    if (!env || !gEngineRef) { releaseEnv(att); return; }

    jclass cls = (*env)->GetObjectClass(env, gEngineRef);
    jmethodID m = (*env)->GetMethodID(env, cls, "onCharacteristicWritten",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V");
    (*env)->DeleteLocalRef(env, cls);

    if (m) {
        jstring jpuuid = toJString(env, peripheral.identifier.UUIDString);
        jstring jsuuid = toJString(env, characteristic.service.UUID.UUIDString);
        jstring jcuuid = toJString(env, characteristic.UUID.UUIDString);
        (*env)->CallVoidMethod(env, gEngineRef, m, jpuuid, jsuuid, jcuuid,
            error == nil ? JNI_TRUE : JNI_FALSE);
        (*env)->DeleteLocalRef(env, jpuuid);
        (*env)->DeleteLocalRef(env, jsuuid);
        (*env)->DeleteLocalRef(env, jcuuid);
    }
    releaseEnv(att);
}

- (void)peripheral:(CBPeripheral *)peripheral
didDiscoverDescriptorsForCharacteristic:(CBCharacteristic *)characteristic
             error:(NSError *)error {
    // Discovery happens automatically; no explicit Kotlin callback needed
}

- (void)peripheral:(CBPeripheral *)peripheral
didUpdateValueForDescriptor:(CBDescriptor *)descriptor
             error:(NSError *)error {
    if (error || !descriptor.value) return;

    // Coerce various descriptor value types to NSData
    NSData *valueData = nil;
    if ([descriptor.value isKindOfClass:[NSData class]]) {
        valueData = (NSData *)descriptor.value;
    } else if ([descriptor.value isKindOfClass:[NSNumber class]]) {
        uint16_t val = CFSwapInt16HostToLittle([(NSNumber *)descriptor.value unsignedShortValue]);
        valueData = [NSData dataWithBytes:&val length:sizeof(val)];
    } else if ([descriptor.value isKindOfClass:[NSString class]]) {
        valueData = [(NSString *)descriptor.value dataUsingEncoding:NSUTF8StringEncoding];
    }
    if (!valueData) return;

    jboolean att; JNIEnv *env = getEnv(&att);
    if (!env || !gEngineRef) { releaseEnv(att); return; }

    jclass cls = (*env)->GetObjectClass(env, gEngineRef);
    jmethodID m = (*env)->GetMethodID(env, cls, "onDescriptorRead",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[B)V");
    (*env)->DeleteLocalRef(env, cls);

    if (m) {
        jstring jpuuid = toJString(env, peripheral.identifier.UUIDString);
        jstring jsuuid = toJString(env, descriptor.characteristic.service.UUID.UUIDString);
        jstring jcuuid = toJString(env, descriptor.characteristic.UUID.UUIDString);
        jstring jduuid = toJString(env, descriptor.UUID.UUIDString);
        jbyteArray jval = toJByteArray(env, valueData);
        (*env)->CallVoidMethod(env, gEngineRef, m, jpuuid, jsuuid, jcuuid, jduuid, jval);
        (*env)->DeleteLocalRef(env, jpuuid);
        (*env)->DeleteLocalRef(env, jsuuid);
        (*env)->DeleteLocalRef(env, jcuuid);
        (*env)->DeleteLocalRef(env, jduuid);
        if (jval) (*env)->DeleteLocalRef(env, jval);
    }
    releaseEnv(att);
}

- (void)peripheral:(CBPeripheral *)peripheral
didUpdateNotificationStateForCharacteristic:(CBCharacteristic *)characteristic
             error:(NSError *)error {
    // Notification state is reflected in characteristic.isNotifying; no extra callback needed
}

- (void)peripheral:(CBPeripheral *)peripheral
didOpenL2CAPChannel:(CBL2CAPChannel *)channel
             error:(NSError *)error {
    NSString *puuid = peripheral.identifier.UUIDString;

    jboolean att; JNIEnv *env = getEnv(&att);
    if (!env || !gEngineRef) { releaseEnv(att); return; }

    jclass cls = (*env)->GetObjectClass(env, gEngineRef);
    jmethodID m = (*env)->GetMethodID(env, cls, "onL2capChannelOpened",
        "(Ljava/lang/String;JLjava/lang/String;)V");
    (*env)->DeleteLocalRef(env, cls);

    if (error || !channel) {
        if (m) {
            jstring jpuuid = toJString(env, puuid);
            NSString *msg = error ? [error localizedDescription] : @"L2CAP channel was null";
            jstring jerr = toJString(env, msg);
            (*env)->CallVoidMethod(env, gEngineRef, m, jpuuid, (jlong)-1, jerr);
            if (jpuuid) (*env)->DeleteLocalRef(env, jpuuid);
            if (jerr) (*env)->DeleteLocalRef(env, jerr);
        }
        releaseEnv(att);
        return;
    }

    jlong handle = gL2capNextHandle++;
    L2CapChannelHolder *holder = [[L2CapChannelHolder alloc] initWithHandle:handle channel:channel];
    gL2capChannels[@(handle)] = holder;
    [holder release]; // the dictionary now owns it

    ensureL2capThread();
    [holder performSelector:@selector(scheduleStreams)
                   onThread:gL2capThread
                 withObject:nil
              waitUntilDone:NO];

    if (m) {
        jstring jpuuid = toJString(env, puuid);
        (*env)->CallVoidMethod(env, gEngineRef, m, jpuuid, handle, NULL);
        if (jpuuid) (*env)->DeleteLocalRef(env, jpuuid);
    }
    releaseEnv(att);
}

@end

// ---------------------------------------------------------------------------
// JNI entry points
// ---------------------------------------------------------------------------

JNIEXPORT void JNICALL
Java_dev_bluefalcon_engine_macos_jvm_MacosJvmEngine_nativeInitialize(JNIEnv *env, jobject thiz) {
    (*env)->GetJavaVM(env, &gJvm);

    if (gEngineRef) (*env)->DeleteGlobalRef(env, gEngineRef);
    gEngineRef = (*env)->NewGlobalRef(env, thiz);

    gPeripherals = [NSMutableDictionary dictionary];
    gL2capChannels = [[NSMutableDictionary alloc] init];
    gBLEQueue = dispatch_queue_create("dev.bluefalcon.ble", DISPATCH_QUEUE_SERIAL);
    gBridge = [[BlueFalconBLEBridge alloc] init];
    gCentralManager = [[CBCentralManager alloc] initWithDelegate:gBridge queue:gBLEQueue];
}

JNIEXPORT void JNICALL
Java_dev_bluefalcon_engine_macos_jvm_MacosJvmEngine_nativeScan(JNIEnv *env, jobject thiz,
                                                                 jobjectArray serviceUuids) {
    jsize count = (*env)->GetArrayLength(env, serviceUuids);
    NSMutableArray<CBUUID *> *cbuuids = [NSMutableArray arrayWithCapacity:count];
    for (jsize i = 0; i < count; i++) {
        jstring js = (jstring)(*env)->GetObjectArrayElement(env, serviceUuids, i);
        [cbuuids addObject:[CBUUID UUIDWithString:toString(env, js)]];
        (*env)->DeleteLocalRef(env, js);
    }
    NSArray<CBUUID *> *filter = cbuuids.count > 0 ? cbuuids : nil;
    NSDictionary *opts = @{CBCentralManagerScanOptionAllowDuplicatesKey: @YES};

    dispatch_async(gBLEQueue, ^{
        [gCentralManager scanForPeripheralsWithServices:filter options:opts];
    });
}

JNIEXPORT void JNICALL
Java_dev_bluefalcon_engine_macos_jvm_MacosJvmEngine_nativeStopScan(JNIEnv *env, jobject thiz) {
    dispatch_async(gBLEQueue, ^{ [gCentralManager stopScan]; });
}

JNIEXPORT void JNICALL
Java_dev_bluefalcon_engine_macos_jvm_MacosJvmEngine_nativeConnect(JNIEnv *env, jobject thiz,
                                                                    jstring peripheralUuid) {
    NSString *puuid = toString(env, peripheralUuid);
    dispatch_async(gBLEQueue, ^{
        CBPeripheral *p = gPeripherals[puuid];
        if (p) [gCentralManager connectPeripheral:p options:nil];
    });
}

JNIEXPORT void JNICALL
Java_dev_bluefalcon_engine_macos_jvm_MacosJvmEngine_nativeDisconnect(JNIEnv *env, jobject thiz,
                                                                       jstring peripheralUuid) {
    NSString *puuid = toString(env, peripheralUuid);
    dispatch_async(gBLEQueue, ^{
        CBPeripheral *p = gPeripherals[puuid];
        if (p) [gCentralManager cancelPeripheralConnection:p];
    });
}

JNIEXPORT jint JNICALL
Java_dev_bluefalcon_engine_macos_jvm_MacosJvmEngine_nativeGetConnectionState(JNIEnv *env,
                                                                               jobject thiz,
                                                                               jstring peripheralUuid) {
    NSString *puuid = toString(env, peripheralUuid);
    CBPeripheral *p = gPeripherals[puuid];
    return p ? (jint)p.state : 0; // 0 = CBPeripheralStateDisconnected
}

JNIEXPORT void JNICALL
Java_dev_bluefalcon_engine_macos_jvm_MacosJvmEngine_nativeDiscoverServices(JNIEnv *env,
                                                                             jobject thiz,
                                                                             jstring peripheralUuid) {
    NSString *puuid = toString(env, peripheralUuid);
    dispatch_async(gBLEQueue, ^{
        CBPeripheral *p = gPeripherals[puuid];
        if (p) [p discoverServices:nil];
    });
}

JNIEXPORT void JNICALL
Java_dev_bluefalcon_engine_macos_jvm_MacosJvmEngine_nativeDiscoverCharacteristics(JNIEnv *env,
                                                                                    jobject thiz,
                                                                                    jstring peripheralUuid,
                                                                                    jstring serviceUuid) {
    NSString *puuid = toString(env, peripheralUuid);
    NSString *suuid = toString(env, serviceUuid);
    dispatch_async(gBLEQueue, ^{
        CBPeripheral *p = gPeripherals[puuid];
        if (!p) return;
        CBService *s = findService(p, suuid);
        if (s) [p discoverCharacteristics:nil forService:s];
    });
}

JNIEXPORT void JNICALL
Java_dev_bluefalcon_engine_macos_jvm_MacosJvmEngine_nativeReadCharacteristic(JNIEnv *env,
                                                                               jobject thiz,
                                                                               jstring peripheralUuid,
                                                                               jstring serviceUuid,
                                                                               jstring characteristicUuid) {
    NSString *puuid = toString(env, peripheralUuid);
    NSString *suuid = toString(env, serviceUuid);
    NSString *cuuid = toString(env, characteristicUuid);
    dispatch_async(gBLEQueue, ^{
        CBPeripheral *p = gPeripherals[puuid];
        if (!p) return;
        CBCharacteristic *c = findCharacteristic(p, suuid, cuuid);
        if (c) [p readValueForCharacteristic:c];
    });
}

JNIEXPORT void JNICALL
Java_dev_bluefalcon_engine_macos_jvm_MacosJvmEngine_nativeWriteCharacteristic(JNIEnv *env,
                                                                                jobject thiz,
                                                                                jstring peripheralUuid,
                                                                                jstring serviceUuid,
                                                                                jstring characteristicUuid,
                                                                                jbyteArray value,
                                                                                jboolean withResponse) {
    NSString *puuid = toString(env, peripheralUuid);
    NSString *suuid = toString(env, serviceUuid);
    NSString *cuuid = toString(env, characteristicUuid);
    NSData *data = toNSData(env, value);
    CBCharacteristicWriteType wtype = withResponse
        ? CBCharacteristicWriteWithResponse : CBCharacteristicWriteWithoutResponse;

    dispatch_async(gBLEQueue, ^{
        CBPeripheral *p = gPeripherals[puuid];
        if (!p) return;
        CBCharacteristic *c = findCharacteristic(p, suuid, cuuid);
        if (c) [p writeValue:data forCharacteristic:c type:wtype];
    });
}

JNIEXPORT void JNICALL
Java_dev_bluefalcon_engine_macos_jvm_MacosJvmEngine_nativeSetNotify(JNIEnv *env, jobject thiz,
                                                                      jstring peripheralUuid,
                                                                      jstring serviceUuid,
                                                                      jstring characteristicUuid,
                                                                      jboolean enable) {
    NSString *puuid = toString(env, peripheralUuid);
    NSString *suuid = toString(env, serviceUuid);
    NSString *cuuid = toString(env, characteristicUuid);
    dispatch_async(gBLEQueue, ^{
        CBPeripheral *p = gPeripherals[puuid];
        if (!p) return;
        CBCharacteristic *c = findCharacteristic(p, suuid, cuuid);
        if (c) [p setNotifyValue:(BOOL)enable forCharacteristic:c];
    });
}

JNIEXPORT void JNICALL
Java_dev_bluefalcon_engine_macos_jvm_MacosJvmEngine_nativeReadDescriptor(JNIEnv *env, jobject thiz,
                                                                           jstring peripheralUuid,
                                                                           jstring serviceUuid,
                                                                           jstring characteristicUuid,
                                                                           jstring descriptorUuid) {
    NSString *puuid = toString(env, peripheralUuid);
    NSString *suuid = toString(env, serviceUuid);
    NSString *cuuid = toString(env, characteristicUuid);
    NSString *duuid = toString(env, descriptorUuid);
    dispatch_async(gBLEQueue, ^{
        CBPeripheral *p = gPeripherals[puuid];
        if (!p) return;
        CBDescriptor *d = findDescriptor(p, suuid, cuuid, duuid);
        if (d) [p readValueForDescriptor:d];
    });
}

JNIEXPORT void JNICALL
Java_dev_bluefalcon_engine_macos_jvm_MacosJvmEngine_nativeWriteDescriptor(JNIEnv *env, jobject thiz,
                                                                            jstring peripheralUuid,
                                                                            jstring serviceUuid,
                                                                            jstring characteristicUuid,
                                                                            jstring descriptorUuid,
                                                                            jbyteArray value) {
    NSString *puuid = toString(env, peripheralUuid);
    NSString *suuid = toString(env, serviceUuid);
    NSString *cuuid = toString(env, characteristicUuid);
    NSString *duuid = toString(env, descriptorUuid);
    NSData *data = toNSData(env, value);
    dispatch_async(gBLEQueue, ^{
        CBPeripheral *p = gPeripherals[puuid];
        if (!p) return;
        CBDescriptor *d = findDescriptor(p, suuid, cuuid, duuid);
        if (d) [p writeValue:data forDescriptor:d];
    });
}

JNIEXPORT void JNICALL
Java_dev_bluefalcon_engine_macos_jvm_MacosJvmEngine_nativeChangeMTU(JNIEnv *env, jobject thiz,
                                                                      jstring peripheralUuid,
                                                                      jint mtuSize) {
    NSString *puuid = toString(env, peripheralUuid);
    dispatch_async(gBLEQueue, ^{
        CBPeripheral *p = gPeripherals[puuid];
        if (!p) return;
        // CoreBluetooth exposes the negotiated MTU; mtuSize hint is ignored
        NSUInteger actualMtu = [p maximumWriteValueLengthForType:CBCharacteristicWriteWithResponse];

        jboolean att; JNIEnv *cbEnv = getEnv(&att);
        if (!cbEnv || !gEngineRef) { releaseEnv(att); return; }
        jclass cls = (*cbEnv)->GetObjectClass(cbEnv, gEngineRef);
        jmethodID m = (*cbEnv)->GetMethodID(cbEnv, cls, "onMtuChanged", "(Ljava/lang/String;I)V");
        (*cbEnv)->DeleteLocalRef(cbEnv, cls);
        if (m) {
            jstring juuid = toJString(cbEnv, puuid);
            (*cbEnv)->CallVoidMethod(cbEnv, gEngineRef, m, juuid, (jint)actualMtu);
            (*cbEnv)->DeleteLocalRef(cbEnv, juuid);
        }
        releaseEnv(att);
    });
}

// ---------------------------------------------------------------------------
// L2CAP JNI entry points
// ---------------------------------------------------------------------------

JNIEXPORT void JNICALL
Java_dev_bluefalcon_engine_macos_jvm_MacosJvmEngine_nativeOpenL2capChannel(JNIEnv *env, jobject thiz,
                                                                            jstring peripheralUuid,
                                                                            jint psm) {
    NSString *puuid = toString(env, peripheralUuid);
    uint16_t psmValue = (uint16_t)psm;
    dispatch_async(gBLEQueue, ^{
        CBPeripheral *p = gPeripherals[puuid];
        if (p) {
            p.delegate = gBridge;
            [p openL2CAPChannel:psmValue];
        }
    });
}

JNIEXPORT void JNICALL
Java_dev_bluefalcon_engine_macos_jvm_MacosJvmEngine_nativeL2capWrite(JNIEnv *env, jobject thiz,
                                                                      jlong handle, jbyteArray data) {
    L2CapChannelHolder *holder = [gL2capChannels[@(handle)] retain];
    if (!holder) return;
    if (gL2capThread) {
        NSData *nsdata = toNSData(env, data);
        [holder performSelector:@selector(enqueueWrite:)
                       onThread:gL2capThread
                     withObject:nsdata
                  waitUntilDone:NO];
    }
    [holder release];
}

JNIEXPORT void JNICALL
Java_dev_bluefalcon_engine_macos_jvm_MacosJvmEngine_nativeL2capClose(JNIEnv *env, jobject thiz,
                                                                      jlong handle) {
    L2CapChannelHolder *holder = [gL2capChannels[@(handle)] retain];
    if (!holder) return;
    [gL2capChannels removeObjectForKey:@(handle)];
    if (gL2capThread) {
        [holder performSelector:@selector(teardown)
                       onThread:gL2capThread
                     withObject:nil
                  waitUntilDone:NO];
    }
    [holder release];
}
