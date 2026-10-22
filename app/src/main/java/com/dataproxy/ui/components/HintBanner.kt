package com.dataproxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dataproxy.ui.theme.Danger
import com.dataproxy.ui.theme.TextPrimary
import com.dataproxy.ui.theme.Warning

enum class HintSeverity { Warning, Danger }

@Composable
fun HintBanner(
    message: String,
    severity: HintSeverity,
    modifier: Modifier = Modifier,
) {
    val tint = when (severity) {
        HintSeverity.Warning -> Warning
        HintSeverity.Danger -> Danger
    }
    val icon = when (severity) {
        HintSeverity.Warning -> Icons.Rounded.WarningAmber
        HintSeverity.Danger -> Icons.Rounded.ErrorOutline
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = message,
            color = TextPrimary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
    }
}
