package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.entity.MessageStatus
import com.example.ui.theme.ReadCheckmarkBlue
import com.example.ui.theme.SignalGreen
import com.example.ui.theme.SignalRed
import com.example.ui.theme.SignalYellow

@Composable
fun NodeAvatar(
    name: String,
    modifier: Modifier = Modifier,
    colorHex: String = "#00A884",
    sizeDp: Int = 46
) {
    val bgColor = try {
        Color(android.graphics.Color.parseColor(colorHex))
    } catch (e: Exception) {
        MaterialTheme.colorScheme.primary
    }

    val initials = name.split(" ")
        .mapNotNull { it.firstOrNull()?.toString() }
        .take(2)
        .joinToString("")
        .uppercase()
        .ifEmpty { "N" }

    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (sizeDp / 2.5).sp
        )
    }
}

@Composable
fun MessageStatusIcon(
    status: String,
    modifier: Modifier = Modifier
) {
    when (status) {
        MessageStatus.QUEUED.name -> {
            Icon(
                imageVector = Icons.Default.AccessTime,
                contentDescription = "Queued for mesh",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = modifier.size(14.dp)
            )
        }
        MessageStatus.SENDING.name -> {
            Icon(
                imageVector = Icons.Default.AccessTime,
                contentDescription = "Sending over radio",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = modifier.size(14.dp)
            )
        }
        MessageStatus.SENT.name -> {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Sent",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = modifier.size(15.dp)
            )
        }
        MessageStatus.DELIVERED.name -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Delivered to peer",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = modifier.size(15.dp)
            )
        }
        MessageStatus.READ.name -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Read by peer",
                tint = ReadCheckmarkBlue,
                modifier = modifier.size(15.dp)
            )
        }
        MessageStatus.FAILED.name -> {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = "Failed to deliver",
                tint = MaterialTheme.colorScheme.error,
                modifier = modifier.size(14.dp)
            )
        }
    }
}

@Composable
fun MeshStatusBadge(
    activePeersCount: Int,
    isRelayOn: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (activePeersCount > 0) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.WifiTethering,
                contentDescription = null,
                tint = if (activePeersCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "$activePeersCount peer${if (activePeersCount != 1) "s" else ""} • Relay ${if (isRelayOn) "ON" else "OFF"}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (activePeersCount > 0) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun RssiSignalIndicator(rssi: Int, modifier: Modifier = Modifier) {
    val (color, label) = when {
        rssi >= -65 -> Pair(SignalGreen, "Strong")
        rssi >= -80 -> Pair(SignalYellow, "Good")
        else -> Pair(SignalRed, "Weak")
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "$rssi dBm ($label)",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun EncryptionBadge(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = "End-to-End Encrypted",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(10.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "E2EE AES-256",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
