#!/bin/bash
# Setup script for ShieldShare load testing

set -e

echo "ShieldShare Load Testing Setup"
echo "================================"

# Check if Docker is installed
if ! command -v docker &> /dev/null; then
    echo "Error: Docker is not installed. Please install Docker first."
    exit 1
fi

# Check if Docker Compose is installed
if ! command -v docker-compose &> /dev/null; then
    echo "Error: Docker Compose is not installed. Please install Docker Compose first."
    exit 1
fi

# Build Docker image
echo "Building test client Docker image..."
docker build -t shieldshare-test-client:latest .

# Create test results directory
mkdir -p test-results

# Make scripts executable
chmod +x test-client.py
chmod +x test-orchestrator.py

echo ""
echo "Setup complete!"
echo ""
echo "Next steps:"
echo "1. Start your ShieldShare proxy server on Android device"
echo "2. Note the hotspot IP address (usually 192.168.43.1)"
echo "3. Run a test:"
echo "   python test-orchestrator.py --clients 5 --duration 300 --proxy-host YOUR_IP"
echo ""

