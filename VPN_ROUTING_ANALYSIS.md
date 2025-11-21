# VPN Routing Analysis: "Allow LAN Connections" vs Split Tunneling

## The Key Distinction

### Split Tunneling (Per-App) ≠ "Allow LAN Connections" (VPN-Level)

**Split Tunneling (Per-App):**

- Controls which **applications** route through VPN
- If app is excluded → No VPN for that app's traffic (including internet)
- If app is included → All traffic goes through VPN (including local)
- **Problem**: We need BOTH local bypass AND internet through VPN, which per-app split tunneling can't provide

**"Allow LAN Connections" (VPN-Level):**

- VPN-level setting that excludes **local network IP ranges** (192.168.x.x, 10.x.x.x, 172.16-31.x.x) from VPN tunnel
- Works automatically - local network traffic bypasses VPN regardless of which app
- Internet traffic still goes through VPN
- This is what we actually need!

## Why Per-App Split Tunneling Doesn't Help

### Windscribe Example (Per-App Split Tunneling):

**Option 1: Exclude our app from VPN**

- Our app's traffic doesn't go through VPN
- Local connections work ✅
- **BUT**: Internet traffic from proxy doesn't go through VPN ❌
- Clients don't get VPN protection ❌

**Option 2: Include our app in VPN**

- All our app's traffic goes through VPN
- Local connections (192.168.1.82:8080) go through VPN ❌
- VPN server drops local network traffic ❌
- Clients can't connect to proxy server ❌

**Result**: Per-app split tunneling doesn't solve the problem because we need:

- Local traffic to bypass VPN (so clients can connect)
- Internet traffic to go through VPN (so clients get protection)

## Why "Allow LAN Connections" Works

### Nord VPN (Has "Allow LAN" by Default):

**How it works:**

- VPN automatically excludes local network ranges (192.168.x.x, 10.x.x.x) from tunnel
- This is a **VPN-level routing rule**, not per-app

**Traffic flow:**

1. **Incoming local connection** (client → 192.168.1.82:8080):

   - VPN routing rule: "192.168.x.x is local network, exclude from VPN"
   - Connection bypasses VPN tunnel ✅
   - Goes directly to hotspot interface ✅
   - Proxy server receives connection ✅

2. **Outgoing internet connection** (proxy → internet):
   - Our app uses `vpnAwareSocketFactory` to create sockets
   - Sockets bound to VPN network
   - Internet traffic goes through VPN tunnel ✅
   - Clients get VPN protection ✅

**Result**: Works perfectly! ✅

### Proton VPN Paid (Has "Allow LAN Connections" Option):

**How it works:**

- Paid plan includes "Allow LAN connections" setting
- When enabled, VPN excludes local network ranges from tunnel
- Same behavior as Nord VPN

**Result**: Works when "Allow LAN connections" is enabled ✅

### Proton VPN Free (No "Allow LAN Connections"):

**How it works (CONFIRMED FROM SOURCE CODE):**

- **LAN connections setting is RESTRICTED for free users** (`isRestricted = isFreeUser`)
- Free users **cannot enable** the setting (UI shows upgrade prompt)
- Default is `lanConnections = false`
- All traffic (including local) forced through VPN tunnel
- VPN server drops local network traffic

**Source Code Evidence:**

From `SettingsViewModel.kt` line 261:

```kotlin
class LanConnections(
    value: Boolean,
    val allowDirectConnections: Boolean?,
    isFreeUser: Boolean,
    ...
) : SettingViewState<Boolean>(
    value = value,
    isRestricted = isFreeUser,  // ← FREE USERS CANNOT ENABLE
    ...
)
```

From `ComputeAllowedIPs.kt` lines 95-102:

```kotlin
if (userSettings.lanConnections) {  // Only true for paid users
    excludedIps += getLocalRanges(...)  // Exclude local networks
}
// When lanConnections = false (free users), local networks NOT excluded
```

**Traffic flow:**

1. **Incoming local connection** (client → 192.168.1.82:8080):
   - VPN routing: "All traffic goes through VPN" (no local network exclusion)
   - Connection goes to VPN interface ❌
   - VPN server drops local network traffic ❌
   - Proxy server never receives connection ❌

**Result**: Doesn't work ❌

## Technical Details

### Android VPN Routing Rules

When VPN connects, it uses `VpnService.Builder.addRoute()` to set routing rules:

**With "Allow LAN Connections" (Nord VPN, Proton VPN paid):**

```kotlin
// VPN app excludes local networks from tunnel
vpnBuilder.addRoute("0.0.0.0", 0)  // Route internet through VPN
// BUT excludes: 192.168.0.0/16, 10.0.0.0/8, 172.16.0.0/12
// These ranges bypass VPN automatically
```

**Without "Allow LAN Connections" (Proton VPN free):**

```kotlin
// VPN app routes ALL traffic through VPN
vpnBuilder.addRoute("0.0.0.0", 0)  // Route everything through VPN
// No exclusions for local networks
// Local traffic also goes through VPN tunnel
```

### Why Our App Can't Control This

1. **VPN App Controls Routing**: Only the VPN app can call `VpnService.Builder.addRoute()`
2. **We Can't Modify Routes**: Our app cannot modify VPN routing rules
3. **Feature is VPN-Level**: "Allow LAN connections" is a VPN app feature, not something we can enable

## Summary

| VPN             | "Allow LAN" Feature       | Per-App Split Tunneling | Works with Our App?          |
| --------------- | ------------------------- | ----------------------- | ---------------------------- |
| Nord VPN        | ✅ Enabled by default     | ✅ Available            | ✅ Yes                       |
| Proton VPN Paid | ✅ Available (can enable) | ✅ Available            | ✅ Yes (if enabled)          |
| Proton VPN Free | ❌ Not available          | ❌ Not available        | ❌ No                        |
| Windscribe      | ❌ Not available          | ✅ Available            | ❌ No (per-app doesn't help) |

## Conclusion

The issue is **NOT** about split tunneling (per-app). It's about **"Allow LAN Connections"** - a VPN-level feature that:

1. Excludes local network IP ranges from VPN tunnel automatically
2. Works regardless of which app is making the connection
3. Allows local traffic to bypass VPN while internet traffic still goes through VPN

**We cannot implement this in our app** because:

- It's a VPN app feature, not something we can control
- Only the VPN app can set routing rules via `VpnService.Builder`
- **Proton VPN free plan intentionally restricts this as a paid-only feature** (confirmed from source code)

**The solution** requires:

1. VPN that supports "Allow LAN connections" (Nord VPN, Proton VPN paid)
2. Or user disables VPN when using proxy feature

## Source Code Analysis

See `PROTON_VPN_CODE_ANALYSIS.md` for detailed evidence from Proton VPN's Android app source code confirming:

- Free users have LAN connections setting restricted (`isRestricted = isFreeUser`)
- Free users cannot enable the setting (UI shows upgrade prompt)
- When disabled, local networks are NOT excluded from VPN routing
- This is an intentional paid-only feature restriction
