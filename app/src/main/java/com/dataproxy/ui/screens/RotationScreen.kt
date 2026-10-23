package com.dataproxy.ui.screens

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dataproxy.rotation.HelperStatus
import com.dataproxy.rotation.IpCheckServer
import com.dataproxy.rotation.IpCheckState
import com.dataproxy.rotation.RotationStatus
import com.dataproxy.rotation.SetupState
import com.dataproxy.ui.components.HintBanner
import com.dataproxy.ui.components.HintSeverity
import com.dataproxy.ui.components.NumberFieldWithSave
import com.dataproxy.ui.components.ScreenFrame
import com.dataproxy.ui.components.ScreenScrollColumn
import com.dataproxy.ui.components.SectionLabel
import com.dataproxy.ui.components.TopBar
import com.dataproxy.ui.theme.Accent
import com.dataproxy.ui.theme.Danger
import com.dataproxy.ui.theme.OutlineSoft
import com.dataproxy.ui.theme.OutlineStrong
import com.dataproxy.ui.theme.Spacing
import com.dataproxy.ui.theme.SurfaceLow
import com.dataproxy.ui.theme.SurfaceMid
import com.dataproxy.ui.theme.TextMuted
import com.dataproxy.ui.theme.TextPrimary
import com.dataproxy.ui.theme.TextSecondary
import com.dataproxy.ui.theme.Warning
import com.dataproxy.ui.viewmodel.MainViewModel

