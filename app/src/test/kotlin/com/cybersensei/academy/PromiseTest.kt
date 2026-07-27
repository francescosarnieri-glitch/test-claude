package com.cybersensei.academy

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The promise the settings screen makes to the student, checked against the manifest that
 * actually ships.
 *
 * The screen tells them the app cannot reach the network and invites them to verify it. That
 * claim is only worth making because the operating system enforces it — but a dependency
 * added months from now could quietly request INTERNET and merge it in, and nobody would
 * notice until someone took the invitation seriously.
 *
 * Read from the package manager rather than the source manifest on purpose: what binds the
 * app is the merged result, including everything the libraries asked for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromiseTest {

    private fun requestedPermissions(): List<String> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        return info.requestedPermissions?.toList().orEmpty()
    }

    @Test
    fun `the app cannot reach the network`() {
        val networkPermissions = requestedPermissions().filter {
            it == android.Manifest.permission.INTERNET ||
                it == android.Manifest.permission.ACCESS_NETWORK_STATE
        }
        assertTrue(
            "La promessa fatta allo studente è saltata: $networkPermissions",
            networkPermissions.isEmpty(),
        )
    }

    /**
     * Nothing about the student's surroundings either. The professor asks for a name and a
     * birth date and has no business knowing anything else.
     */
    @Test
    fun `the app asks for nothing about the student's life`() {
        val invasive = requestedPermissions().filter { permission ->
            INVASIVE.any { permission.endsWith(it) }
        }
        assertTrue("Permesso di troppo: $invasive", invasive.isEmpty())
    }

    private companion object {
        val INVASIVE = listOf(
            "ACCESS_FINE_LOCATION",
            "ACCESS_COARSE_LOCATION",
            "READ_CONTACTS",
            "RECORD_AUDIO",
            "CAMERA",
            "READ_EXTERNAL_STORAGE",
            "READ_PHONE_STATE",
        )
    }
}
