package io.lightstick.sdk.ble.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.lightstick.sdk.ble.manager.*
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BleModule {

    @Provides
    @Singleton
    fun provideScanManager(@ApplicationContext context: Context): ScanManager {
        return ScanManager(context)
    }

    @Provides
    @Singleton
    fun provideBleGattManager(@ApplicationContext context: Context): BleGattManager {
        return BleGattManager(context)
    }

    @Provides
    @Singleton
    fun provideBleBondManager(
        @ApplicationContext context: Context,
        gattManager: BleGattManager
    ): BleBondManager {
        val bondManager = BleBondManager(context, gattManager)
        bondManager.registerReceiver()
        return bondManager
    }

    @Provides
    @Singleton
    fun provideDeviceInfoManager(gattManager: BleGattManager): DeviceInfoManager {
        return DeviceInfoManager(gattManager)
    }

    @Provides
    @Singleton
    fun provideLedControlManager(gattManager: BleGattManager): LedControlManager {
        return LedControlManager(gattManager)
    }

    @Provides
    @Singleton
    fun provideOtaManager(gattManager: BleGattManager): OtaManager {
        return OtaManager(gattManager)
    }
}
