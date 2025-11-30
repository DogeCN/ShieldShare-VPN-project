#!/usr/bin/env python3
"""
Test Orchestrator for ShieldShare Load Testing
Coordinates multiple test clients and aggregates results.
"""

import os
import sys
import json
import time
import subprocess
import argparse
from datetime import datetime
from typing import Dict, List
from pathlib import Path
import statistics

class TestOrchestrator:
    """Orchestrates load testing across multiple clients."""
    
    def __init__(self, proxy_host: str, proxy_port: int, output_dir: str = "test-results"):
        self.proxy_host = proxy_host
        self.proxy_port = proxy_port
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(exist_ok=True)
        self.test_start_time = None
        self.test_end_time = None
    
    def run_docker_clients(self, num_clients: int, duration: int, workload_type: str = "concurrent"):
        """Run test clients using Docker Compose."""
        print(f"Starting {num_clients} Docker test clients...")
        
        compose_dir = Path(__file__).parent
        compose_file = compose_dir / 'docker-compose.yml'
        
        # Generate docker-compose.yml if it doesn't exist or if we need different number of clients
        # Check if we need to regenerate
        regenerate = True
        if compose_file.exists():
            # Quick check: count existing clients in file
            with open(compose_file, 'r') as f:
                content = f.read()
                existing_clients = content.count('client')
                if existing_clients >= num_clients:
                    regenerate = False
        
        if regenerate:
            print(f"Generating docker-compose.yml for {num_clients} clients...")
            subprocess.run(
                ['python', 'generate-docker-compose.py',
                 '--clients', str(num_clients),
                 '--proxy-host', self.proxy_host,
                 '--proxy-port', str(self.proxy_port),
                 '--test-duration', str(duration),
                 '--output', 'docker-compose.yml'],
                cwd=compose_dir,
                check=True
            )
        
        env = os.environ.copy()
        env.update({
            'PROXY_HOST': self.proxy_host,
            'PROXY_PORT': str(self.proxy_port),
            'TEST_DURATION': str(duration),
            'WORKLOAD_TYPE': workload_type
        })
        
        # Start clients
        try:
            subprocess.run(
                ['docker-compose', 'up', '-d'],
                cwd=compose_dir,
                env=env,
                check=True
            )
            
            print(f"Started {num_clients} clients. Waiting {duration} seconds...")
            time.sleep(duration + 10)  # Wait for test + buffer
            
            # Stop clients
            subprocess.run(
                ['docker-compose', 'down'],
                cwd=compose_dir,
                check=True
            )
            
        except subprocess.CalledProcessError as e:
            print(f"Error running Docker clients: {e}")
            return False
        
        return True
    
    def run_local_clients(self, num_clients: int, duration: int, workload_type: str = "concurrent"):
        """Run test clients locally (for testing without Docker)."""
        import multiprocessing
        from test_client import ProxyTestClient
        
        print(f"Starting {num_clients} local test clients...")
        
        def run_client(client_id):
            client = ProxyTestClient(client_id, self.proxy_host, self.proxy_port)
            if workload_type == "concurrent":
                client.run_concurrent_workload(duration, concurrency=5)
            else:
                client.run_workload(duration, request_rate=1.0)
            
            results_file = self.output_dir / f"client_{client_id}_results.json"
            client.save_results(str(results_file))
            return results_file
        
        # Run clients in parallel
        with multiprocessing.Pool(processes=min(num_clients, 12)) as pool:
            client_ids = list(range(1, num_clients + 1))
            result_files = pool.map(run_client, client_ids)
        
        return result_files
    
    def collect_results(self) -> List[Dict]:
        """Collect results from all client output files."""
        results = []
        
        # Look for result files
        result_files = list(self.output_dir.glob("client_*_results.json"))
        
        for result_file in result_files:
            try:
                with open(result_file, 'r') as f:
                    data = json.load(f)
                    results.append(data['analysis'])
            except Exception as e:
                print(f"Error reading {result_file}: {e}")
        
        return results
    
    def aggregate_results(self, results: List[Dict]) -> Dict:
        """Aggregate results from all clients."""
        if not results:
            return {}
        
        total_requests = sum(r['total_requests'] for r in results)
        total_successful = sum(r['successful_requests'] for r in results)
        total_failed = sum(r['failed_requests'] for r in results)
        total_bytes_down = sum(r['total_bytes_down'] for r in results)
        total_bytes_up = sum(r.get('total_bytes_up', 0) for r in results)
        
        # Aggregate latencies
        all_latencies = []
        for r in results:
            if 'avg_latency_ms' in r:
                all_latencies.append(r['avg_latency_ms'])
        
        aggregated = {
            'test_timestamp': datetime.now().isoformat(),
            'num_clients': len(results),
            'total_requests': total_requests,
            'total_successful_requests': total_successful,
            'total_failed_requests': total_failed,
            'overall_success_rate': total_successful / total_requests if total_requests > 0 else 0,
            'total_bytes_down_mb': total_bytes_down / 1024 / 1024,
            'total_bytes_up_mb': total_bytes_up / 1024 / 1024,
        }
        
        if all_latencies:
            aggregated.update({
                'avg_latency_ms': statistics.mean(all_latencies),
                'min_latency_ms': min(all_latencies),
                'max_latency_ms': max(all_latencies),
                'median_latency_ms': statistics.median(all_latencies),
                'p95_latency_ms': self._percentile(all_latencies, 95),
                'p99_latency_ms': self._percentile(all_latencies, 99),
            })
        
        # Per-client statistics
        aggregated['per_client_stats'] = results
        
        return aggregated
    
    def _percentile(self, data: List[float], percentile: int) -> float:
        """Calculate percentile."""
        if not data:
            return 0.0
        sorted_data = sorted(data)
        index = int(len(sorted_data) * percentile / 100)
        return sorted_data[min(index, len(sorted_data) - 1)]
    
    def generate_report(self, aggregated: Dict, output_file: str):
        """Generate a human-readable test report."""
        report_lines = [
            "=" * 80,
            "ShieldShare Proxy Load Test Report",
            "=" * 80,
            f"Test Timestamp: {aggregated.get('test_timestamp', 'N/A')}",
            f"Number of Clients: {aggregated.get('num_clients', 0)}",
            "",
            "Overall Statistics:",
            "-" * 80,
            f"Total Requests: {aggregated.get('total_requests', 0):,}",
            f"Successful Requests: {aggregated.get('total_successful_requests', 0):,}",
            f"Failed Requests: {aggregated.get('total_failed_requests', 0):,}",
            f"Success Rate: {aggregated.get('overall_success_rate', 0)*100:.2f}%",
            "",
            "Throughput:",
            "-" * 80,
            f"Total Downloaded: {aggregated.get('total_bytes_down_mb', 0):.2f} MB",
            f"Total Uploaded: {aggregated.get('total_bytes_up_mb', 0):.2f} MB",
            "",
            "Latency Statistics:",
            "-" * 80,
        ]
        
        if 'avg_latency_ms' in aggregated:
            report_lines.extend([
                f"Average Latency: {aggregated['avg_latency_ms']:.2f} ms",
                f"Median Latency: {aggregated['median_latency_ms']:.2f} ms",
                f"Min Latency: {aggregated['min_latency_ms']:.2f} ms",
                f"Max Latency: {aggregated['max_latency_ms']:.2f} ms",
                f"P95 Latency: {aggregated['p95_latency_ms']:.2f} ms",
                f"P99 Latency: {aggregated['p99_latency_ms']:.2f} ms",
            ])
        
        report_lines.extend([
            "",
            "Per-Client Statistics:",
            "-" * 80,
        ])
        
        for client_stat in aggregated.get('per_client_stats', []):
            report_lines.append(
                f"Client {client_stat['client_id']}: "
                f"{client_stat['successful_requests']}/{client_stat['total_requests']} requests, "
                f"Success: {client_stat['success_rate']*100:.1f}%, "
                f"Avg Latency: {client_stat.get('avg_latency_ms', 0):.1f}ms"
            )
        
        report_lines.append("=" * 80)
        
        report_text = "\n".join(report_lines)
        
        with open(output_file, 'w') as f:
            f.write(report_text)
        
        print("\n" + report_text)
        print(f"\nReport saved to: {output_file}")