@Composable
fun RotationScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val enabled by viewModel.rotationEnabled.collectAsStateWithLifecycle()
    val delayMs by viewModel.rotationDelayMs.collectAsStateWithLifecycle()
    val keepWifiOn by viewModel.rotationKeepWifiOn.collectAsStateWithLifecycle()
    val server by viewModel.rotationServer.collectAsStateWithLifecycle()
    val customHost by viewModel.rotationCustomHost.collectAsStateWithLifecycle()
    val customPort by viewModel.rotationCustomPort.collectAsStateWithLifecycle()
    val helperStatus by viewModel.rotationHelperStatus.collectAsStateWithLifecycle()
    val rotationStatus by viewModel.rotationStatus.collectAsStateWithLifecycle()
    val setupState by viewModel.rotationSetupState.collectAsStateWithLifecycle()
    val ipCheckState by viewModel.rotationIpCheckState.collectAsStateWithLifecycle()

    var showSetupDialog by remember { mutableStateOf(false) }
    var showReconnectDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.checkRotationStatus() }
    LaunchedEffect(setupState) {
        if (setupState is SetupState.Done) viewModel.checkRotationStatus()
    }

    ScreenFrame(header = { TopBar(title = "IP rotation", onBack = onBack) }) {
        ScreenScrollColumn {
            RotationToggle(
                enabled = enabled,
                onToggle = viewModel::setRotationEnabled,
            )

            if (enabled) {
                Spacer(Modifier.height(Spacing.itemGap))
                HelperStateCard(
                    status = helperStatus,
                    rotationRunning = rotationStatus is RotationStatus.Running,
                    onSetup = { showSetupDialog = true },
                    onReconnect = { showReconnectDialog = true },
                    onTest = { viewModel.requestRotationTest() },
                )

                if (setupState is SetupState.Pairing) {
                    Spacer(Modifier.height(Spacing.itemGap))
                    HintBanner(
                        message = "Waiting for the pairing code. Enter it in the DataProxy notification.",
                        severity = HintSeverity.Warning,
                    )
                }
                if (setupState is SetupState.Connecting) {
                    Spacer(Modifier.height(Spacing.itemGap))
                    HintBanner(
                        message = "Pairing with Wireless debugging...",
                        severity = HintSeverity.Warning,
                    )
                }
                val setupFailure = setupState as? SetupState.Failed
                if (setupFailure != null) {
                    Spacer(Modifier.height(Spacing.itemGap))
                    HintBanner(
                        message = "Setup failed. ${setupFailure.message}",
                        severity = HintSeverity.Danger,
                    )
                }

                RotationResultCard(rotationStatus)
            }

            Spacer(Modifier.height(Spacing.sectionGap))
            SectionLabel("Timing")
            Spacer(Modifier.height(Spacing.itemGap))
            DelayField(delayMs = delayMs, onSave = viewModel::setRotationDelaySeconds)

            Spacer(Modifier.height(Spacing.itemGap))
            KeepWifiRow(enabled = keepWifiOn, onToggle = viewModel::setRotationKeepWifiOn)

            Spacer(Modifier.height(Spacing.sectionGap))
            SectionLabel("IP check")
            Spacer(Modifier.height(Spacing.itemGap))
            ServerChoice(
                selected = server,
                customHost = customHost,
                onSelect = viewModel::setRotationServer,
            )

            if (server == IpCheckServer.Custom) {
                Spacer(Modifier.height(Spacing.itemGap))
                CustomEndpoint(
                    host = customHost,
                    port = customPort,
                    onSave = viewModel::setRotationCustomEndpoint,
                )
            }

            Spacer(Modifier.height(Spacing.itemGap))
            CheckIpCard(
                state = ipCheckState,
                customIncomplete = server == IpCheckServer.Custom && customHost.isBlank(),
                onCheck = viewModel::checkRotationIp,
            )

            Spacer(Modifier.height(Spacing.itemGap))
            Text(
                text = "After each rotation, DataProxy asks this server for your public IP. " +
                    "If it didn't change, it retries up to 3 times.",
                color = TextMuted,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }

    if (showSetupDialog) {
        SetupDialog(
            onDismiss = { showSetupDialog = false },
            onContinue = { pairingPort, connectPort ->
                showSetupDialog = false
                viewModel.beginRotationSetup(pairingPort, connectPort)
            },
        )
    }

    if (showReconnectDialog) {
        ReconnectDialog(
            onDismiss = { showReconnectDialog = false },
            onReconnect = { port ->
                showReconnectDialog = false
                viewModel.reconnectRotation(port)
            },
        )
    }
}

@Composable
private fun RotationToggle(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceMid)
            .border(1.dp, OutlineSoft, RoundedCornerShape(14.dp))
            .clickable { onToggle(!enabled) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
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
                imageVector = Icons.Rounded.Autorenew,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "IP rotation",
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (enabled) {
                    "Clients can rotate the cellular IP through the SOCKS5 listener."
                } else {
                    "IP rotation and its control endpoint are off."
                },
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = SurfaceLow,
                checkedTrackColor = Accent,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = SurfaceLow,
                uncheckedBorderColor = OutlineStrong,
            ),
        )
    }
}

