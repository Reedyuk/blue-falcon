#import "bfl2cap.h"

@interface BFL2capEntry : NSObject <NSStreamDelegate>
@property (nonatomic, strong, nullable) id owner;
@property (nonatomic, strong, nullable) NSInputStream *input;
@property (nonatomic, strong, nullable) NSOutputStream *output;
@property (nonatomic, copy, nullable) BFL2capStreamEvents events;
@end

@implementation BFL2capEntry
- (void)stream:(NSStream *)stream handleEvent:(NSStreamEvent)event {
    BFL2capStreamEvents events = self.events;
    if (events != nil) events(stream == self.input, (NSUInteger)event);
}
@end

@implementation BFL2capChannelStore {
    NSMutableDictionary<NSNumber *, BFL2capEntry *> *_entries;
    int64_t _nextId;
}

+ (instancetype)shared {
    static BFL2capChannelStore *store;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ store = [[BFL2capChannelStore alloc] init]; });
    return store;
}

- (instancetype)init {
    if ((self = [super init])) { _entries = [NSMutableDictionary dictionary]; _nextId = 0; }
    return self;
}

- (nullable BFL2capEntry *)entry:(int64_t)channelId {
    @synchronized (self) { return _entries[@(channelId)]; }
}

- (int64_t)addOwner:(id)owner input:(NSInputStream *)input output:(NSOutputStream *)output {
    BFL2capEntry *entry = [[BFL2capEntry alloc] init];
    entry.owner = owner;
    entry.input = input;
    entry.output = output;
    @synchronized (self) {
        int64_t channelId = ++_nextId;
        _entries[@(channelId)] = entry;
        return channelId;
    }
}

- (BOOL)contains:(int64_t)channelId { return [self entry:channelId] != nil; }

- (NSUInteger)count { @synchronized (self) { return _entries.count; } }

- (BOOL)start:(int64_t)channelId events:(BFL2capStreamEvents)events {
    BFL2capEntry *entry = [self entry:channelId];
    if (entry == nil) return NO;
    entry.events = events;
    for (NSStream *stream in @[entry.input, entry.output]) {
        stream.delegate = entry;
        [stream scheduleInRunLoop:[NSRunLoop mainRunLoop] forMode:NSDefaultRunLoopMode];
        [stream open];
    }
    return YES;
}

- (BOOL)hasBytesAvailable:(int64_t)channelId {
    NSInputStream *input = [self entry:channelId].input;
    return input != nil && input.hasBytesAvailable;
}

- (NSInteger)read:(int64_t)channelId into:(uint8_t *)buffer maxLength:(NSUInteger)length {
    NSInputStream *input = [self entry:channelId].input;
    return input == nil ? -1 : [input read:buffer maxLength:length];
}

- (NSInteger)write:(int64_t)channelId bytes:(const uint8_t *)bytes length:(NSUInteger)length {
    NSOutputStream *output = [self entry:channelId].output;
    return output == nil ? -1 : [output write:bytes maxLength:length];
}

- (void)closeChannel:(int64_t)channelId {
    BFL2capEntry *entry;
    @synchronized (self) {
        entry = _entries[@(channelId)];
        [_entries removeObjectForKey:@(channelId)];
    }
    if (entry == nil) return;
    entry.events = nil;
    for (NSStream *stream in @[entry.input ?: (NSStream *)[NSNull null], entry.output ?: (NSStream *)[NSNull null]]) {
        if (![stream isKindOfClass:[NSStream class]]) continue;
        stream.delegate = nil;
        [stream close];
        [stream removeFromRunLoop:[NSRunLoop mainRunLoop] forMode:NSDefaultRunLoopMode];
    }
    entry.input = nil;
    entry.output = nil;
    entry.owner = nil; // the last strong reference to the CBL2CAPChannel
}
@end

@implementation BFPeripheralDelegateProxy {
    __weak id<CBPeripheralDelegate> _target;
    id<BFL2capOpenSink> _sink;
}

- (instancetype)initWithDelegate:(id<CBPeripheralDelegate>)delegate sink:(id<BFL2capOpenSink>)sink {
    if ((self = [super init])) { _target = delegate; _sink = sink; }
    return self;
}

- (void)peripheral:(CBPeripheral *)peripheral didOpenL2CAPChannel:(nullable CBL2CAPChannel *)channel error:(nullable NSError *)error {
    id<BFL2capOpenSink> sink = _sink;
    int64_t channelId = 0;
    uint16_t psm = 0;
    if (channel != nil && channel.inputStream != nil && channel.outputStream != nil) {
        psm = channel.PSM;
        channelId = [[BFL2capChannelStore shared] addOwner:channel input:channel.inputStream output:channel.outputStream];
    }
    if (sink == nil) {
        if (channelId != 0) [[BFL2capChannelStore shared] closeChannel:channelId];
        return;
    }
    [sink bfPeripheral:peripheral didOpenL2capChannelId:channelId psm:psm error:error];
}

- (BOOL)respondsToSelector:(SEL)selector {
    if (selector == @selector(peripheral:didOpenL2CAPChannel:error:)) return YES;
    return [super respondsToSelector:selector] || [_target respondsToSelector:selector];
}

- (id)forwardingTargetForSelector:(SEL)selector {
    id target = _target;
    return [target respondsToSelector:selector] ? target : [super forwardingTargetForSelector:selector];
}
@end