def main():
    parser = argparse.ArgumentParser(description='ShieldShare Load Test Orchestrator')
    parser.add_argument('--clients', type=int, default=5, help='Number of test clients')
    parser.add_argument('--duration', type=int, default=300, help='Test duration in seconds')
    parser.add_argument('--proxy-host', type=str, default='192.168.43.1', help='Proxy server host')
    parser.add_argument('--proxy-port', type=int, default=8080, help='Proxy server port')
    parser.add_argument('--workload', type=str, default='concurrent', choices=['concurrent', 'sequential'],
                       help='Workload type')
    parser.add_argument('--method', type=str, default='docker', choices=['docker', 'local'],
                       help='Test method (docker or local)')
    parser.add_argument('--output-dir', type=str, default='test-results', help='Output directory for results')
    
    args = parser.parse_args()
    
    print("=" * 80)
    print("ShieldShare Load Test Orchestrator")
    print("=" * 80)
    print(f"Configuration:")
    print(f"  Clients: {args.clients}")
    print(f"  Duration: {args.duration}s")
    print(f"  Proxy: {args.proxy_host}:{args.proxy_port}")
    print(f"  Workload: {args.workload}")
    print(f"  Method: {args.method}")
    print("=" * 80)
    
    orchestrator = TestOrchestrator(args.proxy_host, args.proxy_port, args.output_dir)
    
    # Run test
    if args.method == 'docker':
        success = orchestrator.run_docker_clients(args.clients, args.duration, args.workload)
        if not success:
            print("Failed to run Docker clients")
            sys.exit(1)
    else:
        orchestrator.run_local_clients(args.clients, args.duration, args.workload)
    
    # Collect and aggregate results
    print("\nCollecting results...")
    results = orchestrator.collect_results()
    
    if not results:
        print("No results found!")
        sys.exit(1)
    
    aggregated = orchestrator.aggregate_results(results)
    
    # Save aggregated results
    results_file = orchestrator.output_dir / f"aggregated_results_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
    with open(results_file, 'w') as f:
        json.dump(aggregated, f, indent=2)
    
    # Generate report
    report_file = orchestrator.output_dir / f"test_report_{datetime.now().strftime('%Y%m%d_%H%M%S')}.txt"
    orchestrator.generate_report(aggregated, str(report_file))
    
    print(f"\nTest completed! Results saved to {orchestrator.output_dir}")


if __name__ == '__main__':
    main()

