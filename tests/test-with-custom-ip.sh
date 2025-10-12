#!/bin/bash

# ShieldShare Proxy Test with Custom IP
# Usage: ./test-with-custom-ip.sh [IP_ADDRESS] [PORT]

# Default values
DEFAULT_IP="10.0.2.2"
DEFAULT_PORT="8080"

# Get IP and port from command line arguments or use defaults
PROXY_IP=${1:-$DEFAULT_IP}
PROXY_PORT=${2:-$DEFAULT_PORT}

echo "========================================"
echo "   ShieldShare Proxy Test (Custom IP)"
echo "========================================"
echo

echo "Testing HTTP proxy with custom IP..."
echo "Proxy: $PROXY_IP:$PROXY_PORT"
echo "Target: httpbin.org/ip"
echo

echo "This will test the HTTP proxy protocol:"
echo "1. Client connects to proxy"
echo "2. Client sends HTTP request"
echo "3. Proxy forwards request to target"
echo "4. Proxy returns response to client"
echo

echo "Starting HTTP proxy test..."
curl -x http://$PROXY_IP:$PROXY_PORT http://httpbin.org/ip --connect-timeout 15 --max-time 30 -v

echo
echo "========================================"
echo "HTTP Proxy Test Completed!"
echo "========================================"
echo "Press any key to continue..."
read -n 1
