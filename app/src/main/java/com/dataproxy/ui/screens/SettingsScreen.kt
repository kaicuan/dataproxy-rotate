package com.dataproxy.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.SettingsBrightness
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dataproxy.rotation.HelperStatus
import com.dataproxy.rotation.SetupState
import com.dataproxy.ui.components.ScreenFrame
import com.dataproxy.ui.components.ScreenScrollColumn
import com.dataproxy.ui.components.SectionLabel
import com.dataproxy.ui.components.TopBar
import com.dataproxy.ui.theme.Accent
import com.dataproxy.ui.theme.OutlineSoft
import com.dataproxy.ui.theme.SurfaceLow
import com.dataproxy.ui.theme.Spacing
import com.dataproxy.ui.theme.SurfaceMid
import com.dataproxy.ui.theme.TextMuted
import com.dataproxy.ui.theme.TextPrimary
import com.dataproxy.ui.theme.TextSecondary
import com.dataproxy.ui.theme.ThemeMode
import com.dataproxy.ui.viewmodel.MainViewModel

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    themeMode: ThemeMode,
    onSetThemeMode: (ThemeMode) -> Unit,
    onOpenListen: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenAuth: () -> Unit,
    onOpenAntiKill: () -> Unit,
    onOpenRotation: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val bindAddress by viewModel.bindAddress.collectAsStateWithLifecycle()
    val port by viewModel.port.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val authEnabled by viewModel.authEnabled.collectAsStateWithLifecycle()
    val rotationEnabled by viewModel.rotationEnabled.collectAsStateWithLifecycle()
    val rotationHelper by viewModel.rotationHelperStatus.collectAsStateWithLifecycle()
    val rotationSetup by viewModel.rotationSetupState.collectAsStateWithLifecycle()
    val activeDeviceCount = devices.count { it.activeConnections > 0 }

    val deviceSummary = when {
        devices.isEmpty() -> "none"
        activeDeviceCount == 0 -> "${devices.size} seen"
        activeDeviceCount == devices.size -> "$activeDeviceCount online"
        else -> "$activeDeviceCount / ${devices.size} online"
    }

    val rotationSummary = when {
        !rotationEnabled -> "Off"
        rotationSetup is SetupState.Pairing || rotationSetup is SetupState.Connecting -> "On, checking"
        rotationHelper is HelperStatus.NeedsSetup -> "On, needs setup"
        rotationHelper is HelperStatus.Checking -> "On, checking"
        else -> "On"
    }

    ScreenFrame(header = { TopBar(title = "Settings", onBack = onBack) }) {
        ScreenScrollColumn {
            SettingsRow(
                icon = Icons.Rounded.Shield,
                title = "Anti-Kill",
                subtitle = "Battery, auto-launch, background",
                onClick = onOpenAntiKill,
            )

            Spacer(Modifier.height(Spacing.sectionGap))
            SectionLabel("Proxy")
            Spacer(Modifier.height(Spacing.itemGap))
            SettingsRow(
                icon = Icons.Rounded.Router,
                title = "Listen",
                subtitle = "$bindAddress:$port",
                onClick = onOpenListen,
            )
            Spacer(Modifier.height(Spacing.itemGap))
            SettingsRow(
                icon = Icons.Rounded.Devices,
                title = "Devices",
                subtitle = deviceSummary,
                onClick = onOpenDevices,
            )
            Spacer(Modifier.height(Spacing.itemGap))
            SettingsRow(
                icon = Icons.Rounded.Lock,
                title = "Auth",
                subtitle = if (authEnabled) "required" else "disabled",
                onClick = onOpenAuth,
            )
            Spacer(Modifier.height(Spacing.itemGap))
            SettingsRow(
                icon = Icons.Rounded.Public,
                title = "Rotation",
                subtitle = rotationSummary,
                onClick = onOpenRotation,
            )

            Spacer(Modifier.height(Spacing.sectionGap))
            SectionLabel("Appearance")
            Spacer(Modifier.height(Spacing.itemGap))
            ThemeCard(selected = themeMode, onSelect = onSetThemeMode)
        }
    }
}

@Composable
private fun ThemeCard(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceLow)
            .border(1.dp, OutlineSoft, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = selected.icon(),
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Theme",
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Text(
                    text = "Follow the system, or force light or dark.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceMid)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ThemeMode.entries.forEach { mode ->
                val isSelected = mode == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (isSelected) Accent else Color.Transparent)
                        .clickable { onSelect(mode) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = mode.label(),
                        color = if (isSelected) Color.Black else TextSecondary,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceLow)
            .border(1.dp, OutlineSoft, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Text(
                text = subtitle,
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(18.dp),
        )
    }
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.System -> "System"
    ThemeMode.Light -> "Light"
    ThemeMode.Dark -> "Dark"
}

private fun ThemeMode.icon(): ImageVector = when (this) {
    ThemeMode.System -> Icons.Rounded.SettingsBrightness
    ThemeMode.Light -> Icons.Rounded.LightMode
    ThemeMode.Dark -> Icons.Rounded.DarkMode
}
