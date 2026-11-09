package dev.archdock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.archdock.ui.theme.TokyoNight

/** Omarchy-style card: dark surface, thin accent border, generous padding. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    title: String? = null,
    accent: Color = TokyoNight.muted,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(TokyoNight.bgDark)
            .border(1.dp, accent, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, color = TokyoNight.accent)
        content()
    }
}

@Composable
fun StatusDot(color: Color) = Box(Modifier.size(10.dp).clip(CircleShape).background(color))

@Composable
fun StatusLine(label: String, value: String, color: Color = TokyoNight.fgBright) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color)
        Spacer(Modifier.width(10.dp))
        Text(label, color = TokyoNight.fgDark, modifier = Modifier.width(110.dp))
        Text(value, color = color)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = TokyoNight.bg, titleContentColor = TokyoNight.fgBright),
    )
}

fun Modifier.screenPadding() = this.padding(horizontal = 16.dp)
