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
- **Proxy Server** - Multi-protocol proxy (HTTP/HTTPS/SOCKS5)
- **Hotspot Manager** - Android hotspot detection and management
- **VPN Manager** - Third-party VPN detection
- **Traffic Meter** - Network usage monitoring
- **QR Code Generator** - Client configuration automation

### Technology Stack
- **Language**: Kotlin
- **Framework**: Android Jetpack Compose
- **Dependency Injection**: Hilt
- **Architecture**: MVVM with StateFlow
- **Build System**: Gradle
- **Target SDK**: Android 14 (API 34)

## Installation & Setup

### Prerequisites
- Android Studio Arctic Fox or later
- Android SDK 34
- Physical Android device (emulator has network limitations)
- Third-party VPN app (e.g., VPN Unlimited, NordVPN, ExpressVPN)

### Installation Steps

1. **Clone the repository**
   ```bash
   git clone <repository-url>
   cd ShieldShare-VPN-project
   ```

2. **Open in Android Studio**
   - Open the project in Android Studio
   - Wait for Gradle sync to complete

3. **Build the project**
   ```bash
   ./gradlew assembleDebug
   ```

4. **Install on device**
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

### Running the Application

1. **Enable Developer Options** on your Android device
2. **Enable USB Debugging**
3. **Connect device** via USB
4. **Run the app** from Android Studio or install the APK
5. **Grant necessary permissions** (Location, Hotspot, etc.)

## Team Contributions

For detailed information about each team member's contributions, implementation status, and technical achievements, please refer to the individual team progress files:

### Team Progress Files
- **Carlos**: `team-progress/Carlos.md` - Proxy Server & Hotspot Management
- **Hanchen**: `team-progress/Hanchen.md` - VPN Integration & IP Management  
- **Jialu**: `team-progress/Jialu.md` - Traffic Metering & UI Enhancement
- **Integration Points**: `team-progress/TEAM_INTEGRATION_POINTS.md` - Cross-team coordination

### Quick Summary
- **Carlos**: Complete proxy server system, hotspot management, QR code configuration, client detection solution
- **Hanchen**: VPN-agnostic implementation, IP management, Hotspot Accessibility, real-time monitoring
- **Jialu**: Traffic metering with real-time data, UI Design & enhancements (in progress),Comm lead/Note taker,Submission coordinator

### Key Achievements
- **VPN-Agnostic Design**: Works with any third-party VPN app
- **Proxy Server**: Multi-protocol support (HTTP/HTTPS/SOCKS5)
- **Client Detection**: IP-based counting through proxy server connections
- **Auto-Configuration**: PAC files and QR code setup
- **Real-time Monitoring**: Live client detection and traffic accounting

## Current Status

### Working Components
- **Proxy Server** - Starts and listens on port 8080/8081
- **QR Code Generation** - Manual and PAC configuration
- **VPN Status Detection** - Real-time VPN monitoring
- **Hotspot Detection** - Basic client monitoring
- **PAC File Generation** - Dynamic routing configuration
- **Client Detection** - IP-based counting working perfectly

### Known Issues
- **VPN Interference** - VPN must be disabled for proxy functionality on some devices
- **Client Detection Timing** - 1-2 minute delay for full client detection using IP detection approach
- **Permission Restrictions** - Cannot access Hotspot system files for client detection

### Remaining Challenges
- **VPN Compatibility** - Need VPN apps that don't block proxy functionality with some devices
- **Cross-Platform Testing** - iOS, Windows, Mac client testing
- **Performance Optimization** - Handle multiple concurrent clients efficiently

### Technical Challenges
- **Non-Root Constraints** - Android restricts hotspot automation and low-level traffic inspection for security reasons
- **MAC Randomization** - Modern devices often use randomized MAC addresses which may complicate per-user tracking
- **Performance Impact** - Running VPN + hotspot + traffic monitoring increases CPU and battery usage
- **User Adoption** - Connected clients must configure their devices (proxy settings) to ensure traffic flows through the app's accounting system

## Testing Results

### Build Status
```
BUILD SUCCESSFUL in 56s
44 actionable tasks: 44 executed
```

