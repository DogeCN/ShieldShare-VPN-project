# ShieldShare Load Testing Framework

This directory contains tools for load testing the ShieldShare proxy server with multiple concurrent clients.

## Overview

The load testing framework allows you to:
- Simulate multiple clients (1, 5, 10, 30, 60) connecting through the proxy
- Each client appears with a unique IP address
- Collect comprehensive metrics (throughput, latency, error rates)
- Generate aggregated test reports

## Prerequisites

### Docker Setup (Recommended)
1. Install Docker and Docker Compose
2. Build the test client image:
   ```bash
   docker build -t shieldshare-test-client:latest .
   ```

### Python Setup (Alternative)
1. Python 3.8+
2. Install dependencies:
   ```bash
   pip install requests
   ```

## Quick Start

### 1. Start Proxy Server
On your Android device:
- Enable hotspot
- Start ShieldShare app
- Start proxy server
- Note the hotspot IP address (usually 192.168.43.1)

### 2. Configure Test
Edit `docker-compose.yml` or command-line arguments to set:
- `PROXY_HOST`: Your hotspot IP (default: 192.168.43.1)
- `PROXY_PORT`: Proxy port (default: 8080)

### 3. Run Test

#### Using Docker (Recommended)
```bash
# Test with 5 clients for 5 minutes
python test-orchestrator.py --clients 5 --duration 300 --method docker

# Test with 30 clients for 10 minutes
python test-orchestrator.py --clients 30 --duration 600 --method docker

# Test with 60 clients for 15 minutes
python test-orchestrator.py --clients 60 --duration 900 --method docker
```

#### Using Local Python (No Docker)
```bash
# Test with 5 clients
python test-orchestrator.py --clients 5 --duration 300 --method local
```

## Test Scenarios

### Scenario 1: Baseline (1 client)
```bash
python test-orchestrator.py --clients 1 --duration 300
```

### Scenario 2: Light Load (5 clients)
```bash
python test-orchestrator.py --clients 5 --duration 600
```

### Scenario 3: Medium Load (10 clients)
```bash
python test-orchestrator.py --clients 10 --duration 900
```

### Scenario 4: Heavy Load (30 clients)
```bash
python test-orchestrator.py --clients 30 --duration 1200
```

### Scenario 5: Stress Test (60 clients)
```bash
python test-orchestrator.py --clients 60 --duration 1800
```

## Understanding Results

### Output Files
- `client_X_results.json`: Individual client metrics
- `aggregated_results_TIMESTAMP.json`: Combined metrics from all clients
- `test_report_TIMESTAMP.txt`: Human-readable report

### Key Metrics
- **Success Rate**: Percentage of successful requests
- **Average Latency**: Mean response time
- **P95/P99 Latency**: 95th/99th percentile latency (important for user experience)
- **Throughput**: Total data transferred
- **Per-Client Stats**: Individual client performance

## Scaling to 60 Clients

### Testing Strategy
- **1 client**: Use 1 physical device (manual testing)
- **5 clients**: Use 5 physical devices (manual testing)
- **10, 30, 60 clients**: All virtual clients (Docker containers)

### Option 1: Single Machine (Recommended)
Run all virtual clients on a single powerful machine:
```bash
# 10 clients
python test-orchestrator.py --clients 10 --duration 900

# 30 clients
python test-orchestrator.py --clients 30 --duration 1200

# 60 clients
python test-orchestrator.py --clients 60 --duration 1800
```

### Option 2: Distributed Testing (Optional)
If single machine doesn't have enough resources, distribute across multiple machines:
```bash
# On Machine 1
python test-orchestrator.py --clients 20 --duration 1800

# On Machine 2
python test-orchestrator.py --clients 20 --duration 1800

# On Machine 3
python test-orchestrator.py --clients 20 --duration 1800
```

Then aggregate results manually or use a centralized collector.

## Customization

### Modify Workload
Edit `test-client.py` to change:
- Test URLs
- Request patterns
- Concurrent connections per client
- Request rate

### Modify Client Count
The orchestrator automatically generates `docker-compose.yml` for the specified number of clients. 
You can also manually generate it:
```bash
python generate-docker-compose.py --clients 60 --proxy-host 192.168.43.1
```

### Custom IP Addresses
Edit `docker-compose.yml` network configuration to assign specific IPs:
```yaml
networks:
  testnet:
    ipam:
      config:
        - subnet: 192.168.43.0/24
```

## Troubleshooting

### Clients Can't Connect
1. Verify proxy server is running on Android device
2. Check hotspot IP address is correct
3. Ensure firewall allows connections
4. Test with single client first: `--clients 1`

### Docker Issues
1. Ensure Docker is running: `docker ps`
2. Check Docker network: `docker network ls`
3. View client logs: `docker-compose logs client1`

### High Resource Usage
- Reduce concurrent connections per client
- Reduce test duration
- Run fewer clients per machine
- Use lighter test workload

## Advanced Usage

### Custom Test URLs
Modify `test-client.py` `test_urls` list to test specific websites or endpoints.

### Performance Profiling
Add timing instrumentation to measure:
- Connection establishment time
- First byte time (TTFB)
- Total transfer time

### Network Monitoring
Use tools like `tcpdump` or `wireshark` to monitor network traffic during tests.

## Integration with Android App

The Android app's `PerformanceMonitor` class can be extended to:
- Export metrics during tests
- Correlate server-side metrics with client-side metrics
- Provide real-time monitoring dashboard

## Next Steps

1. **Baseline Testing**: Run 1-client test to establish baseline
2. **Scale Testing**: Gradually increase client count (5, 10, 30, 60)
3. **Analysis**: Compare metrics across different scales
4. **Optimization**: Identify bottlenecks and optimize
5. **Report Generation**: Create conference-ready visualizations

## Notes

- Virtual clients use Docker network namespaces for IP isolation
- Each client gets a unique IP from the 192.168.43.0/24 subnet
- Test duration should be long enough to collect meaningful statistics (minimum 5 minutes)
- For realistic testing, use mixed workloads (web browsing, file downloads, streaming)

