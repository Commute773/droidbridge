package com.commute773.droidbridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.LinearLayout
import android.view.Gravity
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    private lateinit var statusText: TextView
    private lateinit var ipText: TextView
    private lateinit var toggleButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create UI programmatically
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        val titleText = TextView(this).apply {
            text = "DroidBridge"
            textSize = 28f
            gravity = Gravity.CENTER
        }
        layout.addView(titleText)

        ipText = TextView(this).apply {
            text = "IP: ..."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 16)
        }
        layout.addView(ipText)

        statusText = TextView(this).apply {
            text = "Status: Stopped"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 32)
        }
        layout.addView(statusText)

        toggleButton = Button(this).apply {
            text = "Start Server"
            setOnClickListener { toggleService() }
        }
        layout.addView(toggleButton)

        setContentView(layout)

        checkPermissions()
        updateStatus()
        updateIp()
        autoStartServiceIfPermitted()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updateIp()
        autoStartServiceIfPermitted()
    }

    private fun autoStartServiceIfPermitted() {
        // Skip if already running.
        if (BridgeService.instance != null) return
        // Only start if all required perms are granted; otherwise the user will
        // see the Start Server button after accepting the prompts.
        if (!hasAllPermissions()) return
        val serviceIntent = Intent(this, BridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        statusText.postDelayed({ updateStatus() }, 500)
    }

    private fun hasAllPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return false
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return false
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false
        return true
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    private fun toggleService() {
        val serviceIntent = Intent(this, BridgeService::class.java)

        if (BridgeService.instance != null) {
            stopService(serviceIntent)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }

        // Update UI after a short delay
        statusText.postDelayed({ updateStatus() }, 500)
    }

    private fun updateStatus() {
        val running = BridgeService.instance != null
        statusText.text = if (running) {
            "Status: Running on port ${BridgeService.PORT}"
        } else {
            "Status: Stopped"
        }
        toggleButton.text = if (running) "Stop Server" else "Start Server"
    }

    private fun updateIp() {
        val ip = getLocalIpAddress()
        ipText.text = if (ip != null) {
            "http://$ip:${BridgeService.PORT}"
        } else {
            "IP: Not connected to network"
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                statusText.text = "Status: Permissions required"
            } else {
                autoStartServiceIfPermitted()
            }
        }
    }
}
