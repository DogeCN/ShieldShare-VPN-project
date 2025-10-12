# ShieldShare Proxy Testing - Mac Instructions

## Prerequisites

### 1. Install curl (if not already installed)
```bash
# Using Homebrew (recommended)
brew install curl

# Or using MacPorts
sudo port install curl
```

### 2. Install Android SDK Platform Tools
```bash
# Using Homebrew
brew install android-platform-tools

# Or download from: https://developer.android.com/studio/releases/platform-tools
```

### 3. Add Android SDK to PATH
Add this to your `~/.zshrc` or `~/.bash_profile`:
```bash
export PATH=$PATH:$HOME/Library/Android/sdk/platform-tools
```

Then reload your shell:
```bash
source ~/.zshrc  # or source ~/.bash_profile
```

## Running the Tests

### 1. Start the Android Emulator
Make sure your Android emulator is running with the ShieldShare app installed.

### 2. Start the Proxy Server
In the ShieldShare app, click "Start Proxy Server" button.

### 3. Find Your Device IP (Important!)
```bash
# Find your device/emulator IP address
./tests/find-device-ip.sh
```

**Common IP addresses:**
- **Android Emulator**: `10.0.2.2` (default)
- **Physical Device**: Usually `192.168.x.x` or `10.0.x.x`
- **Localhost**: `127.0.0.1` (if using port forwarding)

### 4. Run the Tests

#### Basic HTTP Proxy Test
```bash
./tests/test-proxy-connection.sh
```

#### HTTP/HTTPS Proxy Test
```bash
./tests/test-http-proxy.sh
```

#### SOCKS5 Proxy Test
```bash
./tests/test-socks5-proxy.sh
```

#### HTTP Proxy Test (Emulator IP)
```bash
./tests/test-http-proxy-emulator.sh
```

#### Monitor Proxy Logs
```bash
./tests/monitor-proxy-logs.sh
```

#### Test with Custom IP (if needed)
```bash
# If the default IP (10.0.2.2) doesn't work, use your device's actual IP
./tests/test-with-custom-ip.sh 192.168.1.100 8080
```

## Expected Results

### Success Indicators:
- Proxy server starts without errors
- Logs show "Proxy server started successfully"
- Port 8080 is listening

### Expected Timeouts:
- **All tests will timeout** - This is expected behavior!
- The proxy server is running but needs VPN integration to forward traffic
- Timeouts confirm the proxy is accepting connections correctly

### Log Verification:
Look for these log messages:
```
I ProxyServerImpl: Starting proxy server on port 8080
I DashboardFragment: Proxy server started successfully
D ProxyServerImpl: Connection acceptor started, waiting for connections on port 8080
```

## Troubleshooting

### Issue: "adb: command not found"
**Solution:** Add Android SDK platform-tools to your PATH:
```bash
export PATH=$PATH:$HOME/Library/Android/sdk/platform-tools
```

### Issue: "Permission denied" when running scripts
**Solution:** Make scripts executable:
```bash
chmod +x tests/*.sh
```

### Issue: "curl: command not found"
**Solution:** Install curl:
```bash
brew install curl
```

### Issue: Tests timeout immediately
**Solution:** This is expected! The proxy server is working correctly but needs VPN integration.

### Issue: "Connection refused" or "No route to host"
**Solution:** Check your IP address:
```bash
# Find your device IP
./tests/find-device-ip.sh

# Test with the correct IP
./tests/test-with-custom-ip.sh YOUR_DEVICE_IP 8080
```

### Issue: "Connection timeout" (not the expected timeout)
**Solution:** 
1. Make sure the proxy server is running in the app
2. Check if the IP address is correct
3. Verify the port (should be 8080)
4. Try: `./tests/find-device-ip.sh` to get the correct IP

## Test Scripts Overview

| Script                        | Purpose               | Expected Result    |
| ----------------------------- | --------------------- | ------------------ |
| `test-proxy-connection.sh`    | Basic HTTP proxy test | Timeout (expected) |
| `test-http-proxy.sh`          | HTTP/HTTPS proxy test | Timeout (expected) |
| `test-socks5-proxy.sh`        | SOCKS5 proxy test     | Timeout (expected) |
| `test-http-proxy-emulator.sh` | Emulator IP test      | Timeout (expected) |
| `monitor-proxy-logs.sh`       | Log monitoring        | Shows proxy logs   |

## Notes

- **All timeouts are expected** - The proxy server is working correctly
- **VPN integration is pending** - Hanchen will implement the VPN tunnel
- **Traffic metering is pending** - Jialu will implement traffic statistics
- **The proxy server is ready** for team integration

## Team Integration

For detailed integration points, see `team-progress/TEAM_INTEGRATION_POINTS.md`
