package io.github.openscreen.sender

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * Guards the view-only promise at the manifest level: the app must never
 * declare capabilities that would let anything control the phone.
 */
class ManifestPolicyTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    private val forbidden = listOf(
        "android.permission.BIND_ACCESSIBILITY_SERVICE",
        "android.accessibilityservice.AccessibilityService",
        "android.permission.BIND_DEVICE_ADMIN",
        "android.permission.INJECT_EVENTS",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.WRITE_SECURE_SETTINGS",
        "android.permission.READ_CONTACTS",
        "android.permission.ACCESS_FINE_LOCATION",
    )

    @Test
    fun `manifest declares no control or personal-data capabilities`() {
        for (entry in forbidden) {
            assertFalse("Manifest must not contain $entry", manifest.contains(entry))
        }
    }
}
