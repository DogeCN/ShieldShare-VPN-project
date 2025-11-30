#!/usr/bin/env python3
"""
ShieldShare Proxy Test Client
Simulates a client device connecting through the proxy server.
"""

import os
import sys
import time
import json
import statistics
import requests
from datetime import datetime
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Dict, List, Optional
import signal

class ProxyTestClient:
    """Test client that connects through ShieldShare proxy server."""
    
    def __init__(self, client_id: int, proxy_host: str, proxy_port: int):
        self.client_id = client_id
        self.proxy_host = proxy_host
        self.proxy_port = proxy_port
        self.proxy = {
            'http': f'http://{proxy_host}:{proxy_port}',
            'https': f'http://{proxy_host}:{proxy_port}'
        }
        self.metrics = {
            'client_id': client_id,
            'start_time': None,
            'end_time': None,
            'requests': [],
            'errors': [],
            'total_bytes_up': 0,
            'total_bytes_down': 0,
            'connection_times': [],
            'response_times': []
        }
        self.running = True
        signal.signal(signal.SIGTERM, self._signal_handler)
        signal.signal(signal.SIGINT, self._signal_handler)
    
    def _signal_handler(self, signum, frame):
        """Handle shutdown signals gracefully."""
        self.running = False
    
    def test_connection(self) -> Dict:
        """Test basic proxy connection."""
        start = time.time()
        try:
            response = requests.get(
                'https://www.google.com',
                proxies=self.proxy,
                timeout=10,
                verify=False  # For testing only
            )
            connection_time = (time.time() - start) * 1000
            return {
                'success': True,
                'connection_time_ms': connection_time,
                'status_code': response.status_code,
                'bytes': len(response.content)
            }
        except Exception as e:
            return {
                'success': False,
                'error': str(e),
                'connection_time_ms': (time.time() - start) * 1000
            }
    
    def test_http_request(self, url: str) -> Dict:
        """Test a single HTTP request through proxy."""
        start = time.time()
        try:
            response = requests.get(
                url,
                proxies=self.proxy,
                timeout=30,
                verify=False
            )
            latency = (time.time() - start) * 1000
            bytes_transferred = len(response.content)
            
            return {
                'success': True,
                'url': url,
                'latency_ms': latency,
                'status_code': response.status_code,
                'bytes': bytes_transferred,
                'timestamp': time.time()
            }
        except requests.exceptions.Timeout:
            return {
                'success': False,
                'url': url,
                'error': 'timeout',
                'latency_ms': (time.time() - start) * 1000,
                'timestamp': time.time()
            }
        except Exception as e:
            return {
                'success': False,
                'url': url,
                'error': str(e),
                'latency_ms': (time.time() - start) * 1000,
                'timestamp': time.time()
            }
    
    def test_file_download(self, url: str) -> Dict:
        """Test downloading a file through proxy."""
        start = time.time()
        bytes_downloaded = 0
        try:
            response = requests.get(
                url,
                proxies=self.proxy,
                timeout=60,
                stream=True,
                verify=False
            )
            
            for chunk in response.iter_content(chunk_size=8192):
                if chunk:
                    bytes_downloaded += len(chunk)
            
            duration = (time.time() - start) * 1000
            throughput = (bytes_downloaded * 8) / (duration / 1000) / 1000000  # Mbps
            
            return {
                'success': True,
                'url': url,
                'duration_ms': duration,
                'bytes': bytes_downloaded,
                'throughput_mbps': throughput,
                'timestamp': time.time()
            }
        except Exception as e:
            return {
                'success': False,
                'url': url,
                'error': str(e),
                'duration_ms': (time.time() - start) * 1000,
                'timestamp': time.time()
            }
    
    def run_workload(self, duration_seconds: int, request_rate: float = 1.0):
        """Run a continuous workload for specified duration."""
        self.metrics['start_time'] = datetime.now().isoformat()
        end_time = time.time() + duration_seconds
        
        # Test URLs - mix of small and large responses
        test_urls = [
            'https://www.google.com',
            'https://www.example.com',
            'https://httpbin.org/json',
            'https://httpbin.org/html',
            'https://httpbin.org/bytes/1024',  # 1KB
            'https://httpbin.org/bytes/10240',  # 10KB
        ]
        
        url_index = 0
        request_count = 0
        
        print(f"[Client {self.client_id}] Starting workload for {duration_seconds}s...")
        
        while time.time() < end_time and self.running:
            url = test_urls[url_index % len(test_urls)]
            url_index += 1
            
            # Test connection
            result = self.test_http_request(url)
            self.metrics['requests'].append(result)
            
            if result['success']:
                self.metrics['total_bytes_down'] += result.get('bytes', 0)
                self.metrics['response_times'].append(result['latency_ms'])
            else:
                self.metrics['errors'].append(result)
            
            request_count += 1
            
            # Rate limiting
            sleep_time = 1.0 / request_rate
            time.sleep(sleep_time)
        
        self.metrics['end_time'] = datetime.now().isoformat()
        print(f"[Client {self.client_id}] Completed {request_count} requests")
    
    def run_concurrent_workload(self, duration_seconds: int, concurrency: int = 5):
        """Run concurrent requests to simulate real browsing behavior."""
        self.metrics['start_time'] = datetime.now().isoformat()
        end_time = time.time() + duration_seconds
        
        test_urls = [
            'https://www.google.com',
            'https://www.example.com',
            'https://httpbin.org/json',
            'https://httpbin.org/html',
            'https://httpbin.org/bytes/1024',
            'https://httpbin.org/bytes/10240',
        ]
        
        print(f"[Client {self.client_id}] Starting concurrent workload ({concurrency} concurrent)...")
        
        with ThreadPoolExecutor(max_workers=concurrency) as executor:
            while time.time() < end_time and self.running:
                futures = []
                for _ in range(concurrency):
                    url = test_urls[hash(f"{self.client_id}_{time.time()}") % len(test_urls)]
                    future = executor.submit(self.test_http_request, url)
                    futures.append(future)
                
                # Wait for batch to complete
                for future in as_completed(futures):
                    try:
                        result = future.result()
                        self.metrics['requests'].append(result)
                        
                        if result['success']:
                            self.metrics['total_bytes_down'] += result.get('bytes', 0)
                            self.metrics['response_times'].append(result['latency_ms'])
                        else:
                            self.metrics['errors'].append(result)
                    except Exception as e:
                        self.metrics['errors'].append({
                            'error': str(e),
                            'timestamp': time.time()
                        })
                
                time.sleep(0.5)  # Small delay between batches
        
        self.metrics['end_time'] = datetime.now().isoformat()
    
    def analyze_results(self) -> Dict:
        """Analyze collected metrics and generate summary."""
        requests = self.metrics['requests']
        successful = [r for r in requests if r.get('success', False)]
        errors = self.metrics['errors']
        
        response_times = self.metrics['response_times']
        
        analysis = {
            'client_id': self.client_id,
            'total_requests': len(requests),
            'successful_requests': len(successful),
            'failed_requests': len(errors),
            'success_rate': len(successful) / len(requests) if requests else 0,
            'total_bytes_down': self.metrics['total_bytes_down'],
            'total_bytes_up': self.metrics['total_bytes_up'],
        }
        
        if response_times:
            analysis.update({
                'avg_latency_ms': statistics.mean(response_times),
                'min_latency_ms': min(response_times),
                'max_latency_ms': max(response_times),
                'median_latency_ms': statistics.median(response_times),
                'p95_latency_ms': self._percentile(response_times, 95),
                'p99_latency_ms': self._percentile(response_times, 99),
                'std_dev_latency_ms': statistics.stdev(response_times) if len(response_times) > 1 else 0,
            })
        
        # Calculate throughput
        if self.metrics['start_time'] and self.metrics['end_time']:
            start = datetime.fromisoformat(self.metrics['start_time'])
            end = datetime.fromisoformat(self.metrics['end_time'])
            duration = (end - start).total_seconds()
            if duration > 0:
                analysis['avg_throughput_mbps'] = (self.metrics['total_bytes_down'] * 8) / duration / 1000000
        
        return analysis
    
    def _percentile(self, data: List[float], percentile: int) -> float:
        """Calculate percentile of a list."""
        if not data:
            return 0.0
        sorted_data = sorted(data)
        index = int(len(sorted_data) * percentile / 100)
        return sorted_data[min(index, len(sorted_data) - 1)]
    
    def save_results(self, output_file: str):
        """Save results to JSON file."""
        analysis = self.analyze_results()
        output = {
            'analysis': analysis,
            'raw_metrics': self.metrics
        }
        
        with open(output_file, 'w') as f:
            json.dump(output, f, indent=2)
        
        print(f"[Client {self.client_id}] Results saved to {output_file}")


