@echo off
echo ========================================
echo    ShieldShare Proxy Server Log Monitor
echo ========================================
echo.

echo Monitoring proxy server logs...
echo Look for these messages when you click "Start Proxy Server":
echo - "Proxy server started successfully"
echo - "ProxyServerImpl: HTTP/HTTPS Proxy server started"
echo - "ProxyServerImpl: SOCKS5 Proxy server started"
echo.

echo Press Ctrl+C to stop monitoring
echo.

C:\Users\EDCARLOS\AppData\Local\Android\Sdk\platform-tools\adb.exe logcat | findstr /i "DashboardFragment ProxyServer HttpProxyHandler Socks5ProxyHandler"