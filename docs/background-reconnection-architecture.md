# Event-driven camera reconnection (Android 16)

## Decision

Camera GPS Link now targets Android 16 / API 36 and uses a hybrid system-managed
architecture:

1. `CompanionDeviceManager` owns user-approved Sony camera associations.
2. API 36 companion presence is the primary dormant trigger. The app declares
   `REQUEST_COMPANION_RUN_IN_BACKGROUND` so Android can bind and wake the companion
   component independently of a cached application process.
3. On a companion appeared event, `CompanionDeviceService` performs a bounded 12-second
   hardware-filtered confirmation scan. GATT is started only after the Sony powered-on
   bit is observed, so a power-off beacon cannot start GPS or a foreground service.
4. A filtered `BluetoothLeScanner.startScan(..., PendingIntent)` registration remains as
   a `LOW_POWER` fallback. It handles **Connect while Power Off**, where generic presence
   may remain continuously true and therefore provides no new appeared transition.
5. The service becomes a `connectedDevice|location` foreground service, opens a direct
   LE GATT connection, starts high-accuracy location, and sends Sony DD11 updates.
6. Disconnect resets the fallback scan's `FIRST_MATCH` state before tearing down GATT/location/
   FGS. This permits a fresh event after a quick off/on cycle instead of waiting for
   Samsung's delayed `MATCH_LOST`. The durable system scan remains registered without
   retaining the app process.

This replaces both user-facing connection modes. A callback BLE scan and
`connectGatt(autoConnect=true)` are no longer background-liveness mechanisms.

## Why API 36

The old minimum was API 26. The new minimum is API 36. The fallback uses association-ID
`ObservingDevicePresenceRequest` and `CompanionDeviceService.onDevicePresenceEvent`,
without deprecated address callbacks or an old-version strategy matrix. This branch is
an architecture experiment aimed at a modern Galaxy; widening compatibility should be
a separate product decision after physical validation.

## State machine

```text
UNASSOCIATED
    | system association approval
    v
ASSOCIATED / DORMANT
    | companion appeared -> bounded Sony power-on confirmation
    | (or low-power PendingIntent fallback)
    v
FGS_STARTING -> GATT_CONNECTING -> BONDING -> DISCOVERING
                    | failure          | failure
                    +---- bounded exponential retry ----+
                                      |
                                      v
                            CONNECTED / GPS_STREAMING
                                      |
                 disconnect, match lost, Bluetooth off
                                      v
                         TEARDOWN -> DORMANT
```

Recovery is normally driven by a freshly rearmed scan event. If scan registration fails,
direct retries are limited to five attempts (2, 4, 8, 16 and 32 seconds). Each connection
has a 25-second timeout and each queued GATT operation has an 8-second timeout.

## Component responsibilities

- `CompanionCameraManager`: association, migration of saved MAC addresses, observer
  fallback, disassociation, and trigger rearming.
- `SonyPresenceScanner` / `SonyPresenceScanReceiver`: process-independent, hardware-
  filterable power-on and match-lost events.
- `SonyAdvertisement`: protocol parsing and the a6700 power-aware filter definition.
- `SonyCompanionDeviceService`: primary API 36 presence wake path and short powered-on
  confirmation scan.
- `CameraSyncService`: active session only; GATT, bonding, Sony protocol and location.
- `GattOperationQueue`: one outstanding characteristic/descriptor write per GATT,
  immediate rejection retry, and timeout-based recovery.
- `BootReceiver`: idempotently rearms registrations on boot/package replacement; it
  never starts GPS or a foreground service.
- `ReconnectionMetrics`: local NDJSON events and counters included when sharing the log.

## Process and power behavior

| Situation | Expected behavior |
|---|---|
| Camera off | No app service/process requirement and no location updates |
| Camera powered on | Companion presence wakes the app; Sony power bit confirms before FGS |
| App removed from Recents | No effect on registered association/scan |
| Normal process death while dormant | System scan can create the receiver process |
| Active-process memory death | `START_REDELIVER_INTENT` recreates the requested session |
| Reboot/update | receiver rearms association and scan; no boot notification |
| Doze/App Standby | system-managed filtered trigger; active connection uses FGS |
| Explicit Force stop | Android suppresses callbacks until user interaction |
| Samsung Deep sleeping apps | Unsupported by design; One UI prevents background activation |

Normal Samsung Optimized/Adaptive Battery is the target configuration. Companion
association plus `REQUEST_COMPANION_RUN_IN_BACKGROUND` is the official Android exemption
for an associated-device integration and replaces the generic request for unrestricted
battery. `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and its onboarding step remain removed.

Galaxy field logs showed that One UI froze a correctly registered PendingIntent scan for
minutes while the app process was cached. The scan received no results across three camera
power cycles and resumed after opening the app. This is why companion presence is primary;
increasing scan duty cycle cannot repair a scan that the OEM has frozen.

## Location and notifications

`ACCESS_BACKGROUND_LOCATION` is required because Android 14+ validates while-in-use
location at FGS creation time. Users grant Fine Location first, then select the localized
“Allow all the time” option in system settings. No location request is active when there
is no connected/connecting camera.

The notification now means real work: connecting or connected. The indefinite
“Searching for cameras” notification and post-reboot reminder were removed.

## Sony assumptions and limitations

Sony manufacturer data is interpreted as three-byte tags beginning at offset 6. A physical
a6700 running protocol version 101 was observed advertising tags `0x22`, `0x23`, then
`0x21`, placing the power tag at offset 12. The power-aware hardware filter therefore
matches `0x21` at offset 12 and masks bit `0x0040`. The parser itself continues to walk all
tags because their contents can vary, but Android manufacturer filters match fixed offsets.
Physical a6700 captures showed `0x21 0x60 0x00` while on. During the short power-down
transition that bit can remain set until advertisements cease, so absence still arrives as
a delayed `MATCH_LOST`; resetting `FIRST_MATCH` at GATT teardown closes the resulting
quick-cycle race. Unit tests cover the documented layout. **Connect while Power Off**
still needs physical validation with both enabled and disabled; the documented off beacon
uses `0x21 0x20 0x00` and is rejected in hardware.

If a firmware reorders tags, the strict PendingIntent filter will not fire. The safe
fallback is to disable **Connect while Power Off** while adding a model-specific observed
layout; broad `ALL_MATCHES` scanning was intentionally avoided because it would wake the
app for every power-off advertisement.

With **Connect while Power Off** enabled, generic companion presence may keep
`CompanionDeviceService` bound while the camera is nominally off. The app performs no GPS
or GATT work after the confirmation timeout, but the bound process can cost more memory and
battery than the normal fully dormant state. The low-power manufacturer-filtered scan is
still required to detect the later off-to-on transition.

Neither companion association nor device presence creates a GATT connection or speaks
the proprietary Sony protocol. Bonding and DD30/DD31/CC13/DD11 remain app-owned.

## Official references

- <https://developer.android.com/develop/connectivity/bluetooth/ble/background>
- <https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing>
- <https://developer.android.com/reference/android/companion/CompanionDeviceService>
- <https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start>
- <https://developer.android.com/develop/background-work/services/fgs/service-types>
- <https://developer.android.com/develop/sensors-and-location/location/permissions/background>
- <https://developer.samsung.com/mobile/app-management.html>
