// Keeps every CBL2CAPChannel and its streams on the Objective-C side (#334).
//
// Core Bluetooth closes an L2CAP channel over the air when the channel object is deallocated.
// A Kotlin/Native reference to an Objective-C object is released only when the Kotlin GC frees
// it, so a channel that ever reaches Kotlin stays open until some later collection. Here ARC
// releases the channel as soon as -closeChannel: removes it, and Kotlin only ever sees an id.

#pragma once

#import <CoreBluetooth/CoreBluetooth.h>
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/** Receives L2CAP opens from BFPeripheralDelegateProxy by id. */
@protocol BFL2capOpenSink <NSObject>
- (void)bfPeripheral:(CBPeripheral *)peripheral didOpenL2capChannelId:(int64_t)channelId psm:(uint16_t)psm error:(nullable NSError *)error;
@end

/** Stream event for a stored channel. `input` is YES for the input stream. */
typedef void (^BFL2capStreamEvents)(BOOL input, NSUInteger event);

@interface BFL2capChannelStore : NSObject
+ (instancetype)shared;
/** Stores `channel`, or a stream pair and the object that owns it. Returns its id (> 0). */
- (int64_t)addOwner:(id)owner input:(NSInputStream *)input output:(NSOutputStream *)output;
- (BOOL)contains:(int64_t)channelId;
- (NSUInteger)count;
/** Schedules both streams on the main run loop and opens them. Events arrive on the main thread. */
- (BOOL)start:(int64_t)channelId events:(BFL2capStreamEvents)events;
- (BOOL)hasBytesAvailable:(int64_t)channelId;
/** -1 when the channel is gone or the read failed. */
- (NSInteger)read:(int64_t)channelId into:(uint8_t *)buffer maxLength:(NSUInteger)length;
/** -1 when the channel is gone or the write failed. May block like -[NSOutputStream write:]. */
- (NSInteger)write:(int64_t)channelId bytes:(const uint8_t *)bytes length:(NSUInteger)length;
/** Closes both streams, removes them from the run loop and drops the channel. Idempotent. */
- (void)closeChannel:(int64_t)channelId;
@end

/**
 * Sits in front of a Kotlin CBPeripheralDelegate. It handles -peripheral:didOpenL2CAPChannel:error:
 * itself (stores the channel and hands `sink` an id) and forwards every other delegate call to
 * `delegate`. Holds `delegate` weakly (the owner keeps it) and `sink` strongly.
 */
@interface BFPeripheralDelegateProxy : NSObject <CBPeripheralDelegate>
- (instancetype)initWithDelegate:(id<CBPeripheralDelegate>)delegate sink:(id<BFL2capOpenSink>)sink;
@end

NS_ASSUME_NONNULL_END

