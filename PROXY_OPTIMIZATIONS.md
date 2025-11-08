# Proxy Server Performance Optimizations

This document summarizes all performance optimizations applied to the ShieldShare VPN proxy server to improve throughput, reduce latency, and handle concurrent connections more efficiently.

## Overview

The proxy server handles HTTP/HTTPS and SOCKS5 proxy requests from client devices, forwarding traffic through a VPN tunnel. Initial performance issues included:

- Slow page loading (30+ seconds for concurrent pages)
- Sequential connection processing
- Connection timeouts and failures
- Resource exhaustion under load

## Key Optimizations

### 1. Connection Management

#### Connection Limits

- **Increased concurrent handler limit**: 50 → 200 handlers
- **Increased per-client handler limit**: 5 → 60 handlers
- **Rationale**: Modern browsers open 6-8 connections per page, so higher limits are needed for multiple concurrent pages

#### Connection Acceptance

- **Non-blocking connection handling**: Moved `handleClientConnection` into `serviceScope.launch(Dispatchers.IO)` to allow truly concurrent connection acceptance
- **Increased ServerSocket backlog**: 50 → 500 pending connections
- **Reduced accept timeout**: 30s → 1s (allows graceful shutdown checks)
- **O(1) handler counting**: Introduced `handlersPerClient` map for constant-time lookup instead of O(N) iteration

#### Handler Cleanup

- **Timeout-based cleanup**: Removes handlers stuck for >2 minutes
- **Aggressive cleanup threshold**: 90% of max handlers (prevents resource exhaustion)
- **Cleanup frequency**: Every 30 seconds
- **Atomic counter management**: Helper functions ensure consistent handler removal

### 2. Socket Optimizations

#### Buffer Sizes

- **Read/write buffer**: 8KB → 64KB (reduces system calls)
- **Socket receive buffer**: Default → 128KB (improves throughput)
- **Socket send buffer**: Default → 128KB (improves throughput)
- **Rationale**: Larger buffers reduce system call overhead and improve I/O efficiency

#### TCP Settings

- **TCP_NODELAY enabled**: Disables Nagle's algorithm for lower latency
- **Keep-alive enabled**: Allows connection reuse for multiple requests
- **Optimized timeouts**:
  - Initial request: 10 seconds (fail fast on stuck connections)
  - Tunneling: 5 seconds (allows TLS handshake but fails fast on stuck connections)
  - Target connection: 8 seconds

### 3. I/O Performance

#### Flush Strategy

- **Reduced flush frequency**: Flush every 4 reads (256KB) instead of every read
- **Rationale**: Reduces system call overhead while maintaining responsiveness
- **Final flush**: Always flush at end of transfer

#### Asynchronous Operations

- **Traffic accounting**: Moved to `scope.launch` to avoid blocking data transfer
- **Connection handling**: All operations run on `Dispatchers.IO` for true concurrency
- **Removed redundant context switches**: Eliminated unnecessary `withContext` calls

### 4. HTTP Proxy Handler Optimizations

#### Keep-Alive Support

- **HTTP keep-alive**: Reuses connections for multiple requests (up to 50 requests per connection)
- **Connection state management**: Properly tracks connection lifecycle
- **Request result handling**: Better state management with `RequestResult` enum

#### HTTPS CONNECT Handling

- **Bidirectional tunneling**: Separate coroutines for upload and download
- **Coroutine synchronization**: Uses `coroutineScope` to ensure proper cancellation (prevents barrier crashes)
- **Timeout handling**: Graceful handling of read timeouts during tunneling

#### Protocol Detection

- **Timeout for detection**: 2-second timeout prevents indefinite blocking
- **Non-blocking**: Protocol detection doesn't block connection acceptance

### 5. SOCKS5 Proxy Handler Optimizations

#### Authentication & Connection

- **Optimized socket settings**: Same optimizations as HTTP proxy
- **Efficient connection establishment**: Proper timeout handling

#### Tunneling

- **Same optimizations as HTTP**: Buffer sizes, flush strategy, timeout handling
- **Coroutine synchronization**: Fixed using `coroutineScope` instead of `joinAll`

### 6. Error Handling & Resilience

#### Exception Handling

