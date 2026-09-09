package com.fnyoat.donothingvpn

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var nameInput: EditText
    private lateinit var connectButton: Button
    private lateinit var statusView: TextView

    private var connecting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        nameInput = findViewById(R.id.name_input)
        connectButton = findViewById(R.id.connect_button)
        statusView = findViewById(R.id.status_view)

        nameInput.setText(FakeVpnService.loadSavedName(this))
        connectButton.setOnClickListener { onConnectClicked() }

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
            return
        }
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.name_required, Toast.LENGTH_SHORT).show()
            return
        }
        requestVpnPermission()
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
            when {
                FakeVpnService.isRunning -> R.string.status_connected
                connecting -> R.string.status_starting
                else -> R.string.status_disconnected
            }
        )
        nameInput.isEnabled = !FakeVpnService.isRunning && !connecting
        connectButton.setText(
            if (FakeVpnService.isRunning || connecting) R.string.button_disconnect else R.string.button_connect
        )
    }

    companion object {
        private const val TAG = "DoNothingVPN"
        private const val REQUEST_VPN = 1
        private const val REQUEST_NOTIF = 2
    }
}