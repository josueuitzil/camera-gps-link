# Event-driven camera reconnection (Android 16)

## Decision

Camera GPS Link now targets Android 16 / API 36 and uses a hybrid system-managed
architecture:

1. `CompanionDeviceManager` owns user-approved Sony camera associations.
2. A filtered `BluetoothLeScanner.startScan(..., PendingIntent)` registration is the
   normal dormant trigger. The filter matches the saved address, Sony manufacturer ID,
   camera device type, tag `0x21`, and its powered-on bit.
3. API 36 companion presence is armed only if the power-aware PendingIntent scan cannot
   be registered. Observing all Sony presence unconditionally would keep
   `CompanionDeviceService` bound while **Connect while Power Off** advertises.
4. The scan receiver starts `CameraSyncService` only for a powered-on match.
5. The service becomes a `connectedDevice|location` foreground service, opens a direct
   LE GATT connection, starts high-accuracy location, and sends Sony DD11 updates.
6. Disconnect, match-lost, or Bluetooth-off tears down GATT/location/FGS. The durable
   system scan remains registered without retaining the app process.

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
    | Sony powered-on PendingIntent match
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

Retries are limited to five attempts (2, 4, 8, 16 and 32 seconds). Each connection has
a 25-second timeout and each queued GATT operation has an 8-second timeout.

## Component responsibilities

- `CompanionCameraManager`: association, migration of saved MAC addresses, observer
  fallback, disassociation, and trigger rearming.
- `SonyPresenceScanner` / `SonyPresenceScanReceiver`: process-independent, hardware-
  filterable power-on and match-lost events.
- `SonyAdvertisement`: protocol parsing and the a6700 power-aware filter definition.
- `SonyCompanionDeviceService`: API 36 fallback when PendingIntent scan registration
  fails.
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
| Camera powered on | System delivers filtered PendingIntent and starts active FGS |
| App removed from Recents | No effect on registered association/scan |
| Normal process death while dormant | System scan can create the receiver process |
| Active-process memory death | `START_REDELIVER_INTENT` recreates the requested session |
| Reboot/update | receiver rearms association and scan; no boot notification |
| Doze/App Standby | system-managed filtered trigger; active connection uses FGS |
| Explicit Force stop | Android suppresses callbacks until user interaction |
| Samsung Deep sleeping apps | Unsupported by design; One UI prevents background activation |

Normal Samsung Optimized/Adaptive Battery is the target configuration. Companion
association plus background-FGS permission removes the generic reason to request
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; that permission and onboarding step were removed.

## Location and notifications

`ACCESS_BACKGROUND_LOCATION` is required because Android 14+ validates while-in-use
location at FGS creation time. Users grant Fine Location first, then select the localized
“Allow all the time” option in system settings. No location request is active when there
is no connected/connecting camera.

The notification now means real work: connecting or connected. The indefinite
“Searching for cameras” notification and post-reboot reminder were removed.

## Sony assumptions and limitations

Sony manufacturer data is interpreted as three-byte tags beginning at offset 6. The
power-aware hardware filter assumes `0x21` is the first tag and masks bit `0x0040`. The
parser itself can find later tags, but Android manufacturer filters match fixed offsets.
Unit tests cover the documented layout; a Sony a6700 must verify it with **Connect while
Power Off** both enabled and disabled.

If a firmware reorders tags, the strict PendingIntent filter will not fire. The safe
fallback is to disable **Connect while Power Off** while adding a model-specific observed
layout; broad `ALL_MATCHES` scanning was intentionally avoided because it would wake the
app for every power-off advertisement.

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
