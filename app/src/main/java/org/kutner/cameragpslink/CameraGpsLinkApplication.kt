package org.kutner.cameragpslink

import android.app.Application

class CameraGpsLinkApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Presence receivers can be the first component created after days of dormancy.
        DiagnosticsLog.initialize(this)
    }
}
