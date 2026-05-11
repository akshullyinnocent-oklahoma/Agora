package com.newoether.agora.service

object AppForegroundTracker {
    @Volatile
    var isInForeground: Boolean = false
}
