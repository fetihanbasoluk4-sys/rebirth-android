package com.rebirth.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotifService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val n = sbn.notification ?: return
            if (sbn.packageName == packageName) return
            if (sbn.isOngoing) return
            if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
            val ex = n.extras
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
            if (title.isBlank() && text.isBlank()) return
            OverlayService.instance?.onNotif(sbn.packageName, title, text, n.category ?: "")
        } catch (e: Exception) {
        }
    }
}
