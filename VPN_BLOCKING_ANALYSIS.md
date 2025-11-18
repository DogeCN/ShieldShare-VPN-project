# VPN Routing Analysis: How Proton VPN Free Blocks Local Connections

## The Question

How can Proton VPN free plan block incoming connections from clients to the proxy server on the local network (192.168.1.82:8080)? If it's server-side, how does it affect local network traffic?

## The Answer: Routing Table Modification (Not Server-Side Blocking)

### How Android VPNs Work

When a VPN connects on Android, it:

1. Creates a virtual network interface (usually `tun0` or similar)
2. **Modifies the Android routing table** to route traffic through this interface
3. Becomes the "default network" for the device

### The Problem: Lack of Split Tunneling

**Split Tunneling** = Feature that allows some traffic to go through VPN while other traffic (like local network) goes directly.

**Important Distinction:**

- **Incoming local connections** (client → proxy server): Should bypass VPN
- **Outgoing internet connections** (proxy server → internet): Should go through VPN

**Proton VPN Free Plan** likely does NOT support split tunneling, meaning:

- **ALL traffic** (including local network traffic) is routed through the VPN interface
- When a client tries to connect to `192.168.1.82:8080`:
  1. Android routing table says "route all traffic through VPN interface"
  2. **Incoming connection attempt** goes to VPN interface instead of hotspot interface
  3. VPN server receives this local network traffic
  4. VPN server either:
     - Drops it (doesn't know what to do with local network traffic)
     - Blocks it (recognizes it shouldn't be in VPN tunnel)

### Why It Works With Other VPNs

**Nord VPN / Proton VPN Paid (with split tunneling):**

1. **Incoming local connections** (client → proxy server at 192.168.1.82:8080):

   - Split tunneling allows local network traffic (192.168.x.x) to bypass VPN
   - Client connection goes **directly** to hotspot interface ✅
   - Proxy server receives the connection ✅

2. **Outgoing internet connections** (proxy server → internet):
   - Our app uses `vpnAwareSocketFactory` to create sockets
   - These sockets are bound to VPN network via `activeNetwork.socketFactory`
   - Internet traffic goes **through VPN tunnel** ✅
   - Clients get VPN protection through the proxy ✅

**The Key Insight:**

- Split tunneling allows **incoming local connections** to bypass VPN (so clients can reach proxy server)
- But **outgoing internet connections** still go through VPN (via our VPN-aware socket factory)
- Result: Clients connect directly to proxy, but proxy routes their internet traffic through VPN

**Proton VPN Free (no split tunneling):**

- **Incoming local connections** are also routed through VPN ❌
- Client connection attempts go to VPN interface instead of hotspot
- VPN server drops local network traffic
- Proxy server never receives the connection ❌

### Technical Details

#### Android Routing Behavior

```
WITHOUT VPN:
Incoming: Client → 192.168.1.82:8080 → Direct to hotspot interface → Proxy server ✅
Outgoing: Proxy server → Internet → Direct connection ✅

WITH NORD VPN / PROTON VPN PAID (split tunneling enabled):
Incoming: Client → 192.168.1.82:8080 → Bypasses VPN → Direct to hotspot interface → Proxy server ✅
Outgoing: Proxy server → Internet → Through VPN tunnel (via vpnAwareSocketFactory) ✅
Result: Clients connect directly, but their internet traffic goes through VPN ✅

WITH PROTON VPN FREE (no split tunneling):
Incoming: Client → 192.168.1.82:8080 → Routed to VPN interface → VPN server → Drops/Blocks ❌
Outgoing: Proxy server → Internet → Through VPN tunnel (via vpnAwareSocketFactory) ✅
Result: Clients can't connect to proxy server ❌
```

**The Critical Difference:**

- **Split tunneling** allows incoming local connections to bypass VPN (so proxy server can accept connections)
- **Our VPN-aware socket factory** ensures outgoing internet traffic goes through VPN (so clients get VPN protection)
- **Without split tunneling**, incoming local connections are also forced through VPN, which blocks them

#### Network Interface Priority

When VPN is active:

- VPN interface becomes the "default network"
- Android's `ConnectivityManager` prioritizes VPN network
- Local network traffic might be incorrectly routed through VPN

### Evidence

1. **Server socket binds successfully** - The proxy server is listening on the hotspot interface
2. **No connection acceptance logs** - Clients can't reach the server
3. **Works with split tunneling VPNs** - Nord VPN and Proton VPN paid work fine
4. **VPN status shows CONNECTED** - VPN is active and routing traffic

### Why This Isn't "Server-Side Blocking"

The VPN server isn't actively blocking your proxy. Instead:

- Android's routing table routes local traffic through VPN
- VPN server receives unexpected local network traffic
- VPN server drops it (it's not meant to handle local network traffic in the tunnel)

### Solution

This is a **client-side routing issue**, not server-side blocking. The fix would require:

1. **Split tunneling support** in Proton VPN free plan (not available)
2. **Manual routing table modification** (requires root access)
3. **Use a VPN that supports split tunneling** (Nord VPN, Proton VPN paid)

## Can We Handle Split Tunneling in Our App?

### Short Answer: **NO** - Split tunneling is controlled by the VPN client app, not our app

### Why We Can't Control It

1. **VPN Client Controls Routing**: When a third-party VPN app (like Proton VPN) connects, it uses Android's `VpnService` API to:

   - Create the VPN interface
   - Set routing rules via `VpnService.Builder.addRoute()`
   - Control which traffic goes through VPN

2. **Our App Has No Control**: Our app cannot:

   - Modify VPN routing tables (requires root or VPN service permissions)
   - Request split tunneling from third-party VPN apps
   - Override VPN routing decisions
   - Access `VpnService.Builder` methods (only available to VPN app itself)

3. **Android's VpnService API**: The `VpnService.Builder` class allows VPN apps to:
   - `addRoute(route, prefixLength)` - Route specific IP ranges through VPN
   - `addDnsServer(dns)` - Set DNS servers
   - `addAddress(address, prefixLength)` - Add IP addresses
   - But these methods are **only available to the VPN app itself**, not other apps

### What We CAN Do

1. **Detect VPN Status**: We can check if VPN is connected (which we do via `ConnectivityManager`)
2. **Warn Users**: We can detect when VPN blocks local connections and warn users
3. **Suggest Alternatives**: Recommend VPNs that support split tunneling
4. **Use VPN-Aware SocketFactory**: We can ensure outbound traffic goes through VPN (which we do)

### The Fundamental Limitation

Split tunneling is a **VPN client feature**, not something we can implement in our app. We're at the mercy of:

- Whether the VPN app supports split tunneling
- Whether the user's plan includes split tunneling (free vs paid)
- VPN app's routing configuration

## Proton VPN Free vs Paid: What the Source Code Shows

### Key Finding: "Allow LAN Connections" is a Paid-Only Feature

From Proton VPN's documentation and behavior:

- **Free Plan**: NO "Allow LAN connections" option - local network traffic blocked
- **Paid Plan**: "Allow LAN connections" feature available - can enable local network access

### Why Same App, Different Behavior?

The Proton VPN Android app is the same for free and paid users, but:

1. **Feature Flags Based on Subscription**:

   - The app checks user's subscription level (free vs paid)
   - "Allow LAN connections" UI/functionality is only enabled for paid users
   - Free users don't get the option to configure local network access

2. **OpenVPN Configuration Differences**:

   - **Free plan**: OpenVPN config routes ALL traffic (0.0.0.0/0) and blocks local networks
   - **Paid plan**: OpenVPN config can exclude local networks (192.168.0.0/16, 10.0.0.0/8, etc.) when "Allow LAN" is enabled

3. **Server-Side Configuration**:
   - Free servers may have stricter routing policies
   - Paid servers allow local network traffic when configured

### The "Allow LAN Connections" Setting

When enabled in Proton VPN paid plan:

- Local network traffic (192.168.x.x, 10.x.x.x, 172.16-31.x.x) bypasses VPN
- Traffic goes directly to local network interfaces
- This is essentially split tunneling for local networks

When disabled or unavailable (free plan):

- All traffic (including local) goes through VPN
- VPN server drops local network traffic
- Local connections fail

### What This Means

Even though it's the same app:

- **Free users**: App connects with "block local networks" configuration (no option to change)
- **Paid users**: App connects with "Allow LAN connections" option (can enable/disable)
- The difference is in the **VPN connection parameters and available settings**, not the app code itself

## Conclusion

Proton VPN free plan blocks local connections **indirectly** by:

1. Not supporting "Allow LAN connections" (paid-only feature)
2. Forcing all traffic through VPN interface
3. VPN server dropping local network traffic that shouldn't be in the tunnel

**We cannot fix this in our app** because:

- Split tunneling/LAN access is controlled by the VPN client app
- We don't have permissions to modify VPN routing
- The feature is intentionally restricted to paid plans

**The solution** requires either:

1. User upgrades to Proton VPN paid plan (gets "Allow LAN connections" feature)
2. User uses a different VPN that supports local network access in free plan
3. User disables VPN when using our proxy feature
