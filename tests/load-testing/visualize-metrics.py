#!/usr/bin/env python3
"""
Metrics Visualization Tool for ShieldShare Load Testing
Generates charts and graphs from test results.
"""

import json
import argparse
import matplotlib.pyplot as plt
import matplotlib
from pathlib import Path
from typing import Dict, List
import statistics

# Use non-interactive backend for server environments
matplotlib.use('Agg')

def load_results(results_file: str) -> Dict:
    """Load aggregated results from JSON file."""
    with open(results_file, 'r') as f:
        return json.load(f)


def plot_throughput_vs_clients(results_files: List[str], output_file: str):
    """Plot aggregate throughput vs number of clients."""
    client_counts = []
    throughputs = []
    
    for file in results_files:
        data = load_results(file)
        client_counts.append(data.get('num_clients', 0))
        # Calculate throughput in Mbps
        duration = 300  # Assume 5 minutes, adjust if needed
        total_bytes = data.get('total_bytes_down_mb', 0) * 1024 * 1024
        throughput_mbps = (total_bytes * 8) / (duration * 1000000)
        throughputs.append(throughput_mbps)
    
    plt.figure(figsize=(10, 6))
    plt.plot(client_counts, throughputs, marker='o', linewidth=2, markersize=8)
    plt.xlabel('Number of Clients', fontsize=12)
    plt.ylabel('Aggregate Throughput (Mbps)', fontsize=12)
    plt.title('ShieldShare Proxy: Throughput vs Client Count', fontsize=14, fontweight='bold')
    plt.grid(True, alpha=0.3)
    plt.tight_layout()
    plt.savefig(output_file, dpi=300, bbox_inches='tight')
    print(f"Saved throughput chart to {output_file}")


def plot_latency_vs_clients(results_files: List[str], output_file: str):
    """Plot latency percentiles vs number of clients."""
    client_counts = []
    avg_latencies = []
    p95_latencies = []
    p99_latencies = []
    
    for file in results_files:
        data = load_results(file)
        client_counts.append(data.get('num_clients', 0))
        avg_latencies.append(data.get('avg_latency_ms', 0))
        p95_latencies.append(data.get('p95_latency_ms', 0))
        p99_latencies.append(data.get('p99_latency_ms', 0))
    
    plt.figure(figsize=(10, 6))
    plt.plot(client_counts, avg_latencies, marker='o', label='Average', linewidth=2)
    plt.plot(client_counts, p95_latencies, marker='s', label='P95', linewidth=2)
    plt.plot(client_counts, p99_latencies, marker='^', label='P99', linewidth=2)
    plt.xlabel('Number of Clients', fontsize=12)
    plt.ylabel('Latency (ms)', fontsize=12)
    plt.title('ShieldShare Proxy: Latency vs Client Count', fontsize=14, fontweight='bold')
    plt.legend()
    plt.grid(True, alpha=0.3)
    plt.tight_layout()
    plt.savefig(output_file, dpi=300, bbox_inches='tight')
    print(f"Saved latency chart to {output_file}")


def plot_success_rate_vs_clients(results_files: List[str], output_file: str):
    """Plot success rate vs number of clients."""
    client_counts = []
    success_rates = []
    
    for file in results_files:
        data = load_results(file)
        client_counts.append(data.get('num_clients', 0))
        success_rates.append(data.get('overall_success_rate', 0) * 100)
    
    plt.figure(figsize=(10, 6))
    plt.plot(client_counts, success_rates, marker='o', linewidth=2, markersize=8, color='green')
    plt.xlabel('Number of Clients', fontsize=12)
    plt.ylabel('Success Rate (%)', fontsize=12)
    plt.title('ShieldShare Proxy: Success Rate vs Client Count', fontsize=14, fontweight='bold')
    plt.ylim(0, 105)
    plt.grid(True, alpha=0.3)
    plt.tight_layout()
    plt.savefig(output_file, dpi=300, bbox_inches='tight')
    print(f"Saved success rate chart to {output_file}")


def plot_latency_distribution(results_file: str, output_file: str):
    """Plot latency distribution for a single test."""
    data = load_results(results_file)
    
    # Collect all latencies from per-client stats
    all_latencies = []
    for client_stat in data.get('per_client_stats', []):
        # We'd need raw data for distribution, but we can use percentiles
        if 'avg_latency_ms' in client_stat:
            all_latencies.append(client_stat['avg_latency_ms'])
    
    if not all_latencies:
        print("No latency data available for distribution plot")
        return
    
    plt.figure(figsize=(10, 6))
    plt.hist(all_latencies, bins=20, edgecolor='black', alpha=0.7)
    plt.xlabel('Latency (ms)', fontsize=12)
    plt.ylabel('Frequency', fontsize=12)
    plt.title(f'Latency Distribution ({data.get("num_clients", 0)} clients)', fontsize=14, fontweight='bold')
    plt.grid(True, alpha=0.3, axis='y')
    plt.tight_layout()
    plt.savefig(output_file, dpi=300, bbox_inches='tight')
    print(f"Saved latency distribution to {output_file}")