@Composable
private fun HelperStateCard(
    status: HelperStatus,
    rotationRunning: Boolean,
    onSetup: () -> Unit,
    onReconnect: () -> Unit,
    onTest: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceLow)
            .border(1.dp, OutlineSoft, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        when (status) {
            HelperStatus.Unknown, HelperStatus.Checking -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = Accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Checking...",
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            HelperStatus.NeedsSetup -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Needs setup",
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onSetup) {
                        Text("Set up", color = Accent, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text(
                    text = "IP rotation needs a one-time setup.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            is HelperStatus.NeedsReconnect -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Needs reconnect",
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onReconnect) {
                        Text("Reconnect", color = Accent, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text(
                    text = "IP rotation needs to reconnect.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = status.reason,
                    color = TextMuted,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            HelperStatus.Ready -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Ready",
                            color = TextPrimary,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Airplane mode control is available.",
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    TextButton(onClick = onTest, enabled = !rotationRunning) {
                        Text(
                            text = "Test",
                            color = if (rotationRunning) TextMuted else Accent,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RotationResultCard(status: RotationStatus) {
    val (title, color, detail) = when (status) {
        RotationStatus.Idle -> return
        is RotationStatus.Running -> Triple(
            "Rotating...",
            Accent,
            "Attempt ${status.attempt} of 3.",
        )
        is RotationStatus.Succeeded -> Triple(
            "Rotated",
            Accent,
            "${status.ipBefore} to ${status.ipAfter} in ${status.attempts} " +
                (if (status.attempts == 1) "attempt." else "attempts."),
        )
        is RotationStatus.Failed -> Triple(
            "Rotation failed",
            Danger,
            status.reason,
        )
        is RotationStatus.Unverified -> Triple(
            "Rotation unverified",
            Warning,
            buildString {
                append(status.reason)
                if (status.ipBefore != null && status.ipAfter != null) {
                    append(" Public IP changed from ")
                    append(status.ipBefore)
                    append(" to ")
                    append(status.ipAfter)
                    append('.')
                }
                append(" Rotation still runs but cannot be verified.")
            },
        )
    }

    Spacer(Modifier.height(Spacing.itemGap))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = title,
            color = color,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = detail,
            color = TextSecondary,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun DelayField(delayMs: Long, onSave: (Int) -> Unit) {
    val seconds = (delayMs / 1000L).toInt()
    var text by remember { mutableStateOf(seconds.toString()) }
    LaunchedEffect(seconds) { text = seconds.toString() }

    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in 1..60

    NumberFieldWithSave(
        label = "Airplane mode delay (seconds)",
        value = text,
        onValueChange = { input ->
            text = input.filter { it.isDigit() }.take(2)
        },
        onSave = { parsed?.takeIf { it in 1..60 }?.let(onSave) },
        saveEnabled = valid && parsed != seconds,
        isError = !valid,
        supportingText = if (valid) {
            "How long airplane mode stays on before mobile data returns."
        } else {
            "Enter a delay from 1 to 60 seconds."
        },
    )
}

@Composable
private fun KeepWifiRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceMid)
            .border(1.dp, OutlineSoft, RoundedCornerShape(14.dp))
            .clickable { onToggle(!enabled) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Wifi,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Keep Wi-Fi On",
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Stops airplane mode from turning Wi-Fi off.",
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = SurfaceLow,
                checkedTrackColor = Accent,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = SurfaceLow,
                uncheckedBorderColor = OutlineStrong,
            ),
        )
    }
}

@Composable
private fun ServerChoice(
    selected: IpCheckServer,
    customHost: String,
    onSelect: (IpCheckServer) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceLow)
            .border(1.dp, OutlineSoft, RoundedCornerShape(14.dp)),
    ) {
        IpCheckServer.entries.forEachIndexed { index, server ->
            val isSelected = server == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(server) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = server.label,
                        color = if (isSelected) TextPrimary else TextSecondary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = when (server) {
                            IpCheckServer.Custom ->
                                if (customHost.isBlank()) "Custom host and port" else customHost
                            else -> server.host
                        },
                        color = TextMuted,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Accent),
                    )
                }
            }
            if (index < IpCheckServer.entries.lastIndex) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(OutlineSoft),
                )
            }
        }
    }
}

