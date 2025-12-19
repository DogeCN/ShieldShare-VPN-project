# ShieldShare VPN Project

**A VPN-agnostic network sharing application that enables users to share their VPN connection through a mobile hotspot with proxy server functionality.**

## Project Overview

ShieldShare is an Android application that allows users to share their VPN connection with other devices through a mobile hotspot. The app provides a proxy server that routes client traffic through the host device's VPN connection, enabling secure internet access for multiple devices.

### Research Context

This project explores how to extend the functionality of a stock Android phone (without root access) to provide secure, measurable, and shareable internet access. In environments like classrooms, study groups, or small team projects, it is valuable to ensure that shared internet access is protected by a VPN and that each participant's usage can be accounted for.

### Core Objectives

- Enable Wi-Fi hotspot sharing for nearby users
- Route all shared traffic through the VPN tunnel for privacy and security
- Provide per-user accounting showing data consumption per connected device
- Display usage statistics in a user-friendly dashboard with export options

### Key Features

- **VPN-Agnostic Design** - Works with any third-party VPN app
- **Mobile Hotspot Integration** - Creates and manages Android hotspot
- **Proxy Server** - HTTP/HTTPS and SOCKS5 proxy support
- **Auto-Configuration** - PAC file and QR code setup
- **Real-time Monitoring** - Client detection and traffic monitoring
- **Cross-Platform Support** - Works with Android, iOS, Windows, Mac clients

## Technical Architecture

### Core Components

- **Proxy Server** - Multi-protocol proxy (HTTP/HTTPS/SOCKS5) with foreground service
- **Hotspot Manager** - Android hotspot detection and management
- **VPN Manager** - Third-party VPN detection and integration
- **Traffic Meter** - Network usage monitoring with per-client tracking
- **Quota Manager** - Traffic quota allocation and enforcement
- **Traffic Filter Manager** - Content filtering and access control
- **Consumption Tracker** - Abuse detection and monitoring
- **Performance Monitor** - Real-time system resource tracking (process-specific CPU, battery, throughput) with CSV export
- **Database Layer** - Room database for persistent storage of sessions, stats, and traffic records
- **QR Code Generator** - Client configuration automation with PAC file support

### Technology Stack

- **Language**: Kotlin
- **Framework**: Android Jetpack Compose
- **Dependency Injection**: Hilt
- **Architecture**: MVVM with StateFlow
- **Database**: Room (SQLite) for data persistence
- **Background Tasks**: WorkManager for scheduled operations
- **Build System**: Gradle with Kotlin DSL
- **Target SDK**: Android 14 (API 34)
- **Min SDK**: Android 8.0 (API 26)

## Installation & Setup

### Prerequisites

- Android Studio (latest version recommended)
- Android SDK 34
- JDK 17
- Physical Android device (emulator has network limitations)
- Third-party VPN app (e.g., NordVPN, ExpressVPN)

### Installation steps

1. **Clone the repository**

   ```bash
   git clone https://github.com/yestark/ShieldShare-VPN-project.git
   cd ShieldShare-VPN-project
   ```

2. **Enable Developer Options** on your Android device

3. **Enable USB Debugging**

4. **Connect device** via USB

5. **Build the project**

   ```bash
   ./gradlew assembleDebug
   ```

6. **Install on device**

   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

7. **Open the app and grant necessary permissions** (Notification, Hotspot, etc.)

## Team Contributions

### Team Members

- **Carlos Semeho Edorh**: Traffic Metering, Managing & QR Code/PAC Implementation
- **Hanchen Ye**: VPN Detector, Proxy & Hotspot Implementation
- **Jialu Bi**: Database & UI Implementation

### Key Achievements

- **VPN-Agnostic Design**: Works with any third-party VPN app
- **Proxy Server**: Multi-protocol support (HTTP/HTTPS/SOCKS5) with foreground service
- **Client Detection**: IP-based counting through proxy server connections
- **Auto-Configuration**: PAC files and QR code setup
- **Real-time Monitoring**: Live client detection and traffic accounting
- **Data Persistence**: Room database for session tracking and statistics
- **Quota Management**: Per-client bandwidth allocation and enforcement
- **Traffic Filtering**: Content filtering and access control
- **Performance Monitoring**: Process-specific CPU tracking, battery consumption analysis, network throughput measurement, and CSV export for performance analysis

