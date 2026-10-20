package com.zarz.spotiflac

import android.app.Application
import com.ryanheise.audioservice.AudioServicePlugin

class SpotiFlacApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCrashReporter.initialize(this)
        // From Mornye 5.2.1: apply renderer compatibility before the
        // persistent audio_service engine can start without an Activity.
        AudioServicePlugin.setFlutterEngineArguments(flutterRendererShellArgs())
    }
}
