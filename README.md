# Camera GPS Link
  
This is the Android 16 fork maintained on `modern-android-main`, based on
[Camera GPS Link by Eran Kutner](https://github.com/ekutner/camera-gps-link).
Its independent application ID is `io.github.josueuitzil.cameragpslink` and it is
installed from this fork's APK, not Google Play. Official Play Store updates cannot
replace it. Minimum Android version: Android 16 / API 36.

See [fork release notes and migration](docs/fork-release-notes.md).
  
This Android app provides GPS and time synchronization, as well as full Bluetooth remote control 
for most Sony cameras.

The app essentially does the same thing as Location Linking feature in Creator's App,
except it actually works reliably and doesn't randomly turn itself off. 
This issue has affected Creator's App since its first release and Sony are 
unable, or unwilling, to fix it.  
It also provides a fully featured Bluetooth based remote control with all the functionality 
of Sony's RMT-P1BT and RMT-VP2 remotes.

### **This app is not associated with or endorsed by Sony**

## Features
* **GPS synchronization (Location Linking) that just works**
* **Time synchronization** - keeps the camera time always be synchronized with the 
  Phone's time, including time zone and daylight savings status, 
  which can't be synced automatically when using the Creator's App
* **Full Bluetooth remote control** - Full support for all the feature of 
  Sony's Bluetooth remote controls such as the RMT-P1BT and RMT-VP2   
* Support for connecting multiple cameras
* Automatic reconnection through Android Companion Device Presence and a Sony power-aware BLE fallback
* Now supports camera that use either Sony's Creator's App or Imaging Edge Mobile

## Screenshots
| ![](assets/two-cameras-light.jpg) | ![](assets/remote-control-dialog-light.jpg) | ![](assets/notifications.jpg) | ![](assets/found-camera-dark.jpg) |
|-----------------------------------|----------------------------------|-----------------------------------|-------------------------------|



## Usage
### Pairing the camera with the app
1. For a new pairing or to fix a broken bond, remove the old pairing on both devices.
   For migration to this fork's new app ID, reuse the existing Bluetooth bond and
   approve a new Companion Device association; do not unpair a working camera.
2. Disable Location Information Linkage in Creator's App and Imaging Mobile Edge 
   if they are installed on your phone
3. On Cameras using the old menu system (cameras that use Imaging Edge Mobile app)
   Location Information Linking must be enabled in the camera menu before pairing:
   MENU → Network → Location Info. Link Set. → Location Info. Link - set to "On"
4. On cameras using the new menu system:
   * Go to MENU → Network → Bluetooth → Bluetooth Function - Set to "On"
   * Go to MENU → Network → Bluetooth → Pairing - The camera will enter pairing mode
   On cameras using the old menu system:
   * Go to MENU → Network → Bluetooth Settings → Bluetooth Function - Set to "On"
   * Go to MENU → Network → Bluetooth Settings → Pairing
5. In the app:
   * Press the "+" button to start searching for cameras
   * Approve the camera in Android's companion-device dialog.
6. Confirm Bluetooth pairing on both devices if they are not already bonded.
7. Android observes the associated camera and wakes the app when needed. GPS and
   the foreground-service notification run only during a camera session.
8. Observation is rearmed after phone restart and app replacement without opening
   the app. Force stop, revoked permissions and Samsung Deep sleeping remain exceptions.

### Remote Control
1. Enable remote control on the camera.
   * On cameras using the new menu system:
     MENU → Network → Bluetooth → Bluetooth Rmt Ctrl - Set to "On"
   * On cameras using the old menu system:
     MENU → Network → Bluetooth Rmt Ctrl - Set to "On"
   * Turn the camera off and back on again for the app to detect the change
2. Press the "SHUTTER" button in the app app's main screen or directly in the 
   notification which is displayed when the app is actively connected to the camera. 
   This will trigger an immediate shutter release without pre-focusing.
3. Use the "Remote" button in the app to open the full featured remote control.  
   * If "Enable half shutter press" is ON then pressing the shutter button is like 
     half pressing the shutter on the camera. Then you need to drag the button either to 
     the left or to thr right to trigger a full shutter release.  
   * If "Enable half shutter press" is OFF then pressing the shutter button will 
     trigger an immediate shutter release without pre-focusing.
4. The shutter button will turn green when focus is acquired.
5. The video record button will show a "stop record" icon when video recording is active.

**Notes** 
* When remote control is enabled the auto power off feature of the camera is automatically 
  disabled and the camera will have to be manually turned off
* The remote control buttons are simulating a press on the corresponding camera buttons, 
  so they will function in the same way as the corresponding camera button. If you changed 
  the default configuration of these buttons on the camera then they will do the same thing 
  when pressed in the remote control.
* When you enable Bluetooth remote control you will have to turn the camera off and then on again
  so the app can reconnect and discover that remote control has been enabled.
* Some cameras like the a6400 and a7 III do not allow remote control and location linking to be enabled
  at the same time. If you enable one the other one gets automatically disabled. This is a limitation
  of the camera, not the app.


### Settings
Select the "Settings" option from the option from the three dots menu (aka kebab menu) next to the camera:
![](assets/camera-menu.jpg)


#### Available Options
![](assets/camera-settings-dialog.jpg?v=2)
* **Camera Name**
  Set a custom name for the camera instead of the name received from the Bluetooth device.

* **Automatic connection**
  Android manages presence and the app confirms Sony's powered-on advertisement
  before connecting. There are no Scan/AutoConnect or Quick Connect settings.
  Some screenshots above show the original application's older UI.
  

### Unpairing
Select the "Remove camera" option from the three dots menu (see above).
Then go to your phone Bluetooth menu and unpair the camera there as well.
Finally unpair the phone from the camera on the camera Bluetooth menu.

### After phone restart
The app re-registers its companion-device observation and power-aware BLE trigger
after a restart. It does not start location or a foreground service until an
associated camera is detected as powered on.


## Privacy
The app doesn't collect any information so obviously it doesn't send anything to anyone.  
The GPS location is only used locally by the app to send to the camera, it is never collected or shared with anyone.  
The app doesn't connect to the Internet.

## Permissions
The app requires the following permission:  
**Precise Location** - Must be set to "Allow all the time" so a camera can request GPS after waking the app. Location updates run only while a camera session is active.
**Nearby Devices** - Required for using bluetooth to connect to the camera    
**Notifications** - Used for the foreground-service notification only while connecting or connected

## Troubleshooting and FAQ
* **I'm getting a "Pairing Error" message in the app**
  This means the app has detected something is wrong with the pairing between the camera
  and the phone. The best way to resolve this issue is just to pair again from scratch:
  1. Remove the camera from the app. The option to remove is in the three dots menu in 
     next to the camera name.
  2. Unpair the camera from the phone in the phone's Bluetooth menu. it may have already 
     been removed by the previous step, if so just verify that it's not listed.    
  3. Unpair the phone from the camera in the camera's Bluetooth menu.
  4. Pair again. First go to the pairing menu in the camera and then add the camera in 
     the app using the "+" button.
  5. Confirm the pairing request on both the camera and the phone.

* **The camera doesn't receive the location information while the phone is locked**  
  Verify that Location is set to "Allow all the time" and that the app has not been
  placed in Samsung's Deep sleeping apps list. Normal/Optimized battery mode is the
  supported default; "Unrestricted" should only be a troubleshooting fallback.

* **The camera doesn't receive the location information at all**  
  On cameras that work with Imaging Edge Mobile location linking has to be enabled
  in the camera menu. The menu option is at: 
  *Location Info. Link Set.* > *Location Info. Link* > On
  Also, make sure this is enabled in Creator's App and Imaging Edge Mobile if you 
  have them installed.

* **I enabled Bluetooth remote control but the app still tells me to enable it**  
  If you just enabled it then turn the camera off and then on again. This would allow
  the app to detect that it has been enabled.

* **The camera only connects to the app when it is running in the foreground**  
  Remove and associate the camera again with the "+" button, then verify Nearby Devices
  and background Location permissions. Android Companion Device Presence owns the normal
  dormant wake trigger, while a low-power Sony-filtered scan covers cameras configured to
  advertise while powered off. There is no permanent "Searching for cameras" service to
  keep alive.

* **Once in a while I get a notification to open the app in the phone's notification center**  
  Reboot and app updates are rearmed automatically. An explicit Android Force stop, a
  revoked permission, or Samsung Deep sleeping apps still requires user interaction.

* **When I enable Bluetooth Remote Control in the camera geotagging stops working**
  This ia limitation of some cameras like the a6400 and the a7 III which do not allow
  remote control and location linking to be enabled at the same time. If you enable one
  the other one gets automatically disabled.

* **I press the "half-shutter" in Remote Control but nothing happens**
  As noted, the remote control does the same thing as pressing the corresponding button
  on the camera. If you disabled the "AF w/ Shutter" option in the camera then it won't
  auto focus when you half press the shutter on the camera and not when using the remote.

* **The camera is turning on even when I power it off**
  Camera GPS Link filters Sony's manufacturer-data power bit before connecting, including
  when "Cnct. while Power Off" is enabled. A brief connection attempt can still occur
  while the camera is powering down because the a6700 keeps its on-bit set during that
  transition; the app then returns to its dormant scan. If a firmware changes the
  advertisement layout, disable that camera option and include the app's metrics/log when
  reporting the model.

### Acknowledgements
This app is inspired by previous work done by:  
[@whc2001](https://github.com/whc2001/ILCE7M3ExternalGps/blob/main/PROTOCOL_EN.md)  
[@XDjackieXD](https://github.com/whc2001/ILCE7M3ExternalGps/issues/3)  
[Greg Leeds](https://gregleeds.com/reverse-engineering-sony-camera-bluetooth/)  
Without them proving that this was even a possible task I never would have spent 
the time on trying to get it to work



## License
MIT license
