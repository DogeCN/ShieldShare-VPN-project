@echo off
echo ========================================
echo    Testing HTTP Proxy on Emulator IP
echo ========================================
echo.

echo Testing HTTP proxy with emulator's actual IP...
echo Proxy: 10.0.2.16:8080 (emulator wlan0 IP)
echo Target: httpbin.org/ip
echo.

echo This will test the actual HTTP proxy protocol:
echo 1. Client connects to proxy
echo 2. Client sends HTTP request
echo 3. Proxy forwards request to target
echo 4. Proxy returns response to client
echo.

echo Starting HTTP proxy test...
curl -x http://10.0.2.16:8080 http://httpbin.org/ip --connect-timeout 15 --max-time 30 -v

echo.
echo ========================================
echo HTTP Proxy Test Completed!
echo ========================================
pause
