package com.rebirth.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo

object GameDetector {
    fun hasAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun foreground(ctx: Context): String? {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 10 * 60 * 1000, now)
        val e = UsageEvents.Event()
        var pkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND &&
                e.packageName != "com.android.systemui"
            ) {
                pkg = e.packageName
            }
        }
        return pkg
    }

    fun gameName(ctx: Context, pkg: String): String? {
        if (pkg == ctx.packageName) return null
        return try {
            val info = ctx.packageManager.getApplicationInfo(pkg, 0)
            if (info.category == ApplicationInfo.CATEGORY_GAME)
                ctx.packageManager.getApplicationLabel(info).toString()
            else null
        } catch (e: Exception) {
            null
        }
    }
}
