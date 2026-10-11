package com.fauxx.service

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the foreground-service type in the *merged* manifest of the full flavor.
 * [android.app.Service.startForeground] throws when the runtime type doesn't match what the
 * manifest declares, so losing or changing `foregroundServiceType` in the flavor overlay would
 * crash the engine at start, exactly the kind of manifest drift a source grep can miss. CI runs
 * this via `connectedFullDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class ManifestFgsTypeTest {

    @Test
    fun phantomForegroundService_declaresSpecialUseType() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val component = ComponentName(context, PhantomForegroundService::class.java)

        @Suppress("DEPRECATION")
        val info = context.packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)

        assertEquals(
            "PhantomForegroundService must declare foregroundServiceType=specialUse in the merged " +
                "manifest (was ${info.foregroundServiceType})",
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            info.foregroundServiceType,
        )
    }
}
