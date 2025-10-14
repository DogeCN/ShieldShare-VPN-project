#!/bin/bash

echo "========================================"
echo "   ShieldShare Proxy Server Log Monitor"
echo "========================================"
echo "Monitoring proxy server logs..."
echo "Look for these messages when you click \"Start Proxy Server\":"
echo "- \"Proxy server started successfully\""
echo "- \"ProxyServerImpl: HTTP/HTTPS Proxy server started\""
echo "- \"ProxyServerImpl: SOCKS5 Proxy server started\""
echo "Press Ctrl+C to stop monitoring"
echo

# Check if adb is available
if ! command -v adb &> /dev/null; then
    echo "Error: adb not found. Please make sure Android SDK platform-tools is in your PATH"
    echo "You can add it by running:"
    echo "export PATH=\$PATH:\$HOME/Library/Android/sdk/platform-tools"
    echo
    echo "Press any key to continue..."
    read -n 1
    exit 1
fi

adb logcat -d | grep -i "ProxyServer HttpProxyHandler Socks5ProxyHandler DashboardFragment"
