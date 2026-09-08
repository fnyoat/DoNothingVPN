package com.fnyoat.donothingvpn

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
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
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN && resultCode == RESULT_OK) {
            startVpn()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIF) {
            requestVpnPermission(requireNotification = false)
        }
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

    private fun requestVpnPermission(requireNotification: Boolean = true) {
        if (requireNotification &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIF)
            return
        }
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            startActivityForResult(prepare, REQUEST_VPN)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        val name = nameInput.text.toString().trim()
        FakeVpnService.start(this, name)
        updateUi()
    }

    private fun updateUi() {
        statusView.setText(if (FakeVpnService.isRunning) R.string.status_connected else R.string.status_disconnected)
        nameInput.isEnabled = !FakeVpnService.isRunning
        connectButton.setText(
            if (FakeVpnService.isRunning) R.string.button_disconnect else R.string.button_connect
        )
    }

    companion object {
        private const val REQUEST_VPN = 1
        private const val REQUEST_NOTIF = 2
    }
}