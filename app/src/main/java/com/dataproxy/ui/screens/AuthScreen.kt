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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dataproxy.ui.components.HintBanner
import com.dataproxy.ui.components.HintSeverity
import com.dataproxy.ui.components.ScreenFrame
import com.dataproxy.ui.components.ScreenScrollColumn
import com.dataproxy.ui.components.TopBar
import com.dataproxy.ui.theme.Accent
import com.dataproxy.ui.theme.OutlineSoft
import com.dataproxy.ui.theme.OutlineStrong
import com.dataproxy.ui.theme.Spacing
import com.dataproxy.ui.theme.SurfaceLow
import com.dataproxy.ui.theme.SurfaceMid
import com.dataproxy.ui.theme.TextMuted
import com.dataproxy.ui.theme.TextPrimary
import com.dataproxy.ui.theme.TextSecondary
import com.dataproxy.ui.viewmodel.MainViewModel

@Composable
fun AuthScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val enabled by viewModel.authEnabled.collectAsStateWithLifecycle()
    val username by viewModel.authUsername.collectAsStateWithLifecycle()
    val password by viewModel.authPassword.collectAsStateWithLifecycle()
    var usernameText by remember { mutableStateOf(username) }
    var passwordText by remember { mutableStateOf(password) }
    var passwordVisible by remember { mutableStateOf(false) }
    val dirty = usernameText != username || passwordText != password
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(username, password) {
        usernameText = username
        passwordText = password
    }

    ScreenFrame(header = { TopBar(title = "Authentication", onBack = onBack) }) {
        ScreenScrollColumn {
            EnableRow(
                enabled = enabled,
                description = if (enabled) {
                    "Clients must send these credentials during the SOCKS5 handshake " +
                        "(RFC 1929 username/password). Changes apply to new connections."
                } else {
                    "No authentication required. Any client that can reach the listen " +
                        "address can use the proxy."
                },
                onToggle = viewModel::setAuthEnabled,
            )

            Spacer(Modifier.height(Spacing.sectionGap))

            // Auth is enforced fail-closed: with a blank username or password
            // the server refuses every method rather than matching "" == "".
            // That is the safe behaviour, but it is also invisible from the
            // client side, so surface it whenever the form is incomplete.
            // isEmpty, not isBlank, it must match the server's own test in
            // Socks5Connection.negotiateMethod exactly.
            val incomplete = enabled && (username.isEmpty() || password.isEmpty())
            if (incomplete) {
                HintBanner(
                    message = "Credentials incomplete. Every connection will be refused. " +
                        "Fill in both fields and tap Save, or turn authentication off.",
                    severity = HintSeverity.Danger,
                )
                Spacer(Modifier.height(Spacing.itemGap))
            }

            OutlinedTextField(
                value = usernameText,
                onValueChange = { usernameText = it },
                label = { Text("Username") },
                singleLine = true,
                enabled = enabled,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        tint = if (enabled) TextSecondary else TextMuted,
                        modifier = Modifier.size(18.dp),
                    )
                },
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
                value = passwordText,
                onValueChange = { passwordText = it },
                label = { Text("Password") },
                singleLine = true,
                enabled = enabled,
                visualTransformation = if (passwordVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = if (enabled) TextSecondary else TextMuted,
                        modifier = Modifier.size(18.dp),
                    )
                },
                trailingIcon = {
                    IconButton(
                        onClick = { passwordVisible = !passwordVisible },
                        enabled = enabled,
                    ) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Rounded.VisibilityOff
                            else Icons.Rounded.Visibility,
                            contentDescription = if (passwordVisible) "Hide password"
                            else "Show password",
                            tint = if (enabled) TextSecondary else TextMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    color = TextPrimary,
                ),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(Spacing.itemGap))

            Button(
                onClick = {
                    viewModel.setAuthCredentials(usernameText, passwordText)
                    focusManager.clearFocus()
                    keyboard?.hide()
                },
                enabled = dirty,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Color.Black,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Spacing.controlHeight),
            ) {
                Text("Save", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun EnableRow(
    enabled: Boolean,
    description: String,
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Require credentials",
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
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
