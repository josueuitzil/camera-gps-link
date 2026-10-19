This fork does not upload telemetry, diagnostics, or location to a server.
Location is obtained through Google Play services on the phone and sent to the
connected camera over Bluetooth. Google's privacy policy is available at
https://policies.google.com/privacy.

Camera settings, Bluetooth addresses, reconnection counters and bounded diagnostic
logs are stored locally in the app's private storage. Diagnostics include app/process
state, crash information and Android power restrictions; the activity log may include
approximate coordinates. Logs are shared with another application only when the user
chooses Share Log. Review a log before sending it to someone else.

The fork is installed independently of the official Google Play application and
does not request Play Store ratings.
