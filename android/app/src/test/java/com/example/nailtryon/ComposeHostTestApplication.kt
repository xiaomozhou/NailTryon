package com.example.nailtryon

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import org.robolectric.Shadows.shadowOf

/** Register the same empty Compose host in either build variant, only inside Robolectric.
 * ui-test-manifest contributes this Activity to debug, but is intentionally absent from release APKs.
 * Keep the host out of production manifests while running identical assertions for both variants.
 */
class ComposeHostTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        shadowOf(packageManager).addActivityIfNotPresent(ComponentName(this, ComponentActivity::class.java)).apply {
            exported = true
            if (theme == 0) theme = android.R.style.Theme_Material_Light_NoActionBar
        }
    }
}
