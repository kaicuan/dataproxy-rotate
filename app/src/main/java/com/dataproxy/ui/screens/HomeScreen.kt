package com.dataproxy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dataproxy.network.CellularNetworkProvider
import com.dataproxy.network.CellularTechMonitor
import com.dataproxy.service.ProxyService
import com.dataproxy.ui.components.PowerButton
import com.dataproxy.ui.components.PowerState
import com.dataproxy.ui.components.ScreenFrame
import com.dataproxy.ui.components.ScreenScrollColumn
import com.dataproxy.ui.components.SpeedometerCard
import com.dataproxy.ui.components.StatusDot
import com.dataproxy.ui.components.TrafficStatsCard
import com.dataproxy.ui.theme.Accent
import com.dataproxy.ui.theme.Danger
import com.dataproxy.ui.theme.OutlineSoft
import com.dataproxy.ui.theme.SurfaceLow
import com.dataproxy.ui.theme.TextMuted
import com.dataproxy.ui.theme.TextPrimary
import com.dataproxy.ui.theme.TextSecondary
import com.dataproxy.ui.theme.Warning
import com.dataproxy.ui.viewmodel.MainViewModel

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onToggle: () -> Unit,
    onOpenListen: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenAuth: () -> Unit,
    onOpenSettings: () -> Unit,
    onHeaderClick: () -> Unit,
) {
    val serviceState by viewModel.serviceState.collectAsStateWithLifecycle()
    val totals by viewModel.totals.collectAsStateWithLifecycle()
    val maxConnections by viewModel.maxConnections.collectAsStateWithLifecycle()
    val rates by viewModel.rates.collectAsStateWithLifecycle()
    val rateUnit by viewModel.rateUnit.collectAsStateWithLifecycle()
    val cellular by viewModel.cellular.collectAsStateWithLifecycle()
    val cellularTech by viewModel.cellularTech.collectAsStateWithLifecycle()
    val bindAddress by viewModel.bindAddress.collectAsStateWithLifecycle()
    val port by viewModel.port.collectAsStateWithLifecycle()
    val authEnabled by viewModel.authEnabled.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val activeDeviceCount = devices.count { it.activeConnections > 0 }

    val powerState = when (serviceState) {
        is ProxyService.State.Running -> PowerState.On
        is ProxyService.State.Starting -> PowerState.Starting
        is ProxyService.State.Paused -> PowerState.Paused
        is ProxyService.State.Error -> PowerState.Error
        else -> PowerState.Off
    }
    val statusLabel = when (val s = serviceState) {
        is ProxyService.State.Running -> "Online · ${s.bindAddress}:${s.port}"
        is ProxyService.State.Starting -> "Starting on ${s.bindAddress}:${s.port}"
        is ProxyService.State.Paused -> "Paused · ${s.bindAddress}:${s.port}"
        is ProxyService.State.Error -> s.message.take(60)
        else -> "Proxy offline"
    }

    ScreenFrame(header = { Header(onOpenSettings = onOpenSettings) }) {
        ScreenScrollColumn(verticalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    NetworkStatusChip(
                        cellular = cellular,
                        tech = cellularTech,
                        onClick = onHeaderClick,
                    )
                }
                Spacer(Modifier.height(10.dp))
                PowerButton(
                    state = powerState,
                    statusLabel = statusLabel,
                    onClick = onToggle,
                )
                Spacer(Modifier.height(14.dp))
                SpeedometerCard(
                    upBps = rates.upBps,
                    downBps = rates.downBps,
                    rateUnit = rateUnit,
                    onCycleRateUnit = { viewModel.cycleRateUnit() },
                )
                Spacer(Modifier.height(10.dp))
                TrafficStatsCard(
                    bytesUp = totals.bytesUp,
                    bytesDown = totals.bytesDown,
                    activeConnections = totals.active,
                    maxConnections = maxConnections,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NavTile(
                        icon = Icons.Rounded.Router,
                        title = "Listen",
                        subtitle = "$bindAddress:$port",
                        onClick = onOpenListen,
                        modifier = Modifier.weight(1f),
                    )
                    NavTile(
                        icon = Icons.Rounded.Devices,
                        title = "Devices",
                        subtitle = when {
                            devices.isEmpty() -> "none"
                            activeDeviceCount == 0 -> "${devices.size} seen"
                            activeDeviceCount == devices.size -> "$activeDeviceCount online"
                            else -> "$activeDeviceCount / ${devices.size} online"
                        },
                        onClick = onOpenDevices,
                        modifier = Modifier.weight(1f),
                    )
                    NavTile(
                        icon = Icons.Rounded.Lock,
                        title = "Auth",
                        subtitle = if (authEnabled) "required" else "disabled",
                        onClick = onOpenAuth,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            Footer()
        }
    }
}

@Composable
private fun Header(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(listOf(Accent, Color(0xFF1E9C70)))
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Bolt,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = "DataProxy",
            color = TextPrimary,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
            ),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = "Settings",
                tint = TextSecondary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun NetworkStatusChip(
    cellular: CellularNetworkProvider.State,
    tech: CellularTechMonitor.TechState,
    onClick: () -> Unit,
) {
    val isOff = tech is CellularTechMonitor.TechState.DataOff
    val dotColor = when (tech) {
        is CellularTechMonitor.TechState.Tech -> Accent
        is CellularTechMonitor.TechState.OperatorOnly -> Accent
        CellularTechMonitor.TechState.DataOff -> Danger
        CellularTechMonitor.TechState.Unknown -> when (cellular) {
            is CellularNetworkProvider.State.Available -> Accent
            CellularNetworkProvider.State.Requesting -> Warning
            else -> TextMuted
        }
    }
    val label = when (tech) {
        is CellularTechMonitor.TechState.Tech ->
            if (tech.operator.isBlank()) tech.label
            else "${tech.operator} · ${tech.label}"
        is CellularTechMonitor.TechState.OperatorOnly -> tech.operator
        CellularTechMonitor.TechState.DataOff -> "OFF"
        CellularTechMonitor.TechState.Unknown -> when (cellular) {
            is CellularNetworkProvider.State.Available -> "MOBILE"
            CellularNetworkProvider.State.Requesting -> "WAIT"
            else -> "-"
        }
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(SurfaceLow)
            .border(1.dp, OutlineSoft, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color = dotColor)
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = if (isOff) Danger else TextSecondary,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun NavTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceLow)
            .border(1.dp, OutlineSoft, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = title,
            color = TextPrimary,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = subtitle,
            color = TextSecondary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Composable
private fun Footer() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "DataProxy",
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(3.dp)
                    .clip(CircleShape)
                    .background(TextMuted),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "v1.4",
                color = TextMuted,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = "By Sir.MmD",
            color = TextMuted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
