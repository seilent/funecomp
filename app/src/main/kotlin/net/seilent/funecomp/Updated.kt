package net.seilent.funecomp

import android.Manifest
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

class Updated : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val p = context.getSharedPreferences(Main.PREFS, Context.MODE_PRIVATE)
        val wanted = p.getBoolean(Main.WANTED, false)
        val reconnect = if (p.contains(Main.RECONNECT)) p.getBoolean(Main.RECONNECT, false) else null
        Log.i(Remote.TAG, "updated wanted=$wanted reconnect=$reconnect")
        val spec = p.getString(Main.EXTRA_LINK, null)
        if (!wanted || spec == null || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val am = context.getSystemService(ActivityManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && am?.getHistoricalProcessExitReasons(context.packageName, 0, 1)?.firstOrNull()?.reason == ApplicationExitInfo.REASON_USER_REQUESTED) {
            Log.i(Remote.TAG, "reconnect skipped, user stop")
            p.edit().remove(Main.WANTED).apply()
            return
        }
        when (reconnect) {
            null -> p.edit().putBoolean(Main.RECONNECT_ASK, true).apply()
            false -> {}
            true -> {
                if (am?.isBackgroundRestricted == true) {
                    Log.i(Remote.TAG, "reconnect restricted")
                    return
                }
                try {
                    context.startForegroundService(
                        Intent(context, LinkService::class.java).putExtra(Main.EXTRA_LINK, spec).putExtra(LinkService.EXTRA_UPDATED, true),
                    )
                } catch (e: IllegalStateException) {
                    Log.w(Remote.TAG, "reconnect refused ${e.message}")
                } catch (e: SecurityException) {
                    Log.w(Remote.TAG, "reconnect refused ${e.message}")
                }
            }
        }
    }
}