@Composable
private fun CustomEndpoint(
    host: String,
    port: Int,
    onSave: (String, Int) -> Unit,
) {
    var hostText by remember { mutableStateOf(host) }
    var portText by remember { mutableStateOf(port.toString()) }
    LaunchedEffect(host, port) {
        hostText = host
        portText = port.toString()
    }

    val parsedPort = portText.toIntOrNull()
    val valid = hostText.isNotBlank() && parsedPort != null && parsedPort in 1..65535
    val dirty = hostText.trim() != host || parsedPort != port

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceMid)
            .border(1.dp, OutlineSoft, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        OutlinedTextField(
            value = hostText,
            onValueChange = { hostText = it },
            label = { Text("Host") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
                color = TextPrimary,
            ),
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.itemGap))
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.itemGap),
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = portText,
                onValueChange = { input -> portText = input.filter { it.isDigit() }.take(5) },
                label = { Text("Port") },
                singleLine = true,
                isError = parsedPort == null || parsedPort !in 1..65535,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    color = TextPrimary,
                ),
                colors = fieldColors(),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val resolvedPort = parsedPort ?: return@Button
                    onSave(hostText.trim(), resolvedPort)
                },
                enabled = valid && dirty,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Color.Black,
                ),
                modifier = Modifier.height(Spacing.controlHeight),
            ) {
                Text("Save", fontWeight = FontWeight.SemiBold)
            }
        }
        if (!valid) {
            Spacer(Modifier.height(Spacing.itemGap))
            Text(
                text = "Enter a host and a port from 1 to 65535.",
                color = Danger,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun CheckIpCard(
    state: IpCheckState,
    customIncomplete: Boolean,
    onCheck: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceLow)
            .border(1.dp, OutlineSoft, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Public,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Public IP",
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onCheck,
                enabled = state !is IpCheckState.Checking && !customIncomplete,
            ) {
                Text("Check my IP", color = Accent, fontWeight = FontWeight.SemiBold)
            }
        }
        when (state) {
            IpCheckState.Idle -> Unit
            IpCheckState.Checking -> Text(
                text = "Checking...",
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
            is IpCheckState.Success -> Text(
                text = "Your public IP is ${state.ip}.",
                color = Accent,
                style = MaterialTheme.typography.labelMedium,
            )
            is IpCheckState.Failure -> Text(
                text = "Could not check your IP. ${state.message}",
                color = Danger,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun SetupDialog(
    onDismiss: () -> Unit,
    onContinue: (Int?, Int?) -> Unit,
) {
    val context = LocalContext.current
    var pairingPortText by remember { mutableStateOf("") }
    var connectPortText by remember { mutableStateOf("") }
    val pairingPort = pairingPortText.toIntOrNull()?.takeIf { it in 1..65535 }
    val connectPort = connectPortText.toIntOrNull()?.takeIf { it in 1..65535 }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceLow,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text("Set up IP rotation", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "Android only allows an app to turn airplane mode on and off " +
                        "through Wireless debugging. DataProxy needs a one-time pairing.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "1. Open Developer options and turn on Wireless debugging.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "2. Tap Pair device with pairing code. A 6-digit code appears.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "3. Tap Show notification, then enter the code in the DataProxy notification.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                ) {
                    Text("Open Developer options", color = Accent, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "If the ports are not found automatically, enter them here.",
                    color = TextMuted,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pairingPortText,
                    onValueChange = { input ->
                        pairingPortText = input.filter { it.isDigit() }.take(5)
                    },
                    label = { Text("Pairing port (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                        color = TextPrimary,
                    ),
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Spacing.itemGap))
                OutlinedTextField(
                    value = connectPortText,
                    onValueChange = { input ->
                        connectPortText = input.filter { it.isDigit() }.take(5)
                    },
                    label = { Text("Connection port (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                        color = TextPrimary,
                    ),
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onContinue(pairingPort, connectPort) }) {
                Text("Show notification", color = Accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        },
    )
}

@Composable
private fun ReconnectDialog(
    onDismiss: () -> Unit,
    onReconnect: (Int?) -> Unit,
) {
    var portText by remember { mutableStateOf("") }
    val port = portText.toIntOrNull()?.takeIf { it in 1..65535 }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceLow,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text("Reconnect IP rotation", fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(
                    text = "Make sure Wireless debugging is on. DataProxy will find the " +
                        "connection port automatically, or you can enter it here.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(Spacing.itemGap))
                OutlinedTextField(
                    value = portText,
                    onValueChange = { input ->
                        portText = input.filter { it.isDigit() }.take(5)
                    },
                    label = { Text("Connection port (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                        color = TextPrimary,
                    ),
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onReconnect(port) }) {
                Text("Reconnect", color = Accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        },
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Accent,
    unfocusedBorderColor = OutlineStrong,
    disabledBorderColor = OutlineSoft,
    focusedLabelColor = Accent,
    unfocusedLabelColor = TextSecondary,
    disabledLabelColor = TextMuted,
    cursorColor = Accent,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    disabledTextColor = TextMuted,
)
