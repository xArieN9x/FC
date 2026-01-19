package com.example.cedokbooster

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class AnchorService : Service() {

    companion object {
        private const val TAG = "AnchorService"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AnchorService created - keeping app alive")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "AnchorService started")
        return START_STICKY // Restart service if killed by system
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed - restarting service")
        // Restart service when app is removed from recent apps
        val restartServiceIntent = Intent(applicationContext, AnchorService::class.java)
        applicationContext.startService(restartServiceIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "AnchorService destroyed")
    }
}
