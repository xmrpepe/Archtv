package dev.archdock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.archdock.graph
import dev.archdock.ui.theme.TokyoNight

@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val term = LocalContext.current.graph.terminal
    val lines by term.lines.collectAsState()
    val running by term.running.collectAsState()
    var input by remember { mutableStateOf("") }
    val history = remember { mutableListOf<String>() }
    val listState = rememberLazyListState()
    DisposableEffect(Unit) { term.start(); onDispose { } }
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1) }

    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        ArchTopBar("Terminal", onBack) {
            TextButton(onClick = { term.interrupt() }) { Text("Ctrl+C") }
            TextButton(onClick = { term.clear() }) { Text("Clear") }
            if (!running) TextButton(onClick = { term.start() }) { Text("New") }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().background(TokyoNight.bgDarker).padding(8.dp),
        ) {
            items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TokyoNight.fgBright) }
        }
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f), singleLine = true,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, color = TokyoNight.fgBright),
                placeholder = { Text("command", fontFamily = FontFamily.Monospace) },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    term.send(input); if (input.isNotBlank()) history += input; input = ""
                }),
            )
        }
    }
}
