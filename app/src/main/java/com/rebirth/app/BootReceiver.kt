package com.rebirth.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val p = ctx.getSharedPreferences("rebirth", Context.MODE_PRIVATE)
        if (p.getBoolean("autostart", false) && Settings.canDrawOverlays(ctx)) {
            try {
                ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
            } catch (e: Exception) {
            }
        }
    }
}
