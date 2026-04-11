package dev.bluefalcon.core

import dev.bluefalcon.core.mocks.FakeBlueFalconEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for BlueFalcon core functionality
 */
class BlueFalconTest {
    
    @Test
    fun `should create BlueFalcon instance with engine`() {
        // Given
        val engine = FakeBlueFalconEngine()
        
        // When
        val blueFalcon = BlueFalcon(engine)
        
        // Then
        assertEquals(engine, blueFalcon.engine)
    }
    
    @Test
    fun `scan should delegate to engine`() = runTest {
        // Given
        val engine = FakeBlueFalconEngine()
        val blueFalcon = BlueFalcon(engine)
        
        // When
        blueFalcon.scan()
        
        // Then
        assertTrue(engine.scanCalled)
        assertTrue(blueFalcon.isScanning)
    }
    
    @Test
    fun `scan with filters should pass filters to engine`() = runTest {
        // Given
        val engine = FakeBlueFalconEngine()
        val blueFalcon = BlueFalcon(engine)
        val filters = listOf(ServiceFilter("0000180D-0000-1000-8000-00805F9B34FB".toUuid()))
        
        // When
        blueFalcon.scan(filters)
        
        // Then
        assertEquals(filters, engine.lastScanFilters)
    }
    
    @Test
    fun `stopScanning should delegate to engine`() = runTest {
        // Given
        val engine = FakeBlueFalconEngine()
        val blueFalcon = BlueFalcon(engine)
        blueFalcon.scan()
        
        // When
        blueFalcon.stopScanning()
        
        // Then
        assertTrue(engine.stopScanningCalled)
        assertFalse(blueFalcon.isScanning)
    }
    
    @Test
    fun `clearPeripherals should clear engine peripherals`() = runTest {
        // Given
        val engine = FakeBlueFalconEngine()
        val blueFalcon = BlueFalcon(engine)
        engine.addFakePeripheral("Device 1")
        engine.addFakePeripheral("Device 2")
        
        // When
        blueFalcon.clearPeripherals()
        
        // Then
        assertEquals(0, blueFalcon.peripherals.value.size)
    }
    
    @Test
    fun `connect should delegate to engine`() = runTest {
        // Given
        val engine = FakeBlueFalconEngine()
        val blueFalcon = BlueFalcon(engine)
        val peripheral = engine.createFakePeripheral("Device")
        
        // When
        blueFalcon.connect(peripheral)
        
        // Then
        assertTrue(engine.connectCalled)
    }
    
    @Test
    fun `disconnect should delegate to engine`() = runTest {
        // Given
        val engine = FakeBlueFalconEngine()
        val blueFalcon = BlueFalcon(engine)
        val peripheral = engine.createFakePeripheral("Device")
        
        // When
        blueFalcon.disconnect(peripheral)
        
        // Then
        assertTrue(engine.disconnectCalled)
    }
    
    @Test
    fun `peripherals flow should emit discovered devices`() = runTest {
        // Given
        val engine = FakeBlueFalconEngine()
        val blueFalcon = BlueFalcon(engine)
        
        // When
        engine.addFakePeripheral("Device 1")
        engine.addFakePeripheral("Device 2")
        
        // Then
        assertEquals(2, blueFalcon.peripherals.value.size)
    }
    
    @Test
    fun `managerState should reflect engine state`() = runTest {
        // Given
        val engine = FakeBlueFalconEngine()
        val blueFalcon = BlueFalcon(engine)
        
        // When
        engine.setBluetoothState(BluetoothManagerState.NotReady)
        
        // Then
        assertEquals(BluetoothManagerState.NotReady, blueFalcon.managerState.value)
    }
}
