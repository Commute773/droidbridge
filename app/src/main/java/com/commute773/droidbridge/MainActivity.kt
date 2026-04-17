package com.commute773.droidbridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import android.view.Gravity
import android.view.ViewGroup
import android.text.InputType
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
    private lateinit var bearerTokenInput: EditText
    private lateinit var allowAnonymousCheckbox: CheckBox
    private lateinit var saveSettingsButton: Button
    private lateinit var toggleButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 48, 48, 48)
        }

        val titleText = TextView(this).apply {
            text = "DroidBridge"
            textSize = 28f
            gravity = Gravity.CENTER
        }
        layout.addView(titleText)

        val tokenRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 32, 0, 0)
        }

        val tokenLabel = TextView(this).apply {
            text = "Bearer token"
            textSize = 16f
            setPadding(0, 0, 24, 0)
        }
        tokenRow.addView(tokenLabel)

        bearerTokenInput = EditText(this).apply {
            hint = "Bearer token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        tokenRow.addView(
            bearerTokenInput,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        layout.addView(
            tokenRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        allowAnonymousCheckbox = CheckBox(this).apply {
            text = "Allow connections with no token"
            setPadding(0, 24, 0, 0)
        }
        layout.addView(allowAnonymousCheckbox)

        saveSettingsButton = Button(this).apply {
            text = "Save Auth Settings"
            setOnClickListener { saveAuthSettings() }
        }
        layout.addView(saveSettingsButton)

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

        val scrollView = ScrollView(this).apply {
            addView(
                layout,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        setContentView(scrollView)

        checkPermissions()
        loadAuthSettings()
        updateStatus()
        updateIp()
        autoStartServiceIfPermitted()
    }

    override fun onResume() {
        super.onResume()
        loadAuthSettings()
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

    private fun loadAuthSettings() {
        val settings = BridgePreferences.getAuthSettings(this)
        bearerTokenInput.setText(settings.bearerToken)
        allowAnonymousCheckbox.isChecked = settings.allowAnonymous
    }

    private fun saveAuthSettings() {
        BridgePreferences.saveAuthSettings(
            this,
            bearerTokenInput.text?.toString().orEmpty(),
            allowAnonymousCheckbox.isChecked
        )
        updateStatus()

        val settings = BridgePreferences.getAuthSettings(this)
        val message = when {
            settings.bearerToken.isBlank() && !settings.allowAnonymous ->
                "Settings saved. Requests now require a token, but no token is configured yet."
            BridgeService.instance != null ->
                "Settings saved. New connections will use the updated auth settings."
            else -> "Settings saved."
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun updateStatus() {
        val running = BridgeService.instance != null
        val settings = BridgePreferences.getAuthSettings(this)
        val authSummary = when {
            settings.bearerToken.isNotBlank() && settings.allowAnonymous ->
                "Auth: Bearer token configured; anonymous connections allowed"
            settings.bearerToken.isNotBlank() ->
                "Auth: Bearer token required"
            settings.allowAnonymous ->
                "Auth: No token required"
            else ->
                "Auth: Anonymous disabled and no bearer token configured"
        }
        statusText.text = if (running) {
            "Status: Running on port ${BridgeService.PORT}\n$authSummary"
        } else {
            "Status: Stopped\n$authSummary"
        }
        toggleButton.text = if (running) "Stop Server" else "Start Server"
    }

    private fun updateIp() {
        val addresses = getLocalIpAddress()
        ipText.text = if (addresses != null) {
            addresses
                .lineSequence()
                .filter { it.isNotBlank() }
                .joinToString("\n") { "http://$it:${BridgeService.PORT}" }
        } else {
            "IP: Not connected to network"
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val addresses = mutableListOf<String>()
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        addresses.add(addr.hostAddress ?: continue)
                    }
                }
            }
            return addresses.distinct().takeIf { it.isNotEmpty() }?.joinToString("\n")
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
