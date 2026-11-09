package dev.archdock.ui.screens

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import dev.archdock.core.CrashHandler
import dev.archdock.core.Logs
import dev.archdock.graph
import dev.archdock.ui.theme.TokyoNight
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Composable
fun LogsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val channels = Logs.Channel.entries
    var tab by remember { mutableIntStateOf(0) }
    val crashTab = channels.size

    fun export() {
        val out = File(ctx.cacheDir, "archdock-logs.zip")
        ZipOutputStream(out.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("device.txt"))
            zip.write("${Build.MANUFACTURER} ${Build.MODEL} ${Build.SOC_MODEL} Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n".toByteArray())
            zip.closeEntry()
            Logs.files().forEach { f -> zip.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
            // Guest-side session logs.
            File(g.paths.runtimeDir, "logs").listFiles()?.forEach { f ->
                zip.putNextEntry(ZipEntry("guest/${f.name}")); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", out)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Export logs"))
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        ArchTopBar("Logs", onBack) {
            TextButton(onClick = ::export) { Text("Export logs") }
            TextButton(onClick = { Logs.clear() }) { Text("Clear") }
        }
        ScrollableTabRow(selectedTabIndex = tab, containerColor = TokyoNight.bg, edgePadding = 8.dp) {
            channels.forEachIndexed { i, c -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(c.name.lowercase()) }) }
            Tab(selected = tab == crashTab, onClick = { tab = crashTab }, text = { Text("crashes") })
        }
        val lines: List<String> = if (tab < channels.size) {
            Logs.tail(channels[tab]).collectAsState().value.asReversed()
        } else {
            CrashHandler.crashFiles(g.paths.logs).flatMap { listOf("── ${it.name} ──") + it.readLines() + "" }
                .ifEmpty { listOf("No crashes recorded.") }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).background(TokyoNight.bgDarker).padding(8.dp)) {
            items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TokyoNight.fg) }
        }
    }
}
