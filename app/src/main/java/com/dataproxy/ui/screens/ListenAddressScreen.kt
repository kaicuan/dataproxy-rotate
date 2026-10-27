package com.dataproxy.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dataproxy.network.NetworkInterfaceLister
import com.dataproxy.service.ProxyService
import com.dataproxy.ui.components.HintBanner
import com.dataproxy.ui.components.HintSeverity
import com.dataproxy.ui.components.NumberFieldWithSave
import com.dataproxy.ui.components.Pill
import com.dataproxy.ui.components.ScreenFrame
import com.dataproxy.ui.components.ScreenLazyColumn
import com.dataproxy.ui.components.SectionLabel
import com.dataproxy.ui.components.TopBar
import com.dataproxy.ui.theme.Accent
import com.dataproxy.ui.theme.Spacing
import com.dataproxy.ui.theme.SurfaceMid
import com.dataproxy.ui.theme.TextMuted
import com.dataproxy.ui.theme.TextPrimary
import com.dataproxy.ui.theme.TextSecondary
import com.dataproxy.ui.viewmodel.MainViewModel

@Composable
fun ListenAddressScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val candidates by viewModel.interfaces.collectAsStateWithLifecycle()
    val bindAddress by viewModel.bindAddress.collectAsStateWithLifecycle()
    val port by viewModel.port.collectAsStateWithLifecycle()
    val serviceState by viewModel.serviceState.collectAsStateWithLifecycle()

    val canEdit = when (serviceState) {
        is ProxyService.State.Stopped, is ProxyService.State.Error -> true
        else -> false
    }

    var portText by remember { mutableStateOf(port.toString()) }
    LaunchedEffect(port) { portText = port.toString() }
    val parsedPort = portText.toIntOrNull()
    val portValid = parsedPort != null && parsedPort in 1..65535
    val canSavePort = canEdit && portValid && parsedPort != port

    ScreenFrame(
        header = {
            TopBar(
                title = "Listen address",
                onBack = onBack,
                action = {
                    IconButton(onClick = { viewModel.refreshInterfaces() }, enabled = canEdit) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Refresh",
                            tint = if (canEdit) TextSecondary else TextMuted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
            )
        },
    ) {
        ScreenLazyColumn {
            if (!canEdit) {
                item {
                    Column {
                        HintBanner(
                            message = "Stop the proxy to change the listen address or port.",
                            severity = HintSeverity.Warning,
                        )
                        Spacer(Modifier.height(Spacing.itemGap))
                    }
                }
            }
            item {
                NumberFieldWithSave(
                    label = "Port",
                    value = portText,
                    onValueChange = { text -> portText = text.filter { it.isDigit() }.take(5) },
                    onSave = {
                        parsedPort?.takeIf { it in 1..65535 }?.let {
                            viewModel.selectPort(it)
                            portText = it.toString()
                        }
                    },
                    saveEnabled = canSavePort,
                    enabled = canEdit,
                    isError = !portValid,
                    supportingText = if (portValid) null else "Enter a port from 1 to 65535",
                )
            }
            item { Spacer(Modifier.height(Spacing.itemGap)) }
            item {
                Text(
                    text = "Select SOCKS5 or HTTP in your client; this address accepts both. HTTP " +
                        "supports HTTPS sites through CONNECT; use SOCKS5 for UDP traffic.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item { Spacer(Modifier.height(Spacing.sectionGap)) }
            item { SectionLabel("Interface") }
            item { Spacer(Modifier.height(Spacing.itemGap)) }
            if (candidates.isEmpty()) {
                item {
                    Text(
                        text = "No interfaces enumerated",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                itemsIndexed(candidates) { index, cand ->
                    Column {
                        AddressRow(
                            candidate = cand,
                            selected = cand.address == bindAddress,
                            enabled = canEdit,
                            onClick = { if (canEdit) viewModel.selectBindAddress(cand.address) },
                        )
                        if (index < candidates.lastIndex) {
                            Spacer(Modifier.height(Spacing.itemGap))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddressRow(
    candidate: NetworkInterfaceLister.Candidate,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Accent.copy(alpha = 0.08f) else SurfaceMid)
            .border(
                width = if (selected) 1.dp else 0.dp,
                color = if (selected) Accent else Color.Transparent,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = when {
            candidate.isWildcard -> Icons.Rounded.SettingsEthernet
            candidate.isWifi -> Icons.Rounded.Wifi
            else -> Icons.Rounded.Router
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) Accent else TextSecondary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = candidate.address,
                color = TextPrimary,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                ),
            )
            Text(
                text = candidate.label,
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (selected) Pill(text = "active", color = Accent)
    }
}
