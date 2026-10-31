package net.seilent.funecomp

import android.app.Service
import android.content.Intent
import android.os.IBinder

class SvcLoginHost : Service() {
    override fun onBind(intent: Intent?): IBinder = Svc.binder
}
