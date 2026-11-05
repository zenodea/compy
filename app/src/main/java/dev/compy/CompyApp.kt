package dev.compy

import android.app.Application

class CompyApp : Application() {
    val state: AppState by lazy { AppState(this) }

    override fun onCreate() {
        super.onCreate()
        state
    }
}
