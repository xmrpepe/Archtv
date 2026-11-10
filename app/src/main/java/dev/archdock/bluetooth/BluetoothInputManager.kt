package dev.archdock.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import dev.archdock.core.Logs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BtInputDevice(
    val address: String,
    val name: String,
    val kind: HidDeviceKind,
    val bonded: Boolean,
    val bonding: Boolean,
    /** Android currently exposes it as an input device (it's connected and usable). */
    val connected: Boolean,
    val rssi: Int? = null,
    val lowEnergy: Boolean = false,
)

data class BtState(
    val supported: Boolean = true,
    val enabled: Boolean = false,
    val permitted: Boolean = false,
    val scanning: Boolean = false,
    val paired: List<BtInputDevice> = emptyList(),
    val nearby: List<BtInputDevice> = emptyList(),
    /** Every external keyboard/mouse Android currently sees (Bluetooth, USB, …). */
    val activeInputs: List<String> = emptyList(),
    val message: String? = null,
)

/**
 * Finds and pairs Bluetooth keyboards and mice from inside ArchDock.
 *
 * Scans both classic Bluetooth (inquiry, Class of Device = peripheral) and
 * Bluetooth LE (HID-over-GATT service 0x1812). Pairing uses the public
 * createBond(); Android shows its own PIN/passkey dialog and, once bonded,
 * its HID host connects the device. From then on its key and pointer events
 * reach InputBridge like any other hardware keyboard/mouse.
 */
