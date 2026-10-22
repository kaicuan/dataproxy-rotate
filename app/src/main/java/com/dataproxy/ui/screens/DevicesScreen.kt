package com.dataproxy.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dataproxy.proxy.ConnectionRegistry
import com.dataproxy.ui.components.NumberFieldWithSave
import com.dataproxy.ui.components.ScreenFrame
import com.dataproxy.ui.components.ScreenLazyColumn
import com.dataproxy.ui.components.SectionLabel
import com.dataproxy.ui.components.StatusDot
import com.dataproxy.ui.components.TopBar
import com.dataproxy.ui.theme.Accent
import com.dataproxy.ui.theme.OutlineSoft
import com.dataproxy.ui.theme.Spacing
import com.dataproxy.ui.theme.SurfaceLow
import com.dataproxy.ui.theme.SurfaceMid
import com.dataproxy.ui.theme.TextMuted
import com.dataproxy.ui.theme.TextPrimary
import com.dataproxy.ui.theme.TextSecondary
import com.dataproxy.ui.viewmodel.MainViewModel
import com.dataproxy.util.ByteFormatter

@Composable
fun DevicesScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val maxConnections by viewModel.maxConnections.collectAsStateWithLifecycle()
    var maxText by remember { mutableStateOf(maxConnections.toString()) }
    LaunchedEffect(maxConnections) { maxText = maxConnections.toString() }
    val parsedMax = if (maxText.isBlank()) 0 else maxText.toIntOrNull()
    val maxValid = parsedMax != null && parsedMax in 0..MainViewModel.MAX_CONNECTIONS_LIMIT
    val canSaveMax = maxValid && parsedMax != maxConnections

    ScreenFrame(header = { TopBar(title = "Devices", onBack = onBack) }) {
        ScreenLazyColumn {
            item {
                NumberFieldWithSave(
                    label = "Max connections",
                    value = maxText,
                    onValueChange = { text -> maxText = text.filter { it.isDigit() }.take(4) },
                    onSave = {
                        parsedMax?.let {
                            viewModel.setMaxConnections(it)
                            maxText = it.toString()
                        }
                    },
                    saveEnabled = canSaveMax,
                    isError = !maxValid,
                    supportingText = if (maxValid) {
                        "0 or blank means unlimited."
                    } else {
                        "Enter a value from 0 to ${MainViewModel.MAX_CONNECTIONS_LIMIT}"
                    },
                )
            }
            item { Spacer(Modifier.height(Spacing.sectionGap)) }
            item { SectionLabel("Connected devices ${devices.size}") }
            item { Spacer(Modifier.height(Spacing.itemGap)) }
            if (devices.isEmpty()) {
                item { EmptyState() }
            } else {
                itemsIndexed(devices, key = { _, d -> d.clientHost }) { index, d ->
                    Column {
                        DeviceRow(d)
                        if (index < devices.lastIndex) {
                            Spacer(Modifier.height(Spacing.itemGap))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.Devices,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "No clients connected yet",
            color = TextSecondary,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Point a device's SOCKS5 settings here\nand it'll appear in this list.",
            color = TextMuted,
            style = MaterialTheme.typography.labelMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun DeviceRow(device: ConnectionRegistry.DeviceSummary) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceMid)
            .border(1.dp, OutlineSoft, RoundedCornerShape(14.dp))
            .heightIn(min = 64.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(SurfaceLow),
            contentAlignment = Alignment.Center,
        ) {
            StatusDot(
                color = if (device.activeConnections > 0) Accent else TextMuted,
                size = 10,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.clientHost,
                color = TextPrimary,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                ),
            )
            Spacer(Modifier.height(2.dp))
            val state = if (device.activeConnections > 0) "online" else "idle"
            Text(
                text = "$state · ↑${ByteFormatter.bytes(device.bytesUp)} " +
                    "↓${ByteFormatter.bytes(device.bytesDown)}",
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = ByteFormatter.elapsed(device.firstSeenMs),
            color = TextMuted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

