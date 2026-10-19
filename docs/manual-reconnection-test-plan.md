# Camera GPS Link reconnection test plan

Run each scenario against the last production build and this branch. Use the same Galaxy,
Sony a6700, distance, camera settings and phone charge band. Repeat short scenarios 30
times; repeat overnight scenarios on at least 7 nights.

## Preparation

1. Install the build and associate the a6700 through `+`.
2. Grant Nearby devices, notifications, precise location and **Allow all the time**.
3. Leave Samsung battery mode at **Optimized** unless a scenario says otherwise.
4. Reset measurements:

```sh
adb shell dumpsys batterystats --reset
adb logcat -c
```

Capture `CglMetrics`, `CameraSyncService`, Bluetooth and Fused Location logs. The app's
Share log action includes aggregate counters and the path of its local NDJSON event log.

## Core matrix

For every row, record signal time, GATT-connected time, first successful DD11 callback,
disconnect time and FGS stop time.

- Camera off 10 minutes, then on; screen unlocked and locked.
- Camera off 1 hour, then on; phone locked.
- Camera off overnight, then on without opening Camera GPS Link.
- App swiped from Recents before turning the camera on.
- Dormant process killed with `adb shell am kill io.github.josueuitzil.cameragpslink`.
- Active process killed during a connection (verify intent redelivery).
- Phone rebooted with camera off, then camera on without opening the app.
- Bluetooth off/on with camera on, and Bluetooth off/on while camera is off.
- Camera moves out of range and returns; weak/intermittent RF at the range boundary.
- Location off/on; revoke/regrant Fine and Background Location.
- 50 consecutive camera power cycles with 30–60 seconds between cycles.
- Two associated cameras entering/leaving range independently.

Run the full matrix twice:

- Sony **Connect while Power Off** disabled.
- Sony **Connect while Power Off** enabled. Confirm that the power-off beacon never
  produces `camera_signal` or a foreground-service notification.

## Android and Samsung policy matrix

- Force Doze after the app is dormant:

```sh
adb shell dumpsys deviceidle force-idle
adb shell dumpsys deviceidle unforce
```

- Place the app in the Restricted standby bucket, test, then restore Active:

```sh
adb shell am set-standby-bucket io.github.josueuitzil.cameragpslink restricted
adb shell am set-standby-bucket io.github.josueuitzil.cameragpslink active
```

- Test Battery Saver off/on and Adaptive Battery off/on.
- Test Samsung lists separately: none, Sleeping apps, Deep sleeping apps, Never sleeping.
  Deep sleeping is an expected failure; Never sleeping should not be required normally.
- Run `adb shell am force-stop io.github.josueuitzil.cameragpslink` as a negative control. No
  automatic restart is expected until the user opens/interacts with the app.

## Measurements

For each scenario calculate:

- reconnection success percentage within 30 and 60 seconds;
- median, p95 and maximum signal→GATT latency;
- median, p95 and maximum signal→first-location latency;
- GATT error/status distribution, timeouts and retry count;
- total FGS duration versus actual connected-session duration;
- callback-scan duration (expected zero while dormant) and PendingIntent registration;
- wakeups, CPU time, Bluetooth scan/radio time and battery delta while camera is off.

Useful captures:

```sh
adb shell dumpsys batterystats > batterystats.txt
adb shell dumpsys bluetooth_manager > bluetooth.txt
adb shell dumpsys deviceidle > deviceidle.txt
adb bugreport bugreport.zip
```

Also capture a Perfetto trace around one cold reconnection and enable Bluetooth HCI snoop
logging for one failure reproduction. HCI logs can contain device identifiers; keep them
private.

## Acceptance gates

- At least 99% of normal/Optimized reconnections reach GATT within 60 seconds.
- At least 99% send a first DD11 coordinate within 75 seconds.
- No app foreground service or Fused Location updates while the camera is off.
- With **Connect while Power Off** enabled, no app wake/FGS from the off advertisement.
- Process kill, Recents removal, overnight wait and reboot all reconnect without opening
  the app.
- FGS stops within 30 seconds after the last camera disconnects.