- **Comprehensive error handling**: All exception types properly caught and handled
- **Graceful degradation**: Failed connections don't crash the server
- **Resource cleanup**: `finally` blocks ensure sockets are always closed

#### Timeout Management

- **Fail-fast timeouts**: Shorter timeouts prevent resource exhaustion
- **Timeout restoration**: Original timeouts restored after operations
- **Explicit timeout handling**: `SocketTimeoutException` handled gracefully

## Performance Improvements

### Before Optimizations

- First 2-3 pages: Fast loading
- Subsequent pages: 30+ seconds per page
- Concurrent pages: Sequential loading, frequent failures
- Connection acceptance: Blocking, causing serialization
- Max concurrent handlers: ~25 (well below limits)

### After Optimizations

- All pages: 5-7 seconds per page (target: <5 seconds)
- Concurrent pages: True concurrent processing
- Connection acceptance: <100ms average
- Handler limits: Can handle 200 concurrent handlers
- Resource usage: Efficient cleanup prevents exhaustion

## Technical Details

### Buffer Size Rationale

- **64KB read buffer**: Balances memory usage with system call reduction
- **128KB socket buffers**: Matches typical TCP window sizes for better throughput
- **Flush every 4 reads**: 256KB flushed at once (optimal for network efficiency)

### Timeout Strategy

- **5 seconds during tunneling**: Allows TLS handshake (1-3 seconds) + initial data transfer
- **10 seconds for initial request**: Fast failure detection without breaking legitimate slow connections
- **8 seconds for target connection**: Balanced for concurrent connections

### Coroutine Architecture

- **Dispatchers.IO**: All I/O operations use dedicated I/O dispatcher
- **coroutineScope**: Ensures proper cancellation propagation
- **SupervisorJob**: Prevents one handler failure from affecting others

## Configuration Constants

### ProxyServerImpl

```kotlin
MAX_CONCURRENT_HANDLERS = 200
MAX_HANDLERS_PER_CLIENT = 60
HANDLER_TIMEOUT_MS = 120_000L // 2 minutes
ServerSocket backlog = 500
ServerSocket soTimeout = 1000 // 1 second
```

### HttpProxyHandler / Socks5ProxyHandler

```kotlin
BUFFER_SIZE = 65536 // 64KB
Socket receiveBufferSize = 131072 // 128KB
Socket sendBufferSize = 131072 // 128KB
Flush every 4 reads = 256KB
Initial request timeout = 10000ms // 10 seconds
Tunneling timeout = 5000ms // 5 seconds
Target connect timeout = 8000ms // 8 seconds
```

## Best Practices Applied

1. **Non-blocking I/O**: All operations are asynchronous
2. **Resource limits**: Prevent resource exhaustion with configurable limits
3. **Fail-fast**: Quick timeout detection prevents hanging connections
4. **Proper cleanup**: All resources are cleaned up in `finally` blocks
5. **Atomic operations**: Thread-safe counter management
6. **Graceful degradation**: Errors don't crash the server

## Future Optimization Opportunities

1. **Connection pooling**: Reuse target connections for same host
2. **NIO (Non-blocking I/O)**: Could further improve concurrency
3. **Adaptive buffer sizing**: Adjust based on connection speed
4. **Compression**: For text-based content
5. **Caching**: For frequently accessed resources

## Monitoring & Debugging

### Performance Logs

All optimizations include `[PERF]` prefixed logs for:

- Connection acceptance time
- Handler creation time
- Protocol detection duration
- Target connection time
- Tunnel completion time
- Read/write performance

### Key Metrics to Monitor

- Handler count (total and per-client)
- Connection acceptance latency
- Tunnel completion times
- Read/write speeds
- Timeout frequency

## Conclusion

These optimizations transformed the proxy server from a sequential, slow implementation to a highly concurrent, efficient system capable of handling multiple concurrent pages with sub-10-second load times. The key improvements were:

1. **True concurrency**: Non-blocking connection handling
2. **Efficient I/O**: Larger buffers and optimized flush strategy
3. **Proper resource management**: Limits, cleanup, and timeout handling
4. **Robust error handling**: Graceful degradation and recovery

The proxy server now provides a smooth browsing experience for client devices while efficiently managing system resources.
