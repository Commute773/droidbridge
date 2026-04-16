package com.commute773.droidbridge

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {
    companion object {
        private const val TAG = "BleManager"
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val handler = Handler(Looper.getMainLooper())

    // Connected GATT clients
    private val gattClients = ConcurrentHashMap<String, BluetoothGatt>()

    // Discovered services cache
    private val servicesCache = ConcurrentHashMap<String, List<ServiceInfo>>()

    // Per-device GATT serialization. Android's GATT layer accepts exactly one
    // outstanding operation per connection (write, read, writeDescriptor,
    // discoverServices, requestMtu all share the same queue); the callback
    // fires when the controller has finished it. Without serializing, a
    // second op issued while the first is still pending returns false (or
    // silently drops) with no warning. We use one lock per device plus a
    // latch per op type.
    private val gattLocks = ConcurrentHashMap<String, Any>()
    private val connectLatches = ConcurrentHashMap<String, CountDownLatch>()
    private val connectedSet = ConcurrentHashMap<String, Boolean>() // true only when onConnectionStateChange CONNECTED fired
    private val writeLatches = ConcurrentHashMap<String, CountDownLatch>()
    private val writeStatus = ConcurrentHashMap<String, Int>()
    private val descriptorLatches = ConcurrentHashMap<String, CountDownLatch>()
    private val descriptorStatus = ConcurrentHashMap<String, Int>()
    private val discoverLatches = ConcurrentHashMap<String, CountDownLatch>()
    private val discoverStatus = ConcurrentHashMap<String, Int>()
    private val writeTimeoutMs = 5_000L
    private val discoverTimeoutMs = 10_000L

    // Callbacks for notifications
    var onNotification: ((address: String, charUuid: String, data: ByteArray) -> Unit)? = null
    var onConnectionStateChange: ((address: String, connected: Boolean) -> Unit)? = null
    var onScanResult: ((ScanResultInfo) -> Unit)? = null

    data class ScanResultInfo(
        val address: String,
        val name: String?,
        val rssi: Int,
        val manufacturerData: Map<Int, ByteArray>?
    )

    data class ServiceInfo(
        val uuid: String,
        val characteristics: List<CharacteristicInfo>
    )

    data class CharacteristicInfo(
        val uuid: String,
        val properties: Int,
        val descriptors: List<String>
    )

    // Scanning
    private var scanCallback: ScanCallback? = null

    fun startScan(filterUuids: List<String>? = null): Boolean {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return false

        stopScan()

        val filters = filterUuids?.map { uuid ->
            ScanFilter.Builder()
                .setServiceUuid(android.os.ParcelUuid.fromString(uuid))
                .build()
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val mfgData = result.scanRecord?.manufacturerSpecificData?.let { data ->
                    (0 until data.size()).associate { i ->
                        data.keyAt(i) to data.valueAt(i)
                    }
                }
                onScanResult?.invoke(ScanResultInfo(
                    address = result.device.address,
                    name = result.device.name,
                    rssi = result.rssi,
                    manufacturerData = mfgData
                ))
            }
        }

        if (filters != null) {
            scanner.startScan(filters, settings, scanCallback)
        } else {
            scanner.startScan(null, settings, scanCallback)
        }

        Log.i(TAG, "Scan started")
        return true
    }

    fun stopScan() {
        scanCallback?.let {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(it)
            scanCallback = null
            Log.i(TAG, "Scan stopped")
        }
    }

    // Connection.
    //
    // Blocks until the GATT link reaches STATE_CONNECTED or times out.
    // The Android stack's connectGatt() returns immediately and merely
    // queues the connection attempt — without this wait, /connect returns
    // "ok" on devices that never actually come up, then /write and
    // /discover issued against the dead link fail or hang.
    //
    // If we already have a connectGatt handle but it hasn't fired
    // STATE_CONNECTED yet, we wait on the pending latch (no new
    // connectGatt). If the link was already CONNECTED we return
    // immediately.
    fun connect(address: String, timeoutMs: Long = 10_000): Boolean {
        if (connectedSet[address] == true) {
            Log.w(TAG, "Already connected to $address")
            return true
        }

        var latch = connectLatches[address]
        if (latch == null) {
            val device = bluetoothAdapter?.getRemoteDevice(address) ?: return false
            val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                ?: return false
            gattClients[address] = gatt
            latch = CountDownLatch(1)
            connectLatches[address] = latch
            Log.i(TAG, "Connecting to $address (waiting up to ${timeoutMs}ms)")
        } else {
            Log.i(TAG, "Connect to $address already in flight, joining wait")
        }

        val done = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!done) {
            Log.w(TAG, "Connect timeout for $address")
            // Tear down the stale attempt so subsequent calls start fresh.
            gattClients.remove(address)?.let {
                try { it.disconnect() } catch (_: Throwable) {}
                try { it.close() } catch (_: Throwable) {}
            }
            connectLatches.remove(address)
            return false
        }
        return connectedSet[address] == true
    }

    fun disconnect(address: String): Boolean {
        val gatt = gattClients.remove(address)
        if (gatt != null) {
            gatt.disconnect()
            gatt.close()
            servicesCache.remove(address)
            connectedSet.remove(address)
            connectLatches.remove(address)?.countDown()
            Log.i(TAG, "Disconnected from $address")
            return true
        }
        return false
    }

    fun disconnectAll() {
        gattClients.keys.toList().forEach { disconnect(it) }
    }

    fun isConnected(address: String): Boolean {
        return connectedSet[address] == true
    }

    fun getConnectedDevices(): List<String> {
        return gattClients.keys.toList()
    }

    // Services
    fun discoverServices(address: String): Boolean {
        val gatt = gattClients[address] ?: return false
        val lock = gattLocks.getOrPut(address) { Any() }
        synchronized(lock) {
            val latch = CountDownLatch(1)
            discoverLatches[address] = latch
            discoverStatus.remove(address)

            val started = gatt.discoverServices()
            if (!started) {
                discoverLatches.remove(address)
                Log.w(TAG, "discoverServices: kernel rejected discover for $address")
                return false
            }

            val completed = try {
                latch.await(discoverTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            discoverLatches.remove(address)

            if (!completed) {
                Log.w(TAG, "discoverServices: timeout for $address")
                return false
            }

            val status = discoverStatus.remove(address) ?: BluetoothGatt.GATT_FAILURE
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "discoverServices: onServicesDiscovered status=$status")
                return false
            }
            return true
        }
    }

    fun getServices(address: String): List<ServiceInfo>? {
        return servicesCache[address]
    }

    // Read/Write
    fun readCharacteristic(address: String, serviceUuid: String, charUuid: String): Boolean {
        val gatt = gattClients[address] ?: return false
        val service = gatt.getService(UUID.fromString(serviceUuid)) ?: return false
        val char = service.getCharacteristic(UUID.fromString(charUuid)) ?: return false
        return gatt.readCharacteristic(char)
    }

    fun writeCharacteristic(
        address: String,
        serviceUuid: String,
        charUuid: String,
        data: ByteArray,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    ): Boolean {
        val gatt = gattClients[address] ?: return false
        val service = gatt.getService(UUID.fromString(serviceUuid)) ?: return false
        val char = service.getCharacteristic(UUID.fromString(charUuid)) ?: return false

        val lock = gattLocks.getOrPut(address) { Any() }
        synchronized(lock) {
            char.writeType = writeType
            char.value = data

            val latch = CountDownLatch(1)
            writeLatches[address] = latch
            writeStatus.remove(address)

            val started = gatt.writeCharacteristic(char)
            if (!started) {
                writeLatches.remove(address)
                Log.w(TAG, "writeCharacteristic: kernel rejected write for $address")
                return false
            }

            val completed = try {
                latch.await(writeTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            writeLatches.remove(address)

            if (!completed) {
                Log.w(TAG, "writeCharacteristic: timeout waiting for ack on $address")
                return false
            }

            val status = writeStatus.remove(address) ?: BluetoothGatt.GATT_FAILURE
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "writeCharacteristic: onCharacteristicWrite status=$status")
                return false
            }
            return true
        }
    }

    fun setNotification(address: String, serviceUuid: String, charUuid: String, enable: Boolean): Boolean {
        val gatt = gattClients[address] ?: run {
            Log.e(TAG, "setNotification: no gatt for $address")
            return false
        }
        val service = gatt.getService(UUID.fromString(serviceUuid)) ?: run {
            Log.e(TAG, "setNotification: no service $serviceUuid")
            return false
        }
        val char = service.getCharacteristic(UUID.fromString(charUuid)) ?: run {
            Log.e(TAG, "setNotification: no char $charUuid")
            return false
        }

        Log.i(TAG, "setNotification: $charUuid enable=$enable props=${char.properties}")

        val lock = gattLocks.getOrPut(address) { Any() }
        synchronized(lock) {
            if (!gatt.setCharacteristicNotification(char, enable)) {
                Log.e(TAG, "setCharacteristicNotification failed")
                return false
            }

            // Write to CCCD and wait for onDescriptorWrite. Without this
            // wait, back-to-back /notify calls (or a /notify immediately
            // followed by /write) collide on Android's single-op GATT
            // queue and the second op silently drops — the peripheral
            // then never sees CCCD enable and never emits notifications.
            val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            val descriptor = char.getDescriptor(cccdUuid)
            if (descriptor == null) {
                Log.w(TAG, "No CCCD descriptor found on $charUuid")
                // No descriptor means local-only notify; Android's
                // setCharacteristicNotification is the whole story.
                return true
            }

            descriptor.value = if (enable) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }

            val latch = CountDownLatch(1)
            descriptorLatches[address] = latch
            descriptorStatus.remove(address)

            val started = gatt.writeDescriptor(descriptor)
            if (!started) {
                descriptorLatches.remove(address)
                Log.w(TAG, "writeDescriptor: kernel rejected CCCD write for $address")
                return false
            }

            val completed = try {
                latch.await(writeTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            descriptorLatches.remove(address)

            if (!completed) {
                Log.w(TAG, "writeDescriptor: timeout waiting for CCCD ack on $address")
                return false
            }

            val status = descriptorStatus.remove(address) ?: BluetoothGatt.GATT_FAILURE
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "writeDescriptor: onDescriptorWrite status=$status")
                return false
            }
            return true
        }
    }

    fun requestMtu(address: String, mtu: Int): Boolean {
        val gatt = gattClients[address] ?: return false
        return gatt.requestMtu(mtu)
    }

    // Get bonded devices
    fun getBondedDevices(): List<Map<String, String>> {
        return bluetoothAdapter?.bondedDevices?.map { device ->
            mapOf(
                "address" to device.address,
                "name" to (device.name ?: ""),
                "type" to device.type.toString()
            )
        } ?: emptyList()
    }

    // GATT Callback
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            Log.i(TAG, "Connection state change: $address status=$status state=$newState")

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedSet[address] = true
                    connectLatches.remove(address)?.countDown()
                    onConnectionStateChange?.invoke(address, true)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    gattClients.remove(address)
                    servicesCache.remove(address)
                    connectedSet.remove(address)
                    // Release any op blocked on this device so the HTTP
                    // request returns instead of hanging for its timeout.
                    connectLatches.remove(address)?.countDown()
                    writeLatches.remove(address)?.countDown()
                    writeStatus.remove(address)
                    descriptorLatches.remove(address)?.countDown()
                    descriptorStatus.remove(address)
                    discoverLatches.remove(address)?.countDown()
                    discoverStatus.remove(address)
                    gattLocks.remove(address)
                    gatt.close()
                    onConnectionStateChange?.invoke(address, false)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val address = gatt.device.address
            Log.i(TAG, "Services discovered: $address status=$status")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                val services = gatt.services.map { service ->
                    ServiceInfo(
                        uuid = service.uuid.toString(),
                        characteristics = service.characteristics.map { char ->
                            CharacteristicInfo(
                                uuid = char.uuid.toString(),
                                properties = char.properties,
                                descriptors = char.descriptors.map { it.uuid.toString() }
                            )
                        }
                    )
                }
                servicesCache[address] = services
            }
            discoverStatus[address] = status
            discoverLatches[address]?.countDown()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            val address = gatt.device.address
            Log.d(TAG, "onDescriptorWrite: ${descriptor.uuid} status=$status")
            descriptorStatus[address] = status
            descriptorLatches[address]?.countDown()
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val address = gatt.device.address
                onNotification?.invoke(address, characteristic.uuid.toString(), characteristic.value)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val address = gatt.device.address
            writeStatus[address] = status
            writeLatches[address]?.countDown()
        }

        // Deprecated callback for Android < 13
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val address = gatt.device.address
            Log.d(TAG, "onCharacteristicChanged (deprecated): ${characteristic.uuid}")
            onNotification?.invoke(address, characteristic.uuid.toString(), characteristic.value)
        }

        // New callback for Android 13+
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val address = gatt.device.address
            Log.d(TAG, "onCharacteristicChanged: ${characteristic.uuid} len=${value.size}")
            onNotification?.invoke(address, characteristic.uuid.toString(), value)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU changed: ${gatt.device.address} mtu=$mtu status=$status")
        }
    }
}
