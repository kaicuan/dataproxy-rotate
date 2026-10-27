package com.dataproxy.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dataproxy.rotation.HelperStatus
import com.dataproxy.rotation.IpCheckServer
import com.dataproxy.rotation.IpCheckState
import com.dataproxy.rotation.PairingNotification
import com.dataproxy.rotation.RotationStatus
import com.dataproxy.rotation.SetupState
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
import com.dataproxy.ui.viewmodel.MainViewModel
import com.dataproxy.util.OemHelper

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
    val adbPaired by viewModel.rotationAdbPaired.collectAsStateWithLifecycle()
    val rotationStatus by viewModel.rotationStatus.collectAsStateWithLifecycle()
    val setupState by viewModel.rotationSetupState.collectAsStateWithLifecycle()
    val ipCheckState by viewModel.rotationIpCheckState.collectAsStateWithLifecycle()
    val displayHelperStatus = when (setupState) {
        SetupState.Pairing, SetupState.Connecting -> HelperStatus.Checking
        SetupState.Idle, SetupState.Done -> helperStatus
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    var previousIpCheckState by remember { mutableStateOf(ipCheckState) }
    var previousRotationStatus by remember { mutableStateOf(rotationStatus) }
    var showSetupGuide by remember { mutableStateOf(false) }
    var notificationsAllowed by remember { mutableStateOf(canPostPairingNotification(context)) }
    var notificationPermissionDenied by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsAllowed = granted && canPostPairingNotification(context)
        notificationPermissionDenied = !granted
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsAllowed = canPostPairingNotification(context)
                if (notificationsAllowed) notificationPermissionDenied = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun openSetupGuide() {
        notificationsAllowed = canPostPairingNotification(context)
        showSetupGuide = true
    }

    fun enableNotifications() {
        notificationsAllowed = canPostPairingNotification(context)
        if (notificationsAllowed) return

        val runtimePermissionMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        if (runtimePermissionMissing && !notificationPermissionDenied) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            OemHelper.openNotificationSettings(context)
        }
    }

    fun continueSetupFromGuide() {
        if (setupState is SetupState.Connecting) return

        if (adbPaired) {
            viewModel.retryRotationHelperStart()
            return
        }

        notificationsAllowed = canPostPairingNotification(context)
        if (!notificationsAllowed) return

        if (setupState !is SetupState.Pairing) {
            if (!viewModel.beginRotationSetup()) {
                notificationsAllowed = canPostPairingNotification(context)
                return
            }
        }
        openDeveloperOptions(context)
    }

    LaunchedEffect(Unit) { viewModel.checkRotationStatus() }
    LaunchedEffect(setupState) {
        if (setupState is SetupState.Done) {
            showSetupGuide = false
            viewModel.checkRotationStatus()
        }
    }
    LaunchedEffect(ipCheckState) {
        if (ipCheckState == previousIpCheckState) return@LaunchedEffect
        previousIpCheckState = ipCheckState
        when (val result = ipCheckState) {
            is IpCheckState.Success ->
                snackbarHostState.showSnackbar("Success! Your public IP is ${result.ip}.")
            is IpCheckState.Failure ->
                snackbarHostState.showSnackbar("IP check failed. Please try another server.")
            IpCheckState.Idle, IpCheckState.Checking -> Unit
        }
    }
    LaunchedEffect(rotationStatus) {
        if (rotationStatus == previousRotationStatus) return@LaunchedEffect
        previousRotationStatus = rotationStatus
        when (val result = rotationStatus) {
            is RotationStatus.Succeeded -> snackbarHostState.showSnackbar(
                "Success! Your IP changed from ${result.ipBefore} to ${result.ipAfter}.",
            )
            is RotationStatus.Failed -> snackbarHostState.showSnackbar(
                "Rotation failed. ${result.reason}",
            )
            is RotationStatus.Unverified -> snackbarHostState.showSnackbar(
                "Rotation could not be verified. ${result.reason}",
            )
            RotationStatus.Idle, is RotationStatus.Running -> Unit
        }
    }

    ScreenFrame(header = { TopBar(title = "IP rotation", onBack = onBack) }) {
        Box(modifier = Modifier.fillMaxWidth()) {
            ScreenScrollColumn {
                RotationToggle(
                    enabled = enabled,
                    status = displayHelperStatus,
                    onToggle = viewModel::setRotationEnabled,
                )

                if (enabled) {
                    Spacer(Modifier.height(Spacing.itemGap))
                    HelperStateCard(
                        status = displayHelperStatus,
                        rotationRunning = rotationStatus is RotationStatus.Running,
                        onSetup = { openSetupGuide() },
                        onTest = viewModel::requestRotationTest,
                    )
                }

                if (!enabled) {
                    Spacer(Modifier.height(Spacing.sectionGap))
                    Text(
                        text = "Turn on IP rotation to change timing and IP check settings.",
                        color = TextMuted,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (enabled) 1f else 0.45f),
                ) {
                    Spacer(Modifier.height(Spacing.sectionGap))
                    SectionLabel("Timing")
                    Spacer(Modifier.height(Spacing.itemGap))
                    Text(
                        text = "Rotation briefly turns airplane mode on. Set how long it stays on before turning it off. Some mobile providers may need more than the default one second to assign a new IP address.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(Spacing.itemGap))
                    DelayField(
                        delayMs = delayMs,
                        enabled = enabled,
                        onSave = viewModel::setRotationDelaySeconds,
                    )

                    Spacer(Modifier.height(Spacing.sectionGap))
                    SectionLabel("Wi-Fi")
                    Spacer(Modifier.height(Spacing.itemGap))
                    KeepWifiRow(
                        enabled = keepWifiOn,
                        controlsEnabled = enabled,
                        onToggle = viewModel::setRotationKeepWifiOn,
                    )

                    Spacer(Modifier.height(Spacing.sectionGap))
                    SectionLabel("IP check")
                    Spacer(Modifier.height(Spacing.itemGap))
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = "Pick the server DataProxy uses to check your public IP. It will be used to detect whether your IP was successfully rotated.",
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IpCheckTestButton(
                            state = ipCheckState,
                            enabled = enabled &&
                                (server != IpCheckServer.Custom || customHost.isNotBlank()),
                            onClick = viewModel::checkRotationIp,
                        )
                    }
                    Spacer(Modifier.height(Spacing.itemGap))
                    ServerChoice(
                        selected = server,
                        customHost = customHost,
                        customPort = customPort,
                        enabled = enabled,
                        onSelect = viewModel::setRotationServer,
                    )

                    if (server == IpCheckServer.Custom) {
                        Spacer(Modifier.height(Spacing.itemGap))
                        CustomEndpoint(
                            host = customHost,
                            port = customPort,
                            enabled = enabled,
                            onSave = viewModel::setRotationCustomEndpoint,
                        )
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (showSetupGuide) {
        SetupDialog(
            onDismiss = {
                showSetupGuide = false
                viewModel.cancelRotationSetup()
            },
            notificationsAllowed = notificationsAllowed,
            notificationPermissionMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED,
            notificationPermissionDenied = notificationPermissionDenied,
            setupState = setupState,
            adbPaired = adbPaired,
            helperStatus = helperStatus,
            onEnableNotifications = { enableNotifications() },
            onSetupAction = { continueSetupFromGuide() },
        )
    }
}

@Composable
private fun RotationToggle(
    enabled: Boolean,
    status: HelperStatus,
    onToggle: (Boolean) -> Unit,
) {
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
                text = if (!enabled) {
                    "IP rotation and its control endpoint are off."
                } else {
                    when (status) {
                        is HelperStatus.NeedsSetup ->
                            "On, but unavailable until setup is complete."
                        HelperStatus.Checking -> "Checking whether IP rotation is ready."
                        HelperStatus.Ready ->
                            "Clients can request cellular IP changes through the proxy listener."
                    }
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
    onTest: () -> Unit,
) {
    when (status) {
        HelperStatus.Checking -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(SurfaceLow)
                .border(1.dp, OutlineSoft, RoundedCornerShape(14.dp))
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = Accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Checking...",
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        is HelperStatus.NeedsSetup -> StateNoticeCard(
                title = "Setup required",
                message = "IP rotation is on, but it will not work until you finish Wireless debugging setup.",
                action = "Set up",
                tint = Danger,
                icon = Icons.Rounded.ErrorOutline,
                onAction = onSetup,
            )
        HelperStatus.Ready -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(SurfaceLow)
                .border(1.dp, OutlineSoft, RoundedCornerShape(14.dp))
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "All set",
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "IP rotation is ready to use.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                TextButton(onClick = onTest, enabled = !rotationRunning) {
                    Text(
                        text = if (rotationRunning) "Running..." else "Test rotation",
                        color = if (rotationRunning) TextMuted else Accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun StateNoticeCard(
    title: String,
    message: String,
    action: String,
    tint: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.58f), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = tint,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = message,
                    color = TextPrimary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onAction,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Accent,
                contentColor = Color.Black,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(action, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DelayField(delayMs: Long, enabled: Boolean, onSave: (Int) -> Unit) {
    val seconds = (delayMs / 1000L).toInt()
    var text by remember { mutableStateOf(seconds.toString()) }
    LaunchedEffect(seconds) { text = seconds.toString() }

    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in 1..60

    NumberFieldWithSave(
        label = "Airplane mode duration (seconds)",
        value = text,
        onValueChange = { input ->
            text = input.filter { it.isDigit() }.take(2)
        },
        onSave = { parsed?.takeIf { it in 1..60 }?.let(onSave) },
        saveEnabled = enabled && valid && parsed != seconds,
        enabled = enabled,
        isError = !valid,
        supportingText = if (valid) null else "Enter a delay from 1 to 60 seconds.",
    )
}

@Composable
private fun KeepWifiRow(
    enabled: Boolean,
    controlsEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceMid)
            .border(1.dp, OutlineSoft, RoundedCornerShape(14.dp))
            .clickable(enabled = controlsEnabled) { onToggle(!enabled) }
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
            enabled = controlsEnabled,
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
    customPort: Int,
    enabled: Boolean,
    onSelect: (IpCheckServer) -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SurfaceLow)
            .border(1.dp, OutlineSoft, shape),
    ) {
        IpCheckServer.entries.forEachIndexed { index, server ->
            ServerOption(
                server = server,
                selected = server == selected,
                customHost = customHost,
                customPort = customPort,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                onSelect = onSelect,
            )
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
private fun ServerOption(
    server: IpCheckServer,
    selected: Boolean,
    customHost: String,
    customPort: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (IpCheckServer) -> Unit,
) {
    val endpoint = when (server) {
        IpCheckServer.Custom -> if (customHost.isBlank()) "Custom host and port" else "$customHost:$customPort"
        else -> server.host
    }

    Row(
        modifier = modifier
            .background(if (selected) Accent.copy(alpha = 0.08f) else SurfaceLow)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = { onSelect(server) },
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.label,
                color = TextPrimary,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = endpoint,
                color = TextMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
        )
    }
}

@Composable
private fun CustomEndpoint(
    host: String,
    port: Int,
    enabled: Boolean,
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
            enabled = enabled,
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
        OutlinedTextField(
            value = portText,
            onValueChange = { input -> portText = input.filter { it.isDigit() }.take(5) },
            label = { Text("Port") },
            singleLine = true,
            enabled = enabled,
            isError = parsedPort != null && parsedPort !in 1..65535,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
                color = TextPrimary,
            ),
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (hostText.isNotBlank() && !valid) {
            Spacer(Modifier.height(Spacing.itemGap))
            Text(
                text = "Enter a host and a port from 1 to 65535.",
                color = Danger,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.height(Spacing.itemGap))
        Button(
            onClick = {
                val resolvedPort = parsedPort ?: return@Button
                onSave(hostText.trim(), resolvedPort)
            },
            enabled = enabled && valid && dirty,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Accent,
                contentColor = Color.Black,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun IpCheckTestButton(
    state: IpCheckState,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled && state !is IpCheckState.Checking,
        modifier = Modifier.semantics {
            contentDescription = if (state is IpCheckState.Checking) {
                "Checking your public IP"
            } else {
                "Test IP check server"
            }
        },
    ) {
        if (state is IpCheckState.Checking) {
            CircularProgressIndicator(
                color = Accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )
        } else {
            Text(
                text = "Test",
                color = if (enabled) Accent else TextMuted,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SetupDialog(
    onDismiss: () -> Unit,
    notificationsAllowed: Boolean,
    notificationPermissionMissing: Boolean,
    notificationPermissionDenied: Boolean,
    setupState: SetupState,
    adbPaired: Boolean,
    helperStatus: HelperStatus,
    onEnableNotifications: () -> Unit,
    onSetupAction: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceLow,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text("Set up IP rotation", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                if (!adbPaired) {
                    Row(verticalAlignment = Alignment.Top) {
                        SetupStepMarker(number = 1, completed = notificationsAllowed)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Notifications",
                                color = TextPrimary,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Notifications let you enter the pairing code without leaving Settings.",
                                color = TextSecondary,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            if (notificationsAllowed) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 4.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = Accent,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Granted",
                                        color = Accent,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            } else {
                                val openSettings =
                                    !notificationPermissionMissing || notificationPermissionDenied
                                Spacer(Modifier.height(4.dp))
                                TextButton(onClick = onEnableNotifications) {
                                    Text(
                                        text = if (openSettings) {
                                            "Open notification settings"
                                        } else {
                                            "Allow notifications"
                                        },
                                        color = Accent,
                                        fontWeight = FontWeight.SemiBold,
                                        textDecoration = TextDecoration.Underline,
                                    )
                                    if (openSettings) {
                                        Spacer(Modifier.width(6.dp))
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                            contentDescription = null,
                                            tint = Accent,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                }

                Row(verticalAlignment = Alignment.Top) {
                    SetupStepMarker(number = if (adbPaired) 1 else 2, completed = adbPaired)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Wireless debugging",
                            color = TextPrimary,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (adbPaired) {
                                "Wireless debugging pairing is saved."
                            } else {
                                "Turn on Wireless debugging."
                            },
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        if (adbPaired) {
                            (helperStatus as? HelperStatus.NeedsSetup)?.reason?.let { reason ->
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = reason,
                                    color = Danger,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                        TextButton(
                            onClick = onSetupAction,
                            enabled = (adbPaired || notificationsAllowed) &&
                                setupState !is SetupState.Connecting,
                        ) {
                            Text(
                                text = if (adbPaired) "Retry helper setup" else "Open Developer options",
                                color = if ((adbPaired || notificationsAllowed) &&
                                    setupState !is SetupState.Connecting
                                ) {
                                    Accent
                                } else {
                                    TextMuted
                                },
                                fontWeight = FontWeight.SemiBold,
                                textDecoration = TextDecoration.Underline,
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                imageVector = if (adbPaired) {
                                    Icons.Rounded.Autorenew
                                } else {
                                    Icons.AutoMirrored.Rounded.OpenInNew
                                },
                                contentDescription = null,
                                tint = if ((adbPaired || notificationsAllowed) &&
                                    setupState !is SetupState.Connecting
                                ) {
                                    Accent
                                } else {
                                    TextMuted
                                },
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        if (!adbPaired) {
                            Text(
                                text = buildAnnotatedString {
                                    append("Then tap ")
                                    withStyle(
                                        SpanStyle(
                                            color = Accent,
                                            fontWeight = FontWeight.Bold,
                                        ),
                                    ) {
                                        append("Pair device with pairing code")
                                    }
                                    append(" and enter the displayed code into the DataProxy notification.")
                                },
                                color = TextSecondary,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = TextSecondary)
            }
        },
    )
}

@Composable
private fun SetupStepMarker(number: Int, completed: Boolean) {
    val shape = CircleShape
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(shape)
            .background(if (completed) Accent else SurfaceMid)
            .border(1.dp, if (completed) Accent else OutlineStrong, shape)
            .semantics {
                contentDescription = if (completed) "Step $number complete" else "Step $number"
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number.toString(),
            color = if (completed) Color.Black else TextSecondary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun openDeveloperOptions(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun canPostPairingNotification(context: android.content.Context): Boolean =
    PairingNotification.canPost(context)

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
