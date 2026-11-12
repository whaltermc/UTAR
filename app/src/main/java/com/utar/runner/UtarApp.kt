package com.utar.runner

import android.app.Application
import com.utar.runner.runtime.SessionLog

class UtarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SessionLog.init(this)
    }
}
