package com.fnyoat.donothingvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.IOException

/**
 * A VPN that establishes a connection but routes nothing, so real traffic
 * stays on the underlying network. Its only purpose is to control the
 * session name shown next to "Connected to" by the system.
 */
class FakeVpnService : VpnService(), Runnable {

    private var tunnel: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private var sessionName: String = DEFAULT_NAME

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        sessionName = intent?.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() }
            ?: loadName()
        saveName(sessionName)

        lastError = null
        isRunning = true
        notifyStateChanged()
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            lastError = e.message
            fail()
            return START_NOT_STICKY
        }
        if (!establish()) {
            fail()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun fail() {
        isRunning = false
        notifyStateChanged()
        stopSelf()
    }

    override fun run() {
        val pfd = tunnel ?: return
        try {
            val input = FileInputStream(pfd.fileDescriptor)
            val buffer = ByteArray(32768)
            while (isRunning) {
                if (input.read(buffer) < 0) break
            }
        } catch (_: IOException) {
            // Tunnel closed.
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        teardown()
        isRunning = false
        notifyStateChanged()
        super.onDestroy()
    }

    private fun teardown() {
        reader?.interrupt()
        reader = null
        tunnel?.close()
        tunnel = null
    }

    private fun establish(): Boolean {
        // Always rebuild so a changed session name becomes active.
        if (tunnel != null) {
            reader?.interrupt()
            reader = null
            tunnel?.close()
            tunnel = null
        }
        val builder = Builder()
            .setSession(sessionName)
            // A private /32 kept for the interface only; nothing routes through it.
            .addAddress(TUN_ADDRESS, 32)
            .addRoute(TUN_ADDRESS, 32)
        tunnel = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish failed", e)
            lastError = e.message
            null
        }
        if (tunnel == null) {
            Log.w(TAG, "establish returned null")
            if (lastError == null) lastError = "VPN establishment failed."
            return false
        }
        reader = Thread(this, "phn_discard").also { it.start() }
        notifyStateChanged()
        return true
    }

    private fun notifyStateChanged() {
        stateListener?.invoke()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle(getString(R.string.notif_title, sessionName))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
        return notification.build()
    }

    private fun saveName(name: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_NAME, name).apply()
    }

    private fun loadName(): String {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_NAME, DEFAULT_NAME).orEmpty()
    }

    companion object {
        const val EXTRA_NAME = "session_name"

        private const val TAG = "DoNothingVPN"
        private const val TUN_ADDRESS = "10.64.0.1"
        private const val DEFAULT_NAME = "DoNothingVPN"
        private const val PREFS_NAME = "config"
        private const val KEY_NAME = "session_name"
        private const val CHANNEL_ID = "vpn_status"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var lastError: String? = null
            private set

        @Volatile
        var stateListener: (() -> Unit)? = null

        @Volatile
        private var instance: FakeVpnService? = null

        fun start(context: Context, name: String) {
            val intent = Intent(context, FakeVpnService::class.java).putExtra(EXTRA_NAME, name)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            isRunning = false
            instance?.teardown()
            context.stopService(Intent(context, FakeVpnService::class.java))
        }

        fun loadSavedName(context: Context): String {
            return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_NAME, DEFAULT_NAME).orEmpty()
        }
    }
}