package com.example.prototipopagosoffline

import android.app.Application
import com.example.prototipopagosoffline.sync.AutoSyncMonitor

class PaymentsApplication : Application() {
    private lateinit var autoSync: AutoSyncMonitor
    override fun onCreate() {
        super.onCreate()
        autoSync = AutoSyncMonitor(applicationContext)
        autoSync.start()
    }
}
