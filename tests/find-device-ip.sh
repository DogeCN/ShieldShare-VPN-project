#!/bin/bash

echo "========================================"
echo "   Find Your Device/Emulator IP"
echo "========================================"
echo

echo "This script helps you find the correct IP address for testing."
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

echo "1. Checking connected devices..."
adb devices
echo

echo "2. Getting device IP address..."
DEVICE_IP=$(adb shell ip route | grep wlan | awk '{print $9}' | head -1)

if [ -z "$DEVICE_IP" ]; then
    echo "Could not automatically detect device IP."
    echo "Please check manually:"
    echo "  - For emulator: Usually 10.0.2.2"
    echo "  - For physical device: Check your router's admin panel"
    echo "  - Or run: adb shell ip route"
else
    echo "Detected device IP: $DEVICE_IP"
    echo
    echo "You can now test with:"
    echo "  ./test-with-custom-ip.sh $DEVICE_IP 8080"
fi

echo
echo "3. Common IP addresses:"
echo "  - Android Emulator: 10.0.2.2"
echo "  - Physical Device: Usually 192.168.x.x or 10.0.x.x"
echo "  - Localhost (if using port forwarding): 127.0.0.1"
echo

echo "Press any key to continue..."
read -n 1
