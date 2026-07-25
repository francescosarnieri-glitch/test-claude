package com.cybersensei.academy

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class CyberSenseiApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
