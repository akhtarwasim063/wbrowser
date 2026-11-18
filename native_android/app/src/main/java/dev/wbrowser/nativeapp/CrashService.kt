package dev.wbrowser.nativeapp

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import org.mozilla.geckoview.GeckoRuntime
import java.io.File

/** Receives Gecko native-crash reports and keeps the reason in filesDir/last_crash.txt. */
class CrashService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == GeckoRuntime.ACTION_CRASHED) {
            val extrasPath = intent.getStringExtra(GeckoRuntime.EXTRA_EXTRAS_PATH)
            val dump = intent.getStringExtra(GeckoRuntime.EXTRA_MINIDUMP_PATH)
            val type = intent.getStringExtra(GeckoRuntime.EXTRA_CRASH_PROCESS_TYPE)
            val extras = runCatching { File(extrasPath!!).readText() }.getOrDefault("(no extras)")
            val report = "type=$type\ndump=$dump\n$extras"
            Log.e("WBCrash", report)
            runCatching { File(filesDir, "last_crash.txt").writeText(report) }
        }
        stopSelf()
        return START_NOT_STICKY
    }
}
