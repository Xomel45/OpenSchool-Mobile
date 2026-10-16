package io.github.xomel45.openschool

import android.app.Application

/** The app process. (TLS needs no setup: the Rust library carries its own certificate roots.) */
class OpenSchoolApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashNote.install(this)
        // The User-Agent carries the app's version (gradle.properties), the bridge adds its own; before any client is made.
        uniffi.openschool_bridge.setAppVersion(BuildConfig.VERSION_NAME)
    }
}