def plot_comprehensive_comparison(results_files: List[str], output_file: str):
    """Create a comprehensive comparison chart with multiple subplots."""
    fig, axes = plt.subplots(2, 2, figsize=(14, 10))
    fig.suptitle('ShieldShare Proxy: Comprehensive Performance Analysis', fontsize=16, fontweight='bold')
    
    client_counts = []
    throughputs = []
    avg_latencies = []
    p95_latencies = []
    success_rates = []
    
    for file in results_files:
        data = load_results(file)
        client_counts.append(data.get('num_clients', 0))
        
        # Throughput
        duration = 300
        total_bytes = data.get('total_bytes_down_mb', 0) * 1024 * 1024
        throughput_mbps = (total_bytes * 8) / (duration * 1000000)
        throughputs.append(throughput_mbps)
        
        # Latency
        avg_latencies.append(data.get('avg_latency_ms', 0))
        p95_latencies.append(data.get('p95_latency_ms', 0))
        
        # Success rate
        success_rates.append(data.get('overall_success_rate', 0) * 100)
    
    # Throughput
    axes[0, 0].plot(client_counts, throughputs, marker='o', linewidth=2, color='blue')
    axes[0, 0].set_xlabel('Number of Clients')
    axes[0, 0].set_ylabel('Throughput (Mbps)')
    axes[0, 0].set_title('Aggregate Throughput')
    axes[0, 0].grid(True, alpha=0.3)
    
    # Latency
    axes[0, 1].plot(client_counts, avg_latencies, marker='o', label='Avg', linewidth=2)
    axes[0, 1].plot(client_counts, p95_latencies, marker='s', label='P95', linewidth=2)
    axes[0, 1].set_xlabel('Number of Clients')
    axes[0, 1].set_ylabel('Latency (ms)')
    axes[0, 1].set_title('Response Latency')
    axes[0, 1].legend()
    axes[0, 1].grid(True, alpha=0.3)
    
    # Success Rate
    axes[1, 0].plot(client_counts, success_rates, marker='o', linewidth=2, color='green')
    axes[1, 0].set_xlabel('Number of Clients')
    axes[1, 0].set_ylabel('Success Rate (%)')
    axes[1, 0].set_title('Request Success Rate')
    axes[1, 0].set_ylim(0, 105)
    axes[1, 0].grid(True, alpha=0.3)
    
    # Efficiency (throughput per client)
    efficiency = [t / c if c > 0 else 0 for t, c in zip(throughputs, client_counts)]
    axes[1, 1].plot(client_counts, efficiency, marker='o', linewidth=2, color='red')
    axes[1, 1].set_xlabel('Number of Clients')
    axes[1, 1].set_ylabel('Throughput per Client (Mbps)')
    axes[1, 1].set_title('Per-Client Efficiency')
    axes[1, 1].grid(True, alpha=0.3)
    
    plt.tight_layout()
    plt.savefig(output_file, dpi=300, bbox_inches='tight')
    print(f"Saved comprehensive comparison to {output_file}")


def main():
    parser = argparse.ArgumentParser(description='Visualize ShieldShare load test results')
    parser.add_argument('--results-dir', type=str, default='test-results',
                       help='Directory containing test results')
    parser.add_argument('--output-dir', type=str, default='charts',
                       help='Output directory for charts')
    parser.add_argument('--type', type=str, choices=['throughput', 'latency', 'success', 'distribution', 'all', 'comprehensive'],
                       default='all', help='Type of chart to generate')
    
    args = parser.parse_args()
    
    results_dir = Path(args.results_dir)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(exist_ok=True)
    
    # Find all aggregated result files
    results_files = sorted(results_dir.glob('aggregated_results_*.json'))
    
    if not results_files:
        print(f"No aggregated results found in {results_dir}")
        return
    
    print(f"Found {len(results_files)} result files")
    
    if args.type == 'all':
        plot_throughput_vs_clients(results_files, str(output_dir / 'throughput_vs_clients.png'))
        plot_latency_vs_clients(results_files, str(output_dir / 'latency_vs_clients.png'))
        plot_success_rate_vs_clients(results_files, str(output_dir / 'success_rate_vs_clients.png'))
        if results_files:
            plot_latency_distribution(str(results_files[-1]), str(output_dir / 'latency_distribution.png'))
    elif args.type == 'comprehensive':
        plot_comprehensive_comparison(results_files, str(output_dir / 'comprehensive_comparison.png'))
    elif args.type == 'throughput':
        plot_throughput_vs_clients(results_files, str(output_dir / 'throughput_vs_clients.png'))
    elif args.type == 'latency':
        plot_latency_vs_clients(results_files, str(output_dir / 'latency_vs_clients.png'))
    elif args.type == 'success':
        plot_success_rate_vs_clients(results_files, str(output_dir / 'success_rate_vs_clients.png'))
    elif args.type == 'distribution':
        if results_files:
            plot_latency_distribution(str(results_files[-1]), str(output_dir / 'latency_distribution.png'))
    
    print(f"\nCharts saved to {output_dir}")


if __name__ == '__main__':
    try:
        import matplotlib.pyplot as plt
    except ImportError:
        print("Error: matplotlib is not installed. Install it with: pip install matplotlib")
        exit(1)
    
    main()

