package ua.grey.qstarlight.sync

import ua.grey.qstarlight.diagnostics.DiagnosticLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ConfigSyncStatus {
    @Volatile private var message = "Ще немає підтвердження іншого пристрою"
    @Volatile private var confirmedVersion: ConfigVersion? = null

    fun waiting(text: String) {
        confirmedVersion = null
        message = text
        DiagnosticLog.write("SYNC", text)
    }

    fun confirmed(version: ConfigVersion, peer: String) {
        confirmedVersion = version
        message = "Підтверджено $peer • " + SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        DiagnosticLog.write("SYNC", "$message rev=${version.revision} origin=${version.origin}")
    }

    fun summary(current: ConfigVersion): String {
        val text = if (confirmedVersion != null && confirmedVersion != current) {
            "Є нові зміни • очікую підтвердження"
        } else message
        return "$text • rev ${current.revision}"
    }
}
