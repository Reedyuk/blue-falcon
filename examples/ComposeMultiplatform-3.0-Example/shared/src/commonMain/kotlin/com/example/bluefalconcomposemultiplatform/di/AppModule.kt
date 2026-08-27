package com.example.bluefalconcomposemultiplatform.di

import com.example.bluefalconcomposemultiplatform.peripheral.PeripheralExampleRuntime
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.peripheral.BluetoothAdvertiser
import dev.bluefalcon.plugins.bonding.BondingPlugin
import dev.bluefalcon.plugins.nordicfota.NordicFotaPlugin

expect class AppModule {
    val blueFalcon: BlueFalcon
    val fotaPlugin: NordicFotaPlugin
    val bondingPlugin: BondingPlugin
    val advertiser: BluetoothAdvertiser
    val peripheralRuntime: PeripheralExampleRuntime?
}