### App Status
- **Running on Device**: YES
- **No Crashes**: YES
- **Port 8080/8081 Listening**: YES
- **UI Functional**: YES

### Test Commands
```bash
# Basic proxy connection test
.\tests\test-proxy-connection.bat

# HTTP proxy protocol test
.\tests\test-http-proxy.bat

# SOCKS5 proxy protocol test
.\tests\test-socks5-proxy.bat

# Port verification
netstat -an | findstr :8080
```

### Log Evidence
```
10-11 17:59:20.125 I ProxyServerImpl: Starting proxy server on port 8080
10-11 17:59:20.126 I DashboardFragment: Proxy server started successfully
10-11 17:59:20.127 D ProxyServerImpl: Connection acceptor started, waiting for connections
```

## Usage Instructions

### For Host Device (Android)
1. **Install ShieldShare app**
2. **Connect to VPN** (third-party app)
3. **Enable mobile hotspot** in Android settings
4. **Start proxy server** in ShieldShare app
5. **Share QR code** with clients

### For Client Devices
1. **Connect to hotspot** (Wi-Fi)
2. **Scan QR code** or configure proxy manually
3. **Configure proxy settings**:
   - Server: [Hotspot IP]
   - Port: 8080/8081
   - Type: HTTP/HTTPS
4. **Test internet access** through proxy

## Technical Implementation

### Proxy Server Architecture
```kotlin
// Multi-protocol proxy support
enum class ProxyType {
    HTTP_HTTPS, SOCKS5, BOTH
}

// Concurrent client handling
private val proxyHandlers = ConcurrentHashMap<String, ProxyHandler>()
private val connectedClients = ConcurrentHashMap<String, Long>()
```

### Client Detection Method
```kotlin
// IP-based client counting
override fun handleClientConnection(socket: Socket) {
    val clientIp = socket.remoteSocketAddress.toString().substringBefore(':')
    connectedClients[clientIp] = System.currentTimeMillis()
    Log.d(TAG, "Tracking client IP: $clientIp (Total: ${connectedClients.size})")
}
```

### VPN Integration Points
```kotlin
// TODO: HANCHEN - VPN tunnel forwarding
private suspend fun forwardThroughVpn(data: ByteArray): ByteArray {
    // Implement VPN tunnel forwarding
}
```

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
- **Performance Optimization** - Handle multiple concurrent clients

### Long Term
- **Advanced Traffic Metering** - Real-time statistics and analytics
- **Cloud Integration** - Firebase for data synchronization
- **User Management** - Client authentication and access control
- **Security Enhancements** - Encryption and authentication

## Dependencies

### Core Dependencies
```kotlin
implementation 'androidx.core:core-ktx:1.12.0'
implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.7.0'
implementation 'androidx.activity:activity-compose:1.8.2'
implementation 'androidx.compose.ui:ui:1.5.4'
implementation 'androidx.compose.material3:material3:1.1.2'
implementation 'androidx.navigation:navigation-compose:2.7.5'
implementation 'com.google.dagger:hilt-android:2.48'
implementation 'com.google.zxing:core:3.5.2'
```

### Testing Dependencies
```kotlin
testImplementation 'junit:junit:4.13.2'
androidTestImplementation 'androidx.test.ext:junit:1.1.5'
androidTestImplementation 'androidx.test.espresso:espresso-core:3.5.1'
```

## Contributing

### Development Workflow
1. **Create feature branch** from main
2. **Implement feature** with proper testing
3. **Update team progress** in `team-progress/` directory
4. **Test thoroughly** on physical devices
5. **Merge to main** after review

### Code Standards
- **Kotlin coding conventions** followed
- **Proper error handling** and logging
- **Documentation** for complex functions
- **Unit tests** for critical components

## License

This project is being developed for educational purposes as part of CS7980 Research Capstone.

## Support

For technical issues or questions:
- **Check team progress files** in `team-progress/` directory
- **Review implementation details** in source code
- **Test on physical devices** for network functionality
- **Disable VPN** for proxy functionality testing

---

**Last Updated**: October 2025  
**Project Status**: Active Development  
**Team**: Carlos Semeho Edorh, Hanchen Ye, Jialu Bi  
**Supervisor**: Maryam Tanha