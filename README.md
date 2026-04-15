# DroidBridge

Phone can go many place, computer can only go one place, what if I want to
connect to bluetooth in place where computer isn't? Answer? Droidbridge.

## Building

Requires the Android SDK (command line tools or Android Studio) and JDK 17.
Put your SDK path in `local.properties`:

```properties
sdk.dir=/path/to/Android/sdk
```

Then:

```bash
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Installing

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Launch the app, grant Bluetooth + Location permissions, then tap **Start
Server**. The home screen shows the phone's local URL (e.g.
`http://192.168.1.100:8765`). Keep the app in the foreground — it runs as a
foreground service with a persistent notification, so Android won't kill it.

## API

Base URL: `http://<phone-ip>:8765`

All request and response bodies are JSON. Binary payloads are hex-encoded
strings (e.g. `"00056100008a03"`).

### REST

| Method | Path | Body | Description |
|--------|------|------|-------------|
| GET  | `/status` | – | Server status + connected device list |
| GET  | `/bonded` | – | Enumerate bonded (paired) devices — `[{address, name, type}]` |
| GET  | `/connections` | – | Currently-connected addresses — `{devices: [addr, ...]}` |
| GET  | `/services/<address>` | – | Discovered services + characteristics for a device (404 until `/discover` finishes) |
| POST | `/scan/start` | `{filters?: ["uuid", ...]}` | Start a low-latency BLE scan (results stream over WS) |
| POST | `/scan/stop` | – | Stop scanning |
| POST | `/connect` | `{address}` | Open a GATT connection |
| POST | `/disconnect` | `{address}` | Drop the GATT connection |
| POST | `/discover` | `{address}` | Kick off service discovery — poll `/services/<address>` until it returns a populated list |
| POST | `/write` | `{address, service, characteristic, data, writeType?}` | Write a characteristic; blocks until `onCharacteristicWrite` fires |
| POST | `/read` | `{address, service, characteristic}` | Request a read; the result arrives over WS as a `notification` event |
| POST | `/notify` | `{address, service, characteristic, enable}` | Enable/disable characteristic notifications (writes CCCD) |
| POST | `/mtu` | `{address, mtu}` | Request an ATT MTU change |

**Write types** (for `POST /write`):

| Value | Android constant | Meaning |
|-------|------------------|---------|
| `1` | `WRITE_TYPE_NO_RESPONSE` | Fire-and-forget; ack is the controller accepting the packet |
| `2` | `WRITE_TYPE_DEFAULT` | Waits for an ATT write response from the peer |
| `4` | `WRITE_TYPE_SIGNED` | Signed write (rare) |

### WebSocket

Connect to `ws://<phone-ip>:8765`. Messages are JSON objects with `type` and
`data` fields:

```json
{"type": "connected", "id": "1739830000000"}
{"type": "notification", "data": {"address": "...", "characteristic": "...", "data": "hex"}}
{"type": "connection",   "data": {"address": "...", "connected": true}}
{"type": "scan",         "data": {"address": "...", "name": "...", "rssi": -50, "manufacturerData": {...}}}
```

The WebSocket is a broadcast stream — every connected client gets every
event. If you need device-scoped filtering, filter on the client.

**Keep-alive**: NanoWSD closes idle sockets after a few seconds. Clients
should send a short text frame (e.g. `"ping"`) every 1–2 seconds to keep the
connection open.

## Example: reading battery via curl

```bash
PHONE=192.168.1.100
ADDR=DE:66:9E:50:8B:74

# Connect and discover
curl -X POST http://$PHONE:8765/connect   -H 'Content-Type: application/json' -d "{\"address\":\"$ADDR\"}"
curl -X POST http://$PHONE:8765/discover  -H 'Content-Type: application/json' -d "{\"address\":\"$ADDR\"}"

# Wait for discovery, then list services
curl http://$PHONE:8765/services/$ADDR

# Enable notifications on a characteristic
curl -X POST http://$PHONE:8765/notify -H 'Content-Type: application/json' -d '{
  "address":        "DE:66:9E:50:8B:74",
  "service":        "bae80001-4f05-4503-8e65-3af1f7329d1f",
  "characteristic": "bae80013-4f05-4503-8e65-3af1f7329d1f",
  "enable":         true
}'

# Send a command (hex-encoded bytes)
curl -X POST http://$PHONE:8765/write -H 'Content-Type: application/json' -d '{
  "address":        "DE:66:9E:50:8B:74",
  "service":        "bae80001-4f05-4503-8e65-3af1f7329d1f",
  "characteristic": "bae80012-4f05-4503-8e65-3af1f7329d1f",
  "data":           "00056100008a03",
  "writeType":      1
}'
```

Meanwhile on `ws://192.168.1.100:8765` you'll see the notification stream
from the peer.

## Architecture

```
┌─────────────┐      HTTP + WS      ┌──────────────────┐      BLE      ┌────────────┐
│ Your laptop │ ──────────────────> │ DroidBridge app  │ ────────────> │ Peripheral │
│ (any lang)  │ <────────────────── │ (foreground svc) │ <──────────── │ (ring, etc)│
└─────────────┘                     └──────────────────┘               └────────────┘
```

Source layout:

```
app/src/main/java/com/commute773/droidbridge/
├── MainActivity.kt     # start/stop button + status screen
├── BridgeService.kt    # foreground service that owns BleManager + BridgeServer
├── BridgeServer.kt     # NanoHTTPD/NanoWSD routing and event broadcast
└── BleManager.kt       # thin wrapper over BluetoothGatt with per-device write queue
```

## Used by

- [g2-kit](https://github.com/Commute773/g2-kit) — drives Even Realities G2
  smart glasses over BLE.

If you build something on top of DroidBridge, feel free to open a PR adding
it to this list.

## License

MIT. See [`LICENSE`](LICENSE).
