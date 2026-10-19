# 4.10-fork.1

## Upstream improvements incorporated

Adapted the lifecycle diagnostics and foreground-start failure handling introduced
by upstream commit `53641dc97418` on 2026-10-18 (MIT license). Companion presence,
the Sony power-aware PendingIntent scan, background location permission, and the
on-demand foreground service remain the connection architecture.

- Policy rejection at either `startForegroundService` or `startForeground` is
  handled without crashing or requesting sticky redelivery. The user receives an
  actionable notification when notifications are allowed. A normal absent camera
  never causes a reminder or starts a service.
- After access is restored, opening the app rearms the power-filtered trigger for
  rejected sessions, including an already-on camera. It never starts GPS for an
  off camera merely because the user opened an error notification.
- Persistent evidence includes process exit reasons, last connection state,
  uncaught exception traces, limited ANR traces, standby/power state and service
  lifecycle. It is attached to Share Log. Initialization covers presence/boot
  receivers as well as the UI and active service.
- Diagnostic and reconnection event files rotate at 64 KiB each, with one older
  file per log. Routine writes use one background writer, with no polling, alarms,
  network, or extra wake locks. Crash evidence is flushed synchronously.
- Upstream's build-tool upgrades are intentionally deferred: they do not address
  the runtime bug and require a separate toolchain migration.

## Independent app identity

Application ID: `io.github.josueuitzil.cameragpslink`.
Version: `4.10-fork.1`, code `41001`. Minimum/target SDK remain 36.
The Kotlin namespace stays `org.kutner.cameragpslink` to preserve source attribution
and avoid a mechanical code move; it does not determine Play Store identity.
Play review prompts and the Rate App menu have been removed; Help opens this fork.

Changing application ID creates a new Android app. The installer must back up old
settings before removing `org.kutner.cameragpslink`, then restore camera preferences
under the new ID. Runtime permissions and Companion Device associations belong to
the package and must be granted/approved again; never copy an old association ID
as if it belonged to the new package. Bluetooth bonding can be reused, so do not
unpair the camera just to migrate the app. Keep only the new app enabled for camera
connections and remove the old package after verifying the replacement.

Home-screen remote shortcuts tied to the old package need to be recreated.

## Verification

Run unit tests (including bounded/concurrent log retention), debug compilation and
lint. Device tests cover independent identity and both foreground-start rejection
paths. On the Galaxy verify camera association, GATT and first successful DD11,
then camera off/no foreground service and camera on/reconnection. Retest overnight
and Doze before attributing any field reliability change to this release.

### Validated on 2026-10-19

- `testDebugUnitTest`: 11 tests passed, including three log-retention/concurrency tests.
- Debug APK and instrumented-test APK built successfully; `lintDebug`: zero errors
  (84 advisory warnings).
- Four instrumented tests passed on the Galaxy S25 Ultra SM-S938B, running API 37.
- Installed version 4.10-fork.1, restored camera preferences, and approved the new
  camera association through Android's Companion Device dialog using the existing bond.
- Removed the old application and the temporary test package. Package inspection
  confirms only `io.github.josueuitzil.cameragpslink` remains installed.
- Confirmed GATT connection and successful DD11 location writes, followed by a
  disconnect/reconnect and a new successful DD11 after removing the old app.
- Preserved the previous APK and data archive under `local-backups/`, excluded
  from Git. The installed app retains location/Bluetooth/notification permissions.

These checks do not replace another overnight/Doze field test of the new release.
