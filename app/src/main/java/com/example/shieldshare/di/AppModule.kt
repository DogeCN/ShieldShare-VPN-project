package com.example.shieldshare.di

import android.content.Context
import androidx.room.Room
import com.example.shieldshare.data.db.AppDatabase
import com.example.shieldshare.data.prefs.AppPrefs
import com.example.shieldshare.managers.hotspot.HotspotManager
import com.example.shieldshare.managers.hotspot.HotspotManagerImpl
import com.example.shieldshare.managers.meter.TrafficMeter
import com.example.shieldshare.managers.meter.TrafficMeterNoop
import com.example.shieldshare.managers.proxy.ProxyServer
import com.example.shieldshare.managers.proxy.ProxyServerImpl
import com.example.shieldshare.managers.sync.SyncManager
import com.example.shieldshare.managers.sync.SyncManagerNoop
import com.example.shieldshare.managers.vpn.VpnManager
import com.example.shieldshare.managers.vpn.VpnManagerImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDb(@ApplicationContext ctx: Context): AppDatabase =
            Room.databaseBuilder(ctx, AppDatabase::class.java, "shieldshare.db").build()

    @Provides
    @Singleton
    fun providePrefs(@ApplicationContext ctx: Context): AppPrefs = AppPrefs(ctx)

    @Provides
    @Singleton
    fun provideVpnManager(@ApplicationContext ctx: Context): VpnManager = VpnManagerImpl(ctx)

    @Provides
    @Singleton
    fun provideTrafficMeter(): TrafficMeter =
            TrafficMeterNoop() // TODO: JIALU - Replace with TrafficMeterImpl

    @Provides
    @Singleton
    fun provideHotspotManager(@ApplicationContext ctx: Context): HotspotManager =
            HotspotManagerImpl(ctx)

    @Provides
    @Singleton
    fun provideProxyServer(
            @ApplicationContext context: Context,
            trafficMeter: TrafficMeter,
            vpnManager: VpnManager
    ): ProxyServer = ProxyServerImpl(context, trafficMeter, vpnManager)

    @Provides @Singleton fun provideSyncManager(): SyncManager = SyncManagerNoop()
}
