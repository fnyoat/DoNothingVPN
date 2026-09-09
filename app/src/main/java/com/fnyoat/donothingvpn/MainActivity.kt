package com.fnyoat.donothingvpn

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnManager
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        nameInput = findViewById(R.id.name_input)
        connectButton = findViewById(R.id.connect_button)
        statusView = findViewById(R.id.status_view)

        nameInput.setText(FakeVpnService.loadSavedName(this))
        connectButton.setOnClickListener { onConnectClicked() }

        FakeVpnService.lastError?.let { error ->
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
        }
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
                Toast.makeText(this, R.string.vpn_denied_hint, Toast.LENGTH_LONG).show()
                updateUi()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun onConnectClicked() {
        if (FakeVpnService.isRunning) {
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
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
            updateUi()
            return
        }
        statusView.setText(R.string.status_vpn_authorizing)
        val prepare = try {
            VpnService.prepare(this)
        } catch (e: Exception) {
            Log.e(TAG, "prepare failed", e)
            Toast.makeText(this, R.string.start_failed, Toast.LENGTH_LONG).show()
            updateUi()
            null
        }
        if (prepare != null) {
            Log.d(TAG, "prepare intent: $prepare")
            try {
                startActivityForResult(prepare, REQUEST_VPN)
            } catch (e: Exception) {
                Log.e(TAG, "show vpn authorization failed", e)
                Toast.makeText(this, R.string.start_failed, Toast.LENGTH_LONG).show()
                updateUi()
            }
        } else {
            startVpn()
        }
    }

    private fun vpnBlockReason(): CharSequence? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val vpnManager = getSystemService(VpnManager::class.java)
                val alwaysOn = vpnManager.getAlwaysOnVpnPackage()
                if (alwaysOn != null && alwaysOn != packageName) {
                    return getString(R.string.reason_always_on_vpn, alwaysOn)
                }
            } catch (e: Exception) {
                Log.e(TAG, "always-on vpn check failed", e)
            }
        }
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
        statusView.setText(R.string.status_starting)
        try {
            FakeVpnService.start(this, name)
        } catch (e: Exception) {
            Log.e(TAG, "start service failed", e)
            Toast.makeText(this, R.string.start_failed, Toast.LENGTH_LONG).show()
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

    private fun updateUi() {
        statusView.setText(if (FakeVpnService.isRunning) R.string.status_connected else R.string.status_disconnected)
        nameInput.isEnabled = !FakeVpnService.isRunning
        connectButton.setText(
            if (FakeVpnService.isRunning) R.string.button_disconnect else R.string.button_connect
        )
    }

    companion object {
        private const val TAG = "DoNothingVPN"
        private const val REQUEST_VPN = 1
        private const val REQUEST_NOTIF = 2
    }
}