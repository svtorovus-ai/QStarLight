package ua.grey.qstarlight

import android.app.Application
import ua.grey.qstarlight.update.UpdateScheduler

class QStarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PresenceMonitor.ensure(this)
        UpdateScheduler.ensure(this)
    }
}
