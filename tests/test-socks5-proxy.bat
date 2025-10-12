@echo off
echo ========================================
echo    Testing SOCKS5 Proxy Protocol
echo ========================================
echo.

echo Testing SOCKS5 proxy with actual SOCKS5 request...
echo Proxy: 10.0.2.2:8080
echo Target: httpbin.org/ip
echo.

echo This will test the actual SOCKS5 protocol:
echo 1. Client connects to proxy
echo 2. SOCKS5 handshake (authentication)
echo 3. SOCKS5 connection request
echo 4. Proxy establishes connection to target
echo 5. Data forwarding through SOCKS5 tunnel
echo.

echo Starting SOCKS5 proxy test...
curl --socks5 10.0.2.2:8080 http://httpbin.org/ip --connect-timeout 15 --max-time 30 -v

echo.
echo ========================================
echo SOCKS5 Proxy Test Completed!
echo ========================================
pause
