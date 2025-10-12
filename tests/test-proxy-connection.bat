@echo off
echo ========================================
echo    Testing ShieldShare Proxy Server
echo ========================================
echo.

echo Testing HTTP proxy connection...
echo Proxy: 10.0.2.2:8080 (emulator host IP)
echo Target: httpbin.org/ip
echo.

echo This will test if your proxy server can forward HTTP requests
echo.

curl -x http://10.0.2.2:8080 http://httpbin.org/ip --connect-timeout 10 --max-time 30

echo.
echo ========================================
echo Test completed!
echo ========================================
pause