package dev.archdock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.archdock.core.Logs
import dev.archdock.graph
import dev.archdock.installer.StepStatus
import dev.archdock.session.SessionService
import dev.archdock.ui.theme.TokyoNight

@Composable
fun InstallerScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val st by g.installer.state.collectAsState()
    val log by Logs.tail(Logs.Channel.INSTALL).collectAsState()
    val checks = remember { g.installer.preflightChecks() }
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.scrollToItem(log.size - 1) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        ArchTopBar("Install Arch Linux ARM", onBack = if (!st.running) onDone else null)
        Column(Modifier.screenPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Panel(accent = if (st.error != null) TokyoNight.red else TokyoNight.accent) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (st.installed) "Installed" else st.message.ifEmpty { "Ready to install (≈ 4–6 GB download over time)" },
                        color = TokyoNight.fgBright, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${(st.overall * 100).toInt()}%", color = TokyoNight.accent, fontFamily = FontFamily.Monospace)
                }
                LinearProgressIndicator(progress = { st.overall }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = TokyoNight.accent, trackColor = TokyoNight.selection)
                st.stepProgress?.let {
                    LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = TokyoNight.magenta, trackColor = TokyoNight.selection)
                }
                st.error?.let { Text(it, color = TokyoNight.red, fontSize = 13.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        st.installed -> Button(onClick = onDone) { Text("Done") }
                        st.running -> OutlinedButton(onClick = { SessionService.send(ctx, SessionService.ACTION_CANCEL_INSTALL) }) { Text("Pause") }
                        else -> Button(onClick = { SessionService.send(ctx, SessionService.ACTION_INSTALL) },
                            enabled = checks.none { !it.ok && it.fatal }) {
                            Text(if (st.steps.any { it.status == StepStatus.DONE }) "Resume install" else "Start install")
                        }
                    }
                }
            }
            if (!st.running && !st.installed) Panel(title = "Pre-flight checks") {
                checks.forEach { c ->
                    StatusLine(c.name, c.detail, if (c.ok) TokyoNight.green else if (c.fatal) TokyoNight.red else TokyoNight.yellow)
                }
            }
            Panel(title = "Steps") {
                st.steps.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when (s.status) {
                            StepStatus.DONE -> Icon(Icons.Default.CheckCircle, null, tint = TokyoNight.green, modifier = Modifier.size(18.dp))
                            StepStatus.RUNNING -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = TokyoNight.accent)
                            StepStatus.FAILED -> Icon(Icons.Default.Error, null, tint = TokyoNight.red, modifier = Modifier.size(18.dp))
                            else -> Icon(Icons.Default.RadioButtonUnchecked, null, tint = TokyoNight.muted, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(s.step.title, color = when (s.status) {
                            StepStatus.RUNNING -> TokyoNight.fgBright; StepStatus.DONE -> TokyoNight.fg
                            StepStatus.FAILED -> TokyoNight.red; else -> TokyoNight.fgDark
                        }, fontSize = 14.sp)
                    }
                }
            }
        }
        Text("Live log", color = TokyoNight.fgDark, modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp), fontSize = 12.sp)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp).padding(bottom = 12.dp)
                .clip(RoundedCornerShape(10.dp)).background(TokyoNight.bgDarker).padding(8.dp),
        ) {
            items(log) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TokyoNight.fg, maxLines = 3)
            }
        }
    }
}
