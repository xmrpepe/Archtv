package dev.archdock.ui.screens

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.archdock.bluetooth.BluetoothInputManager
import dev.archdock.bluetooth.BtInputDevice
import dev.archdock.bluetooth.HidDeviceKind
import dev.archdock.graph
import dev.archdock.ui.theme.TokyoNight

@Composable
fun BluetoothScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val bt = ctx.graph.bluetooth
    val st by bt.state.collectAsState()
    var showAll by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        bt.refresh()
        if (bt.hasPermissions()) bt.startScan(showAll)
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        bt.refresh()
        if (bt.state.value.enabled) bt.startScan(showAll)
    }
    fun scan() = when {
        !bt.hasPermissions() -> permissionLauncher.launch(BluetoothInputManager.PERMISSIONS)
        !st.enabled -> enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        else -> bt.startScan(showAll)
    }

    DisposableEffect(Unit) {
        bt.start()
        onDispose { bt.stop() }
    }
    // Scan straight away when everything is in place – that's why the user came here.
    LaunchedEffect(st.permitted, st.enabled) { if (st.permitted && st.enabled && st.nearby.isEmpty() && !st.scanning) bt.startScan(showAll) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        ArchTopBar("Bluetooth keyboard & mouse", onBack) {
            TextButton(onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } }) { Text("Android settings") }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).screenPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!st.supported) {
                Panel(accent = TokyoNight.red) { Text("This device has no Bluetooth adapter.", color = TokyoNight.fg) }
                return@Column
            }
            if (!st.permitted || !st.enabled) Panel(accent = TokyoNight.yellow) {
                Text(
                    if (!st.permitted) "ArchDock needs the Nearby devices permission to find and pair your keyboard and mouse."
                    else "Bluetooth is off.",
                    color = TokyoNight.fg,
                )
                Button(onClick = ::scan) { Text(if (!st.permitted) "Allow" else "Turn on Bluetooth") }
            }

            st.message?.let { m ->
                Panel(accent = TokyoNight.orange) {
                    Text(m, color = TokyoNight.fg, fontSize = 13.sp)
                    TextButton(onClick = bt::clearMessage) { Text("OK") }
                }
            }

            Panel(title = "Active input devices") {
                if (st.activeInputs.isEmpty()) Text("No external keyboard or mouse connected yet.", color = TokyoNight.fgDark, fontSize = 13.sp)
                st.activeInputs.forEach { StatusLine("Connected", it, TokyoNight.green) }
                Text("Everything listed here is forwarded to the Linux desktop – the Meta/Windows key works as Super.",
                    color = TokyoNight.fgDark, fontSize = 12.sp)
            }

            Panel(title = "Paired") {
                if (st.paired.isEmpty()) Text("No paired keyboards or mice.", color = TokyoNight.fgDark, fontSize = 13.sp)
                st.paired.forEach { d ->
                    DeviceRow(d) {
                        TextButton(onClick = {
                            if (!bt.forget(d.address)) runCatching { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                        }) { Text("Forget", color = TokyoNight.red) }
                    }
                }
                if (st.paired.any { !it.connected }) Text(
                    "Paired but not connected? Switch the device off and on, or press any key – it reconnects by itself.",
                    color = TokyoNight.fgDark, fontSize = 12.sp)
            }

            Panel(title = "Nearby") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Put your keyboard or mouse in pairing mode (usually hold the Bluetooth/connect button until the light blinks).",
                        color = TokyoNight.fg, fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (st.scanning) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = TokyoNight.accent)
                        Text("Scanning…", color = TokyoNight.accent)
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = bt::stopScan) { Text("Stop") }
                    } else {
                        Button(onClick = ::scan) { Icon(Icons.Default.Bluetooth, null); Spacer(Modifier.width(6.dp)); Text("Scan") }
                        Spacer(Modifier.weight(1f))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show all Bluetooth devices", color = TokyoNight.fg, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = showAll, onCheckedChange = { showAll = it; if (st.permitted && st.enabled) bt.startScan(it) })
                }
                if (st.nearby.isEmpty()) Text(
                    if (st.scanning) "Looking for keyboards and mice…" else "Nothing found yet.",
                    color = TokyoNight.fgDark, fontSize = 13.sp)
                st.nearby.forEach { d ->
                    DeviceRow(d) {
                        if (d.bonding) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = TokyoNight.accent)
                        else Button(onClick = { bt.pair(d.address) }) { Text("Pair") }
                    }
                }
                Text("Android shows its own pairing dialog. Keyboards may ask you to type a code on the keyboard and press Enter.",
                    color = TokyoNight.fgDark, fontSize = 12.sp)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DeviceRow(d: BtInputDevice, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            when (d.kind) {
                HidDeviceKind.KEYBOARD, HidDeviceKind.KEYBOARD_MOUSE -> Icons.Default.Keyboard
                HidDeviceKind.MOUSE -> Icons.Default.Mouse
                HidDeviceKind.GAMEPAD -> Icons.Default.Gamepad
                else -> Icons.Default.Devices
            },
            null, tint = if (d.connected) TokyoNight.green else TokyoNight.accent,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name, color = TokyoNight.fgBright, fontSize = 14.sp)
            Text(
                listOfNotNull(
                    d.kind.label,
                    if (d.lowEnergy) "Bluetooth LE" else null,
                    when { d.connected -> "connected"; d.bonding -> "pairing…"; d.bonded -> "paired"; else -> null },
                    d.rssi?.let { "$it dBm" },
                ).joinToString(" · "),
                color = if (d.connected) TokyoNight.green else TokyoNight.fgDark, fontSize = 12.sp,
            )
        }
        action()
    }
}
