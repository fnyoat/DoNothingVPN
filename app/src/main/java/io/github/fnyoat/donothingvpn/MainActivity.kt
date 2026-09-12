package io.github.fnyoat.donothingvpn

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private lateinit var nameInput: EditText
    private lateinit var connectButton: Button
    private lateinit var statusView: TextView

    private lateinit var renameInput: EditText
    private lateinit var renameButton: Button
    private lateinit var renameStatus: TextView

    private var connecting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        nameInput = findViewById(R.id.name_input)
        connectButton = findViewById(R.id.connect_button)
        statusView = findViewById(R.id.status_view)

        renameInput = findViewById(R.id.rename_input)
        renameButton = findViewById(R.id.rename_button)
        renameStatus = findViewById(R.id.rename_status)

        nameInput.setText(FakeVpnService.loadSavedName(this))
        connectButton.setOnClickListener { onConnectClicked() }
        renameButton.setOnClickListener { onRenameClicked() }
        packageManager.packageInstaller.registerSessionCallback(sessionCallback)

        FakeVpnService.lastError?.let { error ->
            showErrorDialog(getString(R.string.dialog_failed_title), error)
        }
    }

    override fun onStart() {
        super.onStart()
        FakeVpnService.stateListener = {
            runOnUiThread {
                if (!FakeVpnService.isRunning && connecting) {
                    connecting = false
                    FakeVpnService.lastError?.let { error ->
                        showErrorDialog(getString(R.string.dialog_failed_title), error)
                    }
                }
                updateUi()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        FakeVpnService.stateListener = null
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { packageManager.packageInstaller.unregisterSessionCallback(sessionCallback) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN) {
            if (resultCode == RESULT_OK) {
                startVpn()
            } else {
                Log.w(TAG, "VPN authorization declined (resultCode=$resultCode)")
                showErrorDialog(getString(R.string.dialog_declined_title), getString(R.string.dialog_declined_message))
                updateUi()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun onConnectClicked() {
        if (FakeVpnService.isRunning || connecting) {
            connecting = false
            FakeVpnService.stop(this)
            updateUi()
            checkAndWarnIfRestarted()
            return
        }
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.name_required, Toast.LENGTH_SHORT).show()
            return
        }
        requestVpnPermission()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun checkAndWarnIfRestarted() {
        mainHandler.postDelayed({
            if (FakeVpnService.isRunning) {
                showErrorDialog(getString(R.string.dialog_always_on_title), getString(R.string.dialog_always_on_message))
            }
        }, 800L)
    }

    private fun requestVpnPermission() {
        vpnBlockReason()?.let { reason ->
            showErrorDialog(getString(R.string.dialog_failed_title), reason)
            updateUi()
            return
        }
        statusView.setText(R.string.status_vpn_authorizing)
        val prepare = try {
            VpnService.prepare(this)
        } catch (e: Exception) {
            Log.e(TAG, "prepare failed", e)
            showErrorDialog(getString(R.string.dialog_failed_title), e.message ?: getString(R.string.start_failed))
            updateUi()
            null
        }
        if (prepare != null) {
            Log.d(TAG, "prepare intent: $prepare")
            try {
                startActivityForResult(prepare, REQUEST_VPN)
            } catch (e: Exception) {
                Log.e(TAG, "show vpn authorization failed", e)
                showErrorDialog(getString(R.string.dialog_failed_title), e.message ?: getString(R.string.start_failed))
                updateUi()
            }
        } else {
            startVpn()
        }
    }

    private fun vpnBlockReason(): CharSequence? {
        try {
            val um = getSystemService(UserManager::class.java)
            if (um.hasUserRestriction(UserManager.DISALLOW_CONFIG_VPN)) {
                return getString(R.string.reason_vpn_restricted)
            }
        } catch (e: Exception) {
            Log.e(TAG, "vpn restriction check failed", e)
        }
        return null
    }

    private fun startVpn() {
        val name = nameInput.text.toString().trim()
        Log.d(TAG, "startVpn name=$name")
        requestNotificationPermission()
        connecting = true
        statusView.setText(R.string.status_starting)
        try {
            FakeVpnService.start(this, name)
        } catch (e: Exception) {
            Log.e(TAG, "start service failed", e)
            connecting = false
            showErrorDialog(getString(R.string.dialog_failed_title), e.message ?: getString(R.string.start_failed))
        }
        updateUi()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIF)
        }
    }

    private fun showErrorDialog(title: String, message: CharSequence) {
        try {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "show dialog failed", e)
        }
    }

    private fun updateUi() {
        statusView.setText(
            if (FakeVpnService.isRunning) {
                getString(R.string.status_connected, FakeVpnService.currentName ?: "")
            } else if (connecting) {
                getString(R.string.status_starting)
            } else {
                getString(R.string.status_disconnected)
            }
        )
        nameInput.isEnabled = !FakeVpnService.isRunning && !connecting
        connectButton.setText(
            if (FakeVpnService.isRunning || connecting) R.string.button_disconnect else R.string.button_connect
        )
    }

    private fun onRenameClicked() {
        val newName = renameInput.text.toString().trim()
        if (newName.isEmpty()) {
            Toast.makeText(this, R.string.rename_no_name, Toast.LENGTH_SHORT).show()
            return
        }
        val currentName = try {
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (_: Exception) {
            null
        }
        if (newName == currentName) {
            Toast.makeText(this, R.string.rename_same_name, Toast.LENGTH_SHORT).show()
            return
        }
        renameButton.isEnabled = false
        setRenameStatus(R.string.rename_submitted)
        Thread {
            try {
                val out = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir, "repacked.apk")
                out.delete()
                Repackager.build(this, File(applicationInfo.sourceDir), out, newName)
                setRenameStatus(R.string.rename_installing)
                installApk(out)
            } catch (e: Exception) {
                Log.e(TAG, "repack flow failed", e)
                setRenameFailed(getString(R.string.rename_failed_prefix) + (e.message ?: "error"))
            }
        }.start()
    }

    private fun installApk(file: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            runOnUiThread {
                renameStatus.text = getString(R.string.rename_need_source_permission)
                renameButton.isEnabled = true
            }
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                Log.e(TAG, "open unknown app sources failed", e)
            }
            return
        }
        try {
            val installer = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            try {
                file.inputStream().use { input ->
                    val out = session.openWrite("repacked", 0, file.length())
                    input.copyTo(out)
                    session.fsync(out)
                    out.close()
                }
                val sender = PendingIntent.getBroadcast(
                    this,
                    sessionId,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ).intentSender
                session.commit(sender)
                Log.d(TAG, "package installer session $sessionId committed")
            } finally {
                runCatching { session.close() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "package installer failed", e)
            setRenameFailed(getString(R.string.rename_failed_prefix) + "install")
        }
    }

    private val sessionCallback = object : PackageInstaller.SessionCallback() {
        override fun onCreated(sessionId: Int) {}
        override fun onBadgingChanged(sessionId: Int) {}
        override fun onActiveChanged(sessionId: Int, active: Boolean) {}
        override fun onProgressChanged(sessionId: Int, progress: Float) {}

        override fun onFinished(sessionId: Int, success: Boolean) {
            runOnUiThread {
                if (success) {
                    renameStatus.setText(R.string.rename_done)
                } else {
                    setRenameFailed(getString(R.string.rename_failed_prefix) + "install")
                }
                setRenameIdle()
            }
        }
    }

    private fun setRenameIdle() {
        runOnUiThread { renameButton.isEnabled = true }
    }

    private fun setRenameStatus(strId: Int) {
        runOnUiThread { renameStatus.setText(strId) }
    }

    private fun setRenameFailed(message: String) {
        runOnUiThread {
            renameStatus.text = message
            renameButton.isEnabled = true
        }
    }

    companion object {
        private const val TAG = "DoNothingVPN"
        private const val REQUEST_VPN = 1
        private const val REQUEST_NOTIF = 2
    }
}