## Current Status

### Working Components

- **Proxy Server** - Starts and listens on configurable ports (default 8080/8081) with foreground service
- **QR Code Generation** - Manual and PAC configuration
- **VPN Status Detection** - Real-time VPN monitoring and integration
- **Hotspot Detection** - Client monitoring and IP tracking
- **PAC File Generation** - Dynamic routing configuration
- **Client Detection** - IP-based counting with persistent tracking
- **Traffic Metering** - Per-client traffic statistics with database persistence
- **Quota Management** - Bandwidth allocation, enforcement, and blocking
- **Session Tracking** - Client sessions and service sessions stored in Room database
- **Performance Monitoring** - Real-time system resource tracking with process-specific CPU monitoring, battery consumption analysis, network throughput measurement, and CSV export capabilities
- **Advanced Traffic Regulation** - Quota configuration and traffic filtering
- **Settings Management** - Comprehensive app configuration

### Known Issues

- **Client Detection Timing** - 30 seconds to 1 minute delay for full client detection using IP detection approach
- **Permission Restrictions** - Cannot access Hotspot system files for client detection

### Remaining Challenges

- **VPN Compatibility** - Need VPN apps that support LAN connections
- **Performance Optimization** - Handle multiple concurrent clients efficiently

### Technical Challenges

- **Non-Root Constraints** - Android restricts hotspot automation and low-level traffic inspection for security reasons
- **Performance Impact** - Running VPN + hotspot + traffic monitoring increases CPU and battery usage
- **User Adoption** - Connected clients must configure their devices (proxy settings) to ensure traffic flows through the app's accounting system

## Usage Instructions

### For Host Device (Android)

1. **Install ShieldShare app**
2. **Connect to VPN** (third-party app)
3. **Enable mobile hotspot** in Android settings
4. **Proxy server** in ShieldShare starts automatically once VPN and Hotspot are configured
5. **Configure settings** (optional): Set quotas, traffic filters, and other preferences
6. **Share QR code** with clients from the Home screen
7. **Monitor usage** via the Monitoring dashboard
8. **View performance metrics** via the Performance screen

### For Client Devices

1. **Connect to hotspot** (Wi-Fi)
2. **Scan QR code** or configure proxy manually
3. **Configure proxy settings**:
   - Server: [Hotspot IP]
   - Port: 1080/8080
   - Type: SOCKS5/HTTP/HTTPS
4. **Test internet access** through proxy

## Technical Implementation

### Application Architecture

The app follows MVVM architecture with the following key components:

- **UI Layer**: Jetpack Compose screens (Home, Monitoring, Settings, Performance, Advanced Traffic Regulation)
- **ViewModel Layer**: State management with StateFlow and coroutines
- **Repository Layer**: Data access abstraction for database and preferences
- **Manager Layer**: Business logic for proxy, VPN, hotspot, traffic metering, quota, and filtering
- **Database Layer**: Room database with entities for sessions, stats, and traffic records
- **Service Layer**: Foreground service for proxy server operation

### Proxy Server Architecture

```kotlin
// Multi-protocol proxy support
enum class ProxyType {
    HTTP_HTTPS, SOCKS5, BOTH
}

// Concurrent client handling with quota and filtering
class ProxyServerImpl(
    private val trafficMeter: TrafficMeter,
    private val vpnManager: VpnManager,
    private val quotaManager: QuotaManager,
    private val trafficFilterManager: TrafficFilterManager?
)
```

### Database Schema

The app uses Room database with the following entities:

- **ClientSessionEntity**: Tracks individual connection sessions
- **ClientStatsEntity**: Aggregated statistics per client
- **ServiceSessionEntity**: Tracks service operation periods
- **TrafficRecordEntity**: Detailed traffic records
- **KvStore**: Key-value storage for preferences

