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

    // Per-device write serialization. Android's GATT layer accepts exactly one
    // outstanding write per connection; the callback fires when the controller
    // has the packet. Without this, two concurrent /write requests race inside
    // the framework and the second one's gatt.writeCharacteristic() returns
    // false with no warning.
    private val writeLocks = ConcurrentHashMap<String, Any>()
    private val writeLatches = ConcurrentHashMap<String, CountDownLatch>()
    private val writeStatus = ConcurrentHashMap<String, Int>()
    private val writeTimeoutMs = 5_000L

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

    // Connection
    fun connect(address: String): Boolean {
        if (gattClients.containsKey(address)) {
            Log.w(TAG, "Already connected to $address")
            return true
        }

        val device = bluetoothAdapter?.getRemoteDevice(address) ?: return false

        val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        if (gatt != null) {
            gattClients[address] = gatt
            Log.i(TAG, "Connecting to $address")
            return true
        }
        return false
    }

    fun disconnect(address: String): Boolean {
        val gatt = gattClients.remove(address)
        if (gatt != null) {
            gatt.disconnect()
            gatt.close()
            servicesCache.remove(address)
            Log.i(TAG, "Disconnected from $address")
            return true
        }
        return false
    }

    fun disconnectAll() {
        gattClients.keys.toList().forEach { disconnect(it) }
    }

    fun isConnected(address: String): Boolean {
        return gattClients.containsKey(address)
    }

    fun getConnectedDevices(): List<String> {
        return gattClients.keys.toList()
    }

    // Services
    fun discoverServices(address: String): Boolean {
        val gatt = gattClients[address] ?: return false
        return gatt.discoverServices()
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

        val lock = writeLocks.getOrPut(address) { Any() }
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

        if (!gatt.setCharacteristicNotification(char, enable)) {
            Log.e(TAG, "setCharacteristicNotification failed")
            return false
        }

        // Write to CCCD
        val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val descriptor = char.getDescriptor(cccdUuid)
        if (descriptor != null) {
            Log.i(TAG, "Writing CCCD descriptor")
            descriptor.value = if (enable) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            val written = gatt.writeDescriptor(descriptor)
            Log.i(TAG, "writeDescriptor result: $written")
        } else {
            Log.w(TAG, "No CCCD descriptor found")
        }

        return true
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
                    onConnectionStateChange?.invoke(address, true)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    gattClients.remove(address)
                    servicesCache.remove(address)
                    // Release any writer blocked on this device so the HTTP
                    // request returns instead of hanging for writeTimeoutMs.
                    writeLatches.remove(address)?.countDown()
                    writeStatus.remove(address)
                    writeLocks.remove(address)
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
