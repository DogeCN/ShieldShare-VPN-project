package com.example.shieldshare.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.shieldshare.data.db.AppDatabase
import com.example.shieldshare.data.prefs.AppPrefs
import com.example.shieldshare.data.repository.TrafficRepository
import com.example.shieldshare.managers.hotspot.HotspotManager
import com.example.shieldshare.managers.hotspot.HotspotManagerImpl
import com.example.shieldshare.managers.meter.TrafficMeter
import com.example.shieldshare.managers.meter.TrafficMeterSimple
import com.example.shieldshare.managers.network.IpAddressProvider
import com.example.shieldshare.managers.network.IpAddressProviderImpl
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
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Migration from version 2 to 3: Add service_sessions table
     */
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Create service_sessions table
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS service_sessions (
                    sessionId TEXT PRIMARY KEY NOT NULL,
                    startTime INTEGER NOT NULL,
                    endTime INTEGER,
                    totalBytesUploaded INTEGER NOT NULL,
                    totalBytesDownloaded INTEGER NOT NULL,
                    uniqueClients INTEGER NOT NULL,
                    isActive INTEGER NOT NULL
                )
            """.trimIndent())
            
            // Create index on startTime
            database.execSQL("""
                CREATE INDEX IF NOT EXISTS index_service_sessions_startTime ON service_sessions(startTime)
            """.trimIndent())
            
            // Create index on endTime
            database.execSQL("""
                CREATE INDEX IF NOT EXISTS index_service_sessions_endTime ON service_sessions(endTime)
            """.trimIndent())
        }
    }

    /**
     * Migration from version 1 to 2: Add traffic tracking tables
     * Since we're only adding new tables (not modifying existing ones),
     * this migration is straightforward.
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Create traffic_records table
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS traffic_records (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    clientIp TEXT NOT NULL,
                    macAddress TEXT NOT NULL,
                    bytesUploaded INTEGER NOT NULL,
                    bytesDownloaded INTEGER NOT NULL,
                    timestamp INTEGER NOT NULL,
                    protocol TEXT NOT NULL,
                    sessionId TEXT
                )
            """.trimIndent())
            
            // Create index on clientIp
            database.execSQL("""
                CREATE INDEX IF NOT EXISTS index_traffic_records_clientIp ON traffic_records(clientIp)
            """.trimIndent())
            
            // Create index on timestamp
            database.execSQL("""
                CREATE INDEX IF NOT EXISTS index_traffic_records_timestamp ON traffic_records(timestamp)
            """.trimIndent())
            
            // Create client_sessions table
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS client_sessions (
                    sessionId TEXT PRIMARY KEY NOT NULL,
                    clientIp TEXT NOT NULL,
                    macAddress TEXT NOT NULL,
                    protocolType TEXT NOT NULL,
                    startTime INTEGER NOT NULL,
                    endTime INTEGER,
                    totalBytesUploaded INTEGER NOT NULL,
                    totalBytesDownloaded INTEGER NOT NULL,
                    connectionCount INTEGER NOT NULL,
                    hostsAccessed TEXT,
                    userAgent TEXT,
                    deviceName TEXT,
                    isActive INTEGER NOT NULL
                )
            """.trimIndent())
            
            // Create index on clientIp for sessions
            database.execSQL("""
                CREATE INDEX IF NOT EXISTS index_client_sessions_clientIp ON client_sessions(clientIp)
            """.trimIndent())
            
            // Create index on startTime for sessions
            database.execSQL("""
                CREATE INDEX IF NOT EXISTS index_client_sessions_startTime ON client_sessions(startTime)
            """.trimIndent())
            
            // Create client_stats table
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS client_stats (
                    clientIp TEXT PRIMARY KEY NOT NULL,
                    macAddress TEXT NOT NULL,
                    deviceAlias TEXT,
                    firstSeen INTEGER NOT NULL,
                    lastSeen INTEGER NOT NULL,
                    totalBytesUploaded INTEGER NOT NULL,
                    totalBytesDownloaded INTEGER NOT NULL,
                    totalConnections INTEGER NOT NULL,
                    totalSessions INTEGER NOT NULL,
                    lastProtocol TEXT,
                    lastUpdated INTEGER NOT NULL
                )
            """.trimIndent())
            
            // Create index on lastSeen for stats
            database.execSQL("""
                CREATE INDEX IF NOT EXISTS index_client_stats_lastSeen ON client_stats(lastSeen)
            """.trimIndent())
        }
    }

    @Provides
    @Singleton
    fun provideDb(@ApplicationContext ctx: Context): AppDatabase =
            Room.databaseBuilder(ctx, AppDatabase::class.java, "shieldshare.db")
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()

    @Provides
    @Singleton
    fun providePrefs(@ApplicationContext ctx: Context): AppPrefs = AppPrefs(ctx)

    @Provides
    @Singleton
    fun provideVpnManager(@ApplicationContext ctx: Context): VpnManager = VpnManagerImpl(ctx)

    @Provides
    @Singleton
    fun provideTrafficMeter(
        @ApplicationContext ctx: Context,
        trafficRepository: TrafficRepository
    ): TrafficMeter =
            TrafficMeterSimple(ctx, trafficRepository) // STAGE 2: Simple Traffic Metering Implementation

    @Provides
    @Singleton
    fun provideHotspotManager(@ApplicationContext ctx: Context): HotspotManager =
            HotspotManagerImpl(ctx)

    @Provides
    @Singleton
    fun provideProxyServer(
            @ApplicationContext context: Context,
            trafficMeter: TrafficMeter,
            vpnManager: VpnManager,
            hotspotManager: HotspotManager,
            appPrefs: AppPrefs
    ): ProxyServer = ProxyServerImpl(context, trafficMeter, vpnManager, hotspotManager, appPrefs)

    @Provides
    @Singleton
    fun provideSyncManager(): SyncManager = SyncManagerNoop()

    @Provides @Singleton
    fun provideOkHttp(): OkHttpClient =
        OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.SECONDS)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()

    @Provides @Singleton
    fun provideIpAddressProvider(client: OkHttpClient): IpAddressProvider =
        IpAddressProviderImpl(client)

    @Provides
    @Singleton
    fun provideTrafficRepository(db: AppDatabase): TrafficRepository =
        TrafficRepository(
            trafficRecordDao = db.trafficRecordDao(),
            clientSessionDao = db.clientSessionDao(),
            clientStatsDao = db.clientStatsDao()
        )
}
