package com.fnyoat.donothingvpn

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {

    private lateinit var nameInput: EditText
    private lateinit var connectButton: Button
    private lateinit var statusView: TextView

    private lateinit var tokenInput: EditText
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

        tokenInput = findViewById(R.id.token_input)
        renameInput = findViewById(R.id.rename_input)
        renameButton = findViewById(R.id.rename_button)
        renameStatus = findViewById(R.id.rename_status)

        nameInput.setText(FakeVpnService.loadSavedName(this))
        connectButton.setOnClickListener { onConnectClicked() }
        renameButton.setOnClickListener { onRenameClicked() }

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_TOKEN, null)?.let { saved ->
            tokenInput.setText(saved)
        }

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
        var token = tokenInput.text.toString().trim()
        if (token.isEmpty()) {
            token = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_TOKEN, null).orEmpty()
        }
        if (token.isEmpty()) {
            Toast.makeText(this, R.string.rename_no_token, Toast.LENGTH_SHORT).show()
            return
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_TOKEN, token).apply()

        renameButton.isEnabled = false
        setRenameStatus(R.string.rename_submitted)
        Thread {
            try {
                if (!GitHubUpdater.dispatchRename(token, newName)) {
                    setRenameFailed(getString(R.string.rename_failed_prefix) + "dispatch")
                    return@Thread
                }
                setRenameStatus(R.string.rename_waiting)
                val startedAt = System.currentTimeMillis()
                var run: JSONObject? = null
                while (System.currentTimeMillis() - startedAt < RENAME_TIMEOUT_MS) {
                    val r = GitHubUpdater.findLatestCompletedRun(token, startedAt - 5000)
                    if (r != null) {
                        run = r
                        break
                    }
                    Thread.sleep(5000)
                }
                if (run == null) {
                    setRenameFailed(getString(R.string.rename_failed_prefix) + "timeout")
                    return@Thread
                }
                val conclusion = run.optString("conclusion")
                if (conclusion != "success") {
                    setRenameFailed(getString(R.string.rename_failed_prefix) + "build $conclusion")
                    return@Thread
                }
                setRenameStatus(R.string.rename_downloading)
                val destDir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir, "")
                val apk = GitHubUpdater.downloadApk(token, run.getLong("id"), destDir)
                if (apk == null) {
                    setRenameFailed(getString(R.string.rename_failed_prefix) + "download")
                    return@Thread
                }
                setRenameStatus(R.string.rename_installing)
                mainHandler.post { installApk(apk) }
            } catch (e: Exception) {
                Log.e(TAG, "rename flow failed", e)
                setRenameFailed(getString(R.string.rename_failed_prefix) + (e.message ?: "error"))
            }
        }.start()
    }

    private fun installApk(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "install intent failed", e)
            setRenameFailed(getString(R.string.rename_failed_prefix) + "install")
        }
        renameButton.isEnabled = true
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
        private const val PREFS_NAME = "config"
        private const val KEY_TOKEN = "gh_token"
        private const val RENAME_TIMEOUT_MS = 6 * 60 * 1000L
    }
}