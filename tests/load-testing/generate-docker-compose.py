#!/usr/bin/env python3
"""
Generate docker-compose.yml for specified number of virtual clients.
"""

import argparse
import sys

def generate_docker_compose(num_clients: int, proxy_host: str = "192.168.43.1", 
                           proxy_port: int = 8080, test_duration: int = 300,
                           base_ip: str = "192.168.43.101"):
    """Generate docker-compose.yml content."""
    
    # Parse base IP to get network and starting host
    ip_parts = base_ip.rsplit('.', 1)
    network_base = ip_parts[0]
    start_host = int(ip_parts[1])
    
    services = []
    
    for i in range(1, num_clients + 1):
        client_id = i
        client_ip = f"{network_base}.{start_host + i - 1}"
        
        service = f"""  client{client_id}:
    image: shieldshare-test-client:latest
    container_name: test-client-{client_id}
    environment:
      - CLIENT_ID={client_id}
      - PROXY_HOST={proxy_host}
      - PROXY_PORT={proxy_port}
      - TEST_DURATION={test_duration}
      - WORKLOAD_TYPE=concurrent
    networks:
      testnet:
        ipv4_address: {client_ip}
    restart: unless-stopped"""
        
        services.append(service)
    
    # Determine subnet from base IP
    subnet_base = '.'.join(network_base.split('.')[:-1])
    gateway = f"{subnet_base}.1"
    
    compose_content = f"""version: '3.8'

services:
{chr(10).join(services)}

networks:
  testnet:
    driver: bridge
    ipam:
      config:
        - subnet: {subnet_base}.0/24
          gateway: {gateway}
"""
    
    return compose_content


def main():
    parser = argparse.ArgumentParser(description='Generate docker-compose.yml for load testing')
    parser.add_argument('--clients', type=int, required=True, help='Number of virtual clients')
    parser.add_argument('--proxy-host', type=str, default='192.168.43.1', help='Proxy server host')
    parser.add_argument('--proxy-port', type=int, default=8080, help='Proxy server port')
    parser.add_argument('--test-duration', type=int, default=300, help='Test duration in seconds')
    parser.add_argument('--base-ip', type=str, default='192.168.43.101', 
                       help='Base IP address for first client')
    parser.add_argument('--output', type=str, default='docker-compose.yml',
                       help='Output file name')
    
    args = parser.parse_args()
    
    if args.clients < 1 or args.clients > 254:
        print("Error: Number of clients must be between 1 and 254")
        sys.exit(1)
    
    print(f"Generating docker-compose.yml for {args.clients} clients...")
    content = generate_docker_compose(
        args.clients,
        args.proxy_host,
        args.proxy_port,
        args.test_duration,
        args.base_ip
    )
    
    output_path = args.output
    with open(output_path, 'w') as f:
        f.write(content)
    
    print(f"Generated {output_path} with {args.clients} client services")
    print(f"Client IPs: {args.base_ip.rsplit('.', 1)[0]}.{int(args.base_ip.rsplit('.', 1)[1])} to {args.base_ip.rsplit('.', 1)[0]}.{int(args.base_ip.rsplit('.', 1)[1]) + args.clients - 1}")


if __name__ == '__main__':
    main()