def main():
    """Main entry point for test client."""
    client_id = int(os.getenv('CLIENT_ID', '1'))
    proxy_host = os.getenv('PROXY_HOST', '192.168.43.1')
    proxy_port = int(os.getenv('PROXY_PORT', '8080'))
    test_duration = int(os.getenv('TEST_DURATION', '300'))
    workload_type = os.getenv('WORKLOAD_TYPE', 'concurrent')
    concurrency = int(os.getenv('CONCURRENCY', '5'))
    
    print(f"[Client {client_id}] Initializing...")
    print(f"  Proxy: {proxy_host}:{proxy_port}")
    print(f"  Duration: {test_duration}s")
    print(f"  Workload: {workload_type}")
    
    client = ProxyTestClient(client_id, proxy_host, proxy_port)
    
    # Test connection first
    print(f"[Client {client_id}] Testing connection...")
    conn_test = client.test_connection()
    if not conn_test['success']:
        print(f"[Client {client_id}] Connection test failed: {conn_test.get('error')}")
        sys.exit(1)
    print(f"[Client {client_id}] Connection successful ({conn_test['connection_time_ms']:.2f}ms)")
    
    # Run workload
    if workload_type == 'concurrent':
        client.run_concurrent_workload(test_duration, concurrency)
    else:
        client.run_workload(test_duration, request_rate=1.0)
    
    # Analyze and save results
    results_file = f"/tmp/client_{client_id}_results.json"
    client.save_results(results_file)
    
    analysis = client.analyze_results()
    print(f"\n[Client {client_id}] Test Summary:")
    print(f"  Total Requests: {analysis['total_requests']}")
    print(f"  Success Rate: {analysis['success_rate']*100:.2f}%")
    print(f"  Avg Latency: {analysis.get('avg_latency_ms', 0):.2f}ms")
    print(f"  P95 Latency: {analysis.get('p95_latency_ms', 0):.2f}ms")
    print(f"  Total Downloaded: {analysis['total_bytes_down'] / 1024 / 1024:.2f} MB")


if __name__ == '__main__':
    main()

