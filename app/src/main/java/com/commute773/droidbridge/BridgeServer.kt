package com.commute773.droidbridge

import android.bluetooth.BluetoothGattCharacteristic
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class BridgeServer(
    private val bleManager: BleManager,
    port: Int = 8765
) : NanoWSD(port) {

    companion object {
        private const val TAG = "BridgeServer"
    }

    private val gson = Gson()
    private val webSockets = ConcurrentHashMap<String, WebSocket>()

    init {
        // Forward BLE events to WebSocket clients
        bleManager.onNotification = { address, charUuid, data ->
            broadcastEvent("notification", mapOf(
                "address" to address,
                "characteristic" to charUuid,
                "data" to data.toHexString()
            ))
        }

        bleManager.onConnectionStateChange = { address, connected ->
            broadcastEvent("connection", mapOf(
                "address" to address,
                "connected" to connected
            ))
        }

        bleManager.onScanResult = { result ->
            broadcastEvent("scan", mapOf(
                "address" to result.address,
                "name" to result.name,
                "rssi" to result.rssi,
                "manufacturerData" to result.manufacturerData?.mapValues { it.value.toHexString() }
            ))
        }
    }

    private fun broadcastEvent(type: String, data: Any) {
        val message = gson.toJson(mapOf("type" to type, "data" to data))
        webSockets.values.forEach { ws ->
            try {
                ws.send(message)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending to WebSocket", e)
            }
        }
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        return BridgeWebSocket(handshake)
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Log.i(TAG, "$method $uri")

        // Handle WebSocket upgrade
        if (isWebsocketRequested(session)) {
            return super.serve(session)
        }

        // REST API
        return try {
            when {
                // GET endpoints
                method == Method.GET && uri == "/status" -> jsonResponse(mapOf(
                    "ok" to true,
                    "connections" to bleManager.getConnectedDevices()
                ))

                method == Method.GET && uri == "/bonded" -> jsonResponse(mapOf(
                    "devices" to bleManager.getBondedDevices()
                ))

                method == Method.GET && uri == "/connections" -> jsonResponse(mapOf(
                    "devices" to bleManager.getConnectedDevices()
                ))

                method == Method.GET && uri.startsWith("/services/") -> {
                    val address = uri.removePrefix("/services/")
                    val services = bleManager.getServices(address)
                    if (services != null) {
                        jsonResponse(mapOf("services" to services))
                    } else {
                        errorResponse(404, "No services found or not connected")
                    }
                }

                // POST endpoints
                method == Method.POST && uri == "/scan/start" -> {
                    val body = parseBody(session)
                    val filters = body?.get("filters")?.let {
                        gson.fromJson(it, Array<String>::class.java).toList()
                    }
                    if (bleManager.startScan(filters)) {
                        jsonResponse(mapOf("ok" to true))
                    } else {
                        errorResponse(500, "Failed to start scan")
                    }
                }

                method == Method.POST && uri == "/scan/stop" -> {
                    bleManager.stopScan()
                    jsonResponse(mapOf("ok" to true))
                }

                method == Method.POST && uri == "/connect" -> {
                    val body = parseBody(session) ?: return errorResponse(400, "Missing body")
                    val address = body["address"] ?: return errorResponse(400, "Missing address")
                    if (bleManager.connect(address)) {
                        jsonResponse(mapOf("ok" to true))
                    } else {
                        errorResponse(500, "Failed to connect")
                    }
                }

                method == Method.POST && uri == "/disconnect" -> {
                    val body = parseBody(session) ?: return errorResponse(400, "Missing body")
                    val address = body["address"] ?: return errorResponse(400, "Missing address")
                    if (bleManager.disconnect(address)) {
                        jsonResponse(mapOf("ok" to true))
                    } else {
                        errorResponse(500, "Failed to disconnect")
                    }
                }

                method == Method.POST && uri == "/discover" -> {
                    val body = parseBody(session) ?: return errorResponse(400, "Missing body")
                    val address = body["address"] ?: return errorResponse(400, "Missing address")
                    if (bleManager.discoverServices(address)) {
                        jsonResponse(mapOf("ok" to true, "message" to "Discovery started"))
                    } else {
                        errorResponse(500, "Failed to discover services")
                    }
                }

                method == Method.POST && uri == "/write" -> {
                    val body = parseBody(session) ?: return errorResponse(400, "Missing body")
                    val address = body["address"] ?: return errorResponse(400, "Missing address")
                    val service = body["service"] ?: return errorResponse(400, "Missing service")
                    val characteristic = body["characteristic"] ?: return errorResponse(400, "Missing characteristic")
                    val dataHex = body["data"] ?: return errorResponse(400, "Missing data")
                    val writeType = body["writeType"]?.toIntOrNull()
                        ?: BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

                    val data = dataHex.hexToByteArray()
                    if (bleManager.writeCharacteristic(address, service, characteristic, data, writeType)) {
                        jsonResponse(mapOf("ok" to true))
                    } else {
                        errorResponse(500, "Failed to write")
                    }
                }

                method == Method.POST && uri == "/read" -> {
                    val body = parseBody(session) ?: return errorResponse(400, "Missing body")
                    val address = body["address"] ?: return errorResponse(400, "Missing address")
                    val service = body["service"] ?: return errorResponse(400, "Missing service")
                    val characteristic = body["characteristic"] ?: return errorResponse(400, "Missing characteristic")

                    if (bleManager.readCharacteristic(address, service, characteristic)) {
                        jsonResponse(mapOf("ok" to true, "message" to "Read requested, response via WebSocket"))
                    } else {
                        errorResponse(500, "Failed to read")
                    }
                }

                method == Method.POST && uri == "/notify" -> {
                    val body = parseBody(session) ?: return errorResponse(400, "Missing body")
                    val address = body["address"] ?: return errorResponse(400, "Missing address")
                    val service = body["service"] ?: return errorResponse(400, "Missing service")
                    val characteristic = body["characteristic"] ?: return errorResponse(400, "Missing characteristic")
                    val enable = body["enable"]?.toBoolean() ?: true

                    if (bleManager.setNotification(address, service, characteristic, enable)) {
                        jsonResponse(mapOf("ok" to true))
                    } else {
                        errorResponse(500, "Failed to set notification")
                    }
                }

                method == Method.POST && uri == "/mtu" -> {
                    val body = parseBody(session) ?: return errorResponse(400, "Missing body")
                    val address = body["address"] ?: return errorResponse(400, "Missing address")
                    val mtu = body["mtu"]?.toIntOrNull() ?: return errorResponse(400, "Missing/invalid mtu")

                    if (bleManager.requestMtu(address, mtu)) {
                        jsonResponse(mapOf("ok" to true))
                    } else {
                        errorResponse(500, "Failed to request MTU")
                    }
                }

                else -> errorResponse(404, "Not found: $method $uri")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling request", e)
            errorResponse(500, e.message ?: "Unknown error")
        }
    }

    private fun parseBody(session: IHTTPSession): Map<String, String>? {
        val files = HashMap<String, String>()
        try {
            session.parseBody(files)
        } catch (e: Exception) {
            return null
        }

        val body = files["postData"] ?: return null
        return try {
            val json = JsonParser.parseString(body).asJsonObject
            json.entrySet().associate { it.key to it.value.asString }
        } catch (e: Exception) {
            null
        }
    }

    private fun jsonResponse(data: Any): Response {
        val json = gson.toJson(data)
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    private fun errorResponse(code: Int, message: String): Response {
        val status = when (code) {
            400 -> Response.Status.BAD_REQUEST
            404 -> Response.Status.NOT_FOUND
            else -> Response.Status.INTERNAL_ERROR
        }
        return newFixedLengthResponse(status, "application/json", gson.toJson(mapOf("error" to message)))
    }

    inner class BridgeWebSocket(handshake: IHTTPSession) : WebSocket(handshake) {
        private val id = System.currentTimeMillis().toString()

        override fun onOpen() {
            Log.i(TAG, "WebSocket opened: $id")
            webSockets[id] = this
            send(gson.toJson(mapOf("type" to "connected", "id" to id)))
        }

        override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            Log.i(TAG, "WebSocket closed: $id")
            webSockets.remove(id)
        }

        override fun onMessage(message: NanoWSD.WebSocketFrame) {
            // Handle incoming WebSocket messages (if needed)
            Log.d(TAG, "WebSocket message: ${message.textPayload}")
        }

        override fun onPong(pong: NanoWSD.WebSocketFrame?) {}
        override fun onException(exception: IOException) {
            Log.e(TAG, "WebSocket error", exception)
            webSockets.remove(id)
        }
    }
}

// Extension functions
fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

fun String.hexToByteArray(): ByteArray {
    val len = length
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
        data[i / 2] = ((Character.digit(this[i], 16) shl 4) + Character.digit(this[i + 1], 16)).toByte()
        i += 2
    }
    return data
}