@SuppressLint("MissingPermission") // every entry point checks hasPermissions()
class BluetoothInputManager(private val context: Context) {
    private val btManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = btManager?.adapter
    private val inputManager = context.getSystemService(InputManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(BtState(supported = btManager?.adapter != null))
    val state: StateFlow<BtState> = _state.asStateFlow()

    private val found = LinkedHashMap<String, BtInputDevice>()
    private var showAll = false
    private var registered = false
    private var stopScanAt: Runnable? = null

    fun hasPermissions(): Boolean = PERMISSIONS.all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    addFound(d, rssi.takeIf { it != Short.MIN_VALUE.toInt() }, advertisesHid = false, le = false)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> if (_state.value.scanning && stopScanAt == null) refresh()
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    val bond = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    val prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
                    if (d != null && bond == BluetoothDevice.BOND_BONDED) Logs.i(Logs.Channel.APP, "Bluetooth: paired ${safeName(d)}")
                    if (d != null && bond == BluetoothDevice.BOND_NONE && prev == BluetoothDevice.BOND_BONDING) {
                        message("Pairing with ${safeName(d)} failed or was cancelled. Put it in pairing mode and try again.")
                    }
                    refresh()
                }
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothAdapter.ACTION_STATE_CHANGED -> refresh()
            }
        }
    }

    private val inputListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refresh()
        override fun onInputDeviceRemoved(deviceId: Int) = refresh()
        override fun onInputDeviceChanged(deviceId: Int) = refresh()
    }

    private val leCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val hid = result.scanRecord?.serviceUuids?.any { it.uuid.toString().equals(HidDeviceKind.HID_SERVICE_UUID, true) } == true
            addFound(result.device, result.rssi, hid, le = true, advertisedName = result.scanRecord?.deviceName)
        }
        override fun onScanFailed(errorCode: Int) { Logs.w(Logs.Channel.APP, "Bluetooth LE scan failed: $errorCode") }
    }

    /** Call while the Bluetooth screen is visible. */
    fun start() {
        if (registered) { refresh(); return }
        registered = true
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        inputManager.registerInputDeviceListener(inputListener, handler)
        refresh()
    }

    fun stop() {
        stopScan()
        if (!registered) return
        registered = false
        runCatching { context.unregisterReceiver(receiver) }
        inputManager.unregisterInputDeviceListener(inputListener)
    }

    /** Scans for [seconds]; [all] also lists devices that don't look like keyboards/mice. */
    fun startScan(all: Boolean = false, seconds: Long = 30) {
        val a = adapter ?: return
        if (!hasPermissions() || !a.isEnabled) { refresh(); return }
        showAll = all
        found.clear()
        stopScanAt?.let(handler::removeCallbacks)
        if (a.isDiscovering) a.cancelDiscovery()
        val classicOk = a.startDiscovery()
        val le = a.bluetoothLeScanner
        runCatching {
            le?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), leCallback)
        }.onFailure { Logs.w(Logs.Channel.APP, "LE scan not started: ${it.message}") }
        Logs.i(Logs.Channel.APP, "Bluetooth: scanning (classic=$classicOk, le=${le != null})")
        stopScanAt = Runnable { stopScan() }.also { handler.postDelayed(it, seconds * 1000) }
        _state.value = _state.value.copy(scanning = true, message = null)
        publish()
    }

    fun stopScan() {
        stopScanAt?.let(handler::removeCallbacks)
        stopScanAt = null
        val a = adapter ?: return
        if (hasPermissions()) {
            runCatching { if (a.isDiscovering) a.cancelDiscovery() }
            runCatching { a.bluetoothLeScanner?.stopScan(leCallback) }
        }
        _state.value = _state.value.copy(scanning = false)
    }

    fun pair(address: String) {
        val a = adapter ?: return
        if (!hasPermissions()) return
        // Discovery slows bonding down considerably; stop it first.
        stopScan()
        val d = a.getRemoteDevice(address)
        val le = found[address]?.lowEnergy == true
        val started = if (le && d.type == BluetoothDevice.DEVICE_TYPE_UNKNOWN) {
            // Some LE-only devices need the transport spelled out (hidden API, reflective).
            runCatching {
                BluetoothDevice::class.java.getMethod("createBond", Int::class.javaPrimitiveType)
                    .invoke(d, BluetoothDevice.TRANSPORT_LE) as Boolean
            }.getOrElse { d.createBond() }
        } else d.createBond()
        Logs.i(Logs.Channel.APP, "Bluetooth: pairing ${safeName(d)} ($address) started=$started")
        if (!started) message("Couldn't start pairing with ${safeName(d)}. Try again, or pair it in Android's Bluetooth settings.")
        refresh()
    }

    /**
     * Unpairs a device. Android has no public API for this; the hidden
     * removeBond() works on current releases and the caller falls back to
     * the system Bluetooth settings if it doesn't.
     */
    fun forget(address: String): Boolean {
        val a = adapter ?: return false
        if (!hasPermissions()) return false
        val d = a.getRemoteDevice(address)
        val ok = runCatching { BluetoothDevice::class.java.getMethod("removeBond").invoke(d) as Boolean }.getOrDefault(false)
        Logs.i(Logs.Channel.APP, "Bluetooth: forget ${safeName(d)} ok=$ok")
        refresh()
        return ok
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
    private fun message(m: String) { _state.value = _state.value.copy(message = m) }

    private fun addFound(d: BluetoothDevice, rssi: Int?, advertisesHid: Boolean, le: Boolean, advertisedName: String? = null) {
        val name = advertisedName ?: safeName(d)
        val kind = classify(d, advertisesHid, name)
        val prev = found[d.address]
        // An LE advert can arrive before/after the classic result; keep the most informative.
        val best = if (prev != null && prev.kind.isInput && !kind.isInput) prev.kind else kind
        found[d.address] = BtInputDevice(
            address = d.address, name = name ?: prev?.name ?: d.address, kind = best,
            bonded = d.bondState == BluetoothDevice.BOND_BONDED,
            bonding = d.bondState == BluetoothDevice.BOND_BONDING,
            connected = false, rssi = rssi ?: prev?.rssi, lowEnergy = le || prev?.lowEnergy == true,
        )
        publish()
    }

    private fun classify(d: BluetoothDevice, advertisesHid: Boolean, name: String?): HidDeviceKind {
        val cls: BluetoothClass? = runCatching { d.bluetoothClass }.getOrNull()
        return HidDeviceKind.classify(cls?.majorDeviceClass ?: 0, cls?.deviceClass ?: 0, advertisesHid, name)
    }

    private fun safeName(d: BluetoothDevice): String? = runCatching { d.alias ?: d.name }.getOrNull()

    /** Names of external keyboards/mice Android currently delivers events from. */
    private fun activeInputNames(): List<String> = inputManager.inputDeviceIds.toList().mapNotNull { id ->
        val dev = inputManager.getInputDevice(id) ?: return@mapNotNull null
        val relevant = dev.isExternal && !dev.isVirtual &&
            (dev.supportsSource(InputDevice.SOURCE_KEYBOARD) && dev.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC ||
                dev.supportsSource(InputDevice.SOURCE_MOUSE) || dev.supportsSource(InputDevice.SOURCE_TOUCHPAD) ||
                dev.supportsSource(InputDevice.SOURCE_GAMEPAD))
        if (relevant) dev.name else null
    }.distinct()

    fun refresh() {
        val a = adapter
        val permitted = hasPermissions()
        val enabled = a?.isEnabled == true
        val active = activeInputNames()
        val paired = if (a != null && permitted && enabled) {
            a.bondedDevices.orEmpty().map { d ->
                val name = safeName(d) ?: d.address
                BtInputDevice(d.address, name, classify(d, false, name), bonded = true, bonding = false,
                    connected = active.any { it.equals(name, true) || it.startsWith(name, true) || name.startsWith(it, true) },
                    lowEnergy = d.type == BluetoothDevice.DEVICE_TYPE_LE)
            }.filter { it.kind.isInput }.sortedWith(compareByDescending<BtInputDevice> { it.connected }.thenBy { it.name })
        } else emptyList()
        // Devices that got paired during this scan move to the paired list.
        if (permitted && a != null) found.replaceAll { addr, dev ->
            val bond = runCatching { a.getRemoteDevice(addr).bondState }.getOrDefault(BluetoothDevice.BOND_NONE)
            dev.copy(bonded = bond == BluetoothDevice.BOND_BONDED, bonding = bond == BluetoothDevice.BOND_BONDING)
        }
        _state.value = _state.value.copy(
            supported = a != null, enabled = enabled, permitted = permitted,
            paired = paired, activeInputs = active,
            scanning = _state.value.scanning && enabled,
        )
        publish()
    }

    private fun publish() {
        val pairedAddrs = _state.value.paired.map { it.address }.toSet()
        _state.value = _state.value.copy(
            nearby = found.values.filter { it.address !in pairedAddrs && !it.bonded && (showAll || it.kind.isInput) }
                .sortedWith(compareByDescending<BtInputDevice> { it.kind.isInput }.thenByDescending { it.rssi ?: -127 }),
        )
    }

    companion object {
        val PERMISSIONS = arrayOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
        )
    }
}