## Critical Limitations

### VPN Interference

- **VPNs can block ALL proxy functionality when enabled on some devices**
- **Users may have to disable VPN to use proxy on some devices**
- **This is a fundamental limitation of VPN Unlimited, not our app**

### Android Permissions

- **Cannot read `/proc/net/arp` table** (Permission denied)
- **Cannot access system files** for client detection
- **Must use traffic-based detection** instead

### Network Limitations

- **Emulator testing limited** - Use physical devices
- **Hotspot firewall** - Android may block incoming TCP connections
- **Port restrictions** - Port 80 requires root permissions

## Future Enhancements

### Short Term

- **VPN Compatibility Research** - Find VPN apps that don't block proxy
- **Cross-platform Testing** - iOS, Windows, Mac clients
- **Performance Optimization** - Handle multiple concurrent clients efficiently
- **UI/UX Improvements** - Enhanced user experience and accessibility

### Long Term

- **Cloud Integration** - Firebase for data synchronization across devices
- **User Management** - Client authentication and access control
- **Security Enhancements** - Enhanced encryption and authentication
- **Analytics Dashboard** - Advanced reporting and data visualization
- **Export Features** - Enhanced data export capabilities

## Dependencies

### Core Dependencies

```kotlin
// Core & UI
implementation("androidx.core:core-ktx:1.13.1")
implementation("androidx.activity:activity-compose:1.8.2")
implementation("androidx.compose.ui:ui:1.5.4")
implementation("androidx.compose.material3:material3:1.1.2")
implementation("androidx.compose.material:material-icons-extended:1.5.4")
implementation("androidx.navigation:navigation-compose:2.7.5")

// Dependency Injection
implementation("com.google.dagger:hilt-android:2.51.1")
kapt("com.google.dagger:hilt-compiler:2.51.1")
implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

// Database
implementation("androidx.room:room-runtime:2.6.1")
kapt("androidx.room:room-compiler:2.6.1")
implementation("androidx.room:room-ktx:2.6.1")

// Background Tasks
implementation("androidx.work:work-runtime-ktx:2.9.0")

// Security
implementation("androidx.security:security-crypto:1.1.0-alpha06")

// Networking
implementation("com.squareup.okhttp3:okhttp:4.12.0")

// QR Code
implementation("com.journeyapps:zxing-android-embedded:4.3.0")
```

### Testing Dependencies

```kotlin
testImplementation("junit:junit:4.13.2")
androidTestImplementation("androidx.test.ext:junit:1.1.5")
androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
```

## Contributing

### Development Workflow

1. **Create feature branch** from main
2. **Implement feature** with proper testing
3. **Test thoroughly** on physical devices
4. **Merge to main** after review

### Code Standards

- **Kotlin coding conventions** followed
- **Proper error handling** and logging
- **Documentation** for complex functions
- **MVVM architecture** with StateFlow for state management
- **Dependency injection** using Hilt
- **Room database** for data persistence

## License

This project is being developed for educational purposes as part of CS7980 Research Capstone.

## Support

For technical issues or questions:

- **Review implementation details** in source code
- **Test on physical devices** for network functionality
- **Check logs** using `adb logcat` for debugging
- **Disable VPN** for proxy functionality testing if experiencing issues

## Application Screens

The app includes the following main screens:

- **Home Screen**: Proxy server control, VPN status, hotspot management, QR code generation
- **Monitoring Dashboard**: Real-time client detection, traffic statistics, session tracking
- **Settings**: App configuration, theme preferences, proxy settings, quota configuration
- **Performance Screen**: Real-time system resource monitoring with process-specific CPU tracking (via `/proc/self/stat`), battery level monitoring, network throughput analysis, active connection tracking, and CSV data export for performance analysis
- **Advanced Traffic Regulation**: Quota management, traffic filtering, consumption tracking

---

**Project Status**: Active Development  
**Team**: Carlos Semeho Edorh, Hanchen Ye, Jialu Bi  
**Supervisor**: Maryam Tanha
