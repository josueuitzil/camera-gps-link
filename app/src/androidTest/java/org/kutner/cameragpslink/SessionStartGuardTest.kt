package org.kutner.cameragpslink

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionStartGuardTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun clearTestNotification() {
        NotificationHelper(context).cancel(Constants.NOTIFICATION_ID_PERMISSIONS_REQUIRED)
        SessionStartGuard.clearRejection(context, "00:11:22:33:44:55")
    }

    @Test fun permissionRevocationDoesNotCrashOrRequestARestart() {
        assertFalse(SessionStartGuard.run(context, "00:11:22:33:44:55", "test-permission") {
            throw SecurityException("Location permission revoked")
        })
        assertTrue(context.getSharedPreferences("session_start_guard", Context.MODE_PRIVATE)
            .getStringSet("rejected_cameras", emptySet()).orEmpty().contains("00:11:22:33:44:55"))
    }

    @Test fun backgroundStartRejectionDoesNotEscapeToPresenceReceiver() {
        assertFalse(SessionStartGuard.run(context, "00:11:22:33:44:55", "test-background") {
            throw ForegroundServiceStartNotAllowedException("Background start rejected")
        })
    }

    @Test fun allowedStartRunsExactlyOnce() {
        var launches = 0
        assertTrue(SessionStartGuard.run(context, "00:11:22:33:44:55", "test-success") { launches++ })
        assertTrue(launches == 1)
    }
}
