package com.trguitar.practicepad

import android.service.notification.NotificationListenerService

// Only exists so the user can grant notification access, which lets this app
// see and control other apps' media sessions (YouTube). It reads no notifications.
class MediaListener : NotificationListenerService()
