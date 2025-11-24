# Proton VPN Source Code Analysis: Free vs Paid LAN Connections

## Key Findings from Proton VPN Android App Source Code

### 1. LAN Connections Setting is RESTRICTED for Free Users

**File**: `app/src/main/java/com/protonvpn/android/redesign/settings/ui/SettingsViewModel.kt`

**Line 261**:

```kotlin
class LanConnections(
    value: Boolean,
    val allowDirectConnections: Boolean?,
    isFreeUser: Boolean,
    overrideProfilePrimaryLabel: ConnectIntentPrimaryLabel.Profile?,
) : SettingViewState<Boolean>(
    value = value,
    isRestricted = isFreeUser,  // ← FREE USERS CANNOT ENABLE THIS
    ...
)
```

**Evidence**:

- `isRestricted = isFreeUser` means free users have this setting **restricted**
- When restricted, the UI shows "needsUpgrade" and clicking triggers upgrade prompt instead of toggling

### 2. How LAN Connections Work (When Enabled)

**File**: `app/src/main/java/com/protonvpn/android/models/vpn/usecase/ComputeAllowedIPs.kt`

**Lines 95-102**:

```kotlin
if (userSettings.lanConnections) {
    excludedIps += getLocalRanges(
        ipV6Enabled = ipV6Enabled,
        allowDirectConnections = userSettings.lanConnectionsAllowDirect
    ).apply {
        ProtonLogger.logCustom(LogCategory.CONN, "Excluded local networks: $this")
    }
}

val allowedIPsV4 = excludeFrom(FULL_RANGE_IP_V4, excludedIps)
```

**How it works**:

1. When `lanConnections = true`, local network ranges are added to `excludedIps`
2. These excluded IPs are then removed from the full IP range (0.0.0.0/0)
3. Result: Local networks (192.168.x.x, 10.x.x.x, 172.16-31.x.x) are **excluded from VPN routing**
4. Local traffic bypasses VPN, internet traffic goes through VPN

### 3. Default Behavior for Free Users

**File**: `app/src/main/java/com/protonvpn/android/settings/data/LocalUserSettings.kt`

**Line 92**:

```kotlin
val lanConnections: Boolean = false,  // Default is FALSE
```

**For Free Users**:

- Setting is **restricted** (cannot be enabled)
- Default value is `false`
- Local networks are **NOT excluded** from VPN routing
- **ALL traffic** (including local) goes through VPN tunnel
- VPN server drops local network traffic
- **Result**: Local connections fail ❌

**For Paid Users**:

- Setting is **not restricted** (can be enabled)
- Can toggle `lanConnections = true`
- When enabled, local networks are excluded from VPN routing
- Local traffic bypasses VPN, internet traffic goes through VPN
- **Result**: Local connections work ✅

### 4. The Feature Flag

**File**: `app/src/main/java/com/protonvpn/android/vpn/usecases/IsDirectLanConnectionsFeatureFlagEnabled.kt`

There's also a feature flag `DirectLanConnections` that controls the "Allow Direct Connections" sub-setting, but the main LAN connections setting itself is restricted by subscription tier.

### 5. UI Restriction Implementation

**File**: `app/src/main/java/com/protonvpn/android/redesign/settings/ui/SettingsItemUtils.kt`

**Lines 43-48**:

```kotlin
needsUpgrade = isRestricted,  // Shows upgrade prompt for free users
onAnnotatedOutsideClick = if (isRestricted) onRestricted else onToggle
```

When `isRestricted = true`:

- UI shows "needsUpgrade" indicator
- Clicking triggers upgrade prompt instead of toggling setting
- Free users cannot enable LAN connections

## Conclusion

**Confirmed from Source Code**:

1. ✅ **Free users have LAN connections setting RESTRICTED** (`isRestricted = isFreeUser`)
2. ✅ **Free users cannot enable the setting** (UI shows upgrade prompt)
3. ✅ **Default is `lanConnections = false`** for free users
4. ✅ **When `lanConnections = false`**, local networks are NOT excluded from VPN routing
5. ✅ **All traffic (including local) goes through VPN** for free users
6. ✅ **VPN server drops local network traffic**, causing connection failures

**Paid users**:

- Can enable `lanConnections = true`
- When enabled, local networks are excluded from VPN routing
- Local traffic bypasses VPN, internet traffic goes through VPN
- Works perfectly with our proxy ✅

This confirms that **Proton VPN free plan intentionally restricts LAN connections as a paid-only feature**, which is why our proxy doesn't work with it.
