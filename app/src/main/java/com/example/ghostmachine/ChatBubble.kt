package com.example.ghostmachine

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ghostmachine.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ChatBubble(message: ChatMessage, modifier: Modifier = Modifier) {
    val isUser = message.isUser

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (isUser) {
            UserBubble(message)
        } else {
            AiBubbleView(message)
        }
    }
}

@Composable
private fun UserBubble(message: ChatMessage) {
    Column(horizontalAlignment = Alignment.End) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 20.dp,
                        topEnd = 6.dp,
                        bottomEnd = 20.dp,
                        bottomStart = 20.dp
                    )
                )
                .background(
                    Brush.linearGradient(
                        colors = listOf(UserBubbleStart, UserBubbleEnd)
                    )
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary
            )
        }

        Text(
            text = formatTimestamp(message.timestamp),
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
            modifier = Modifier.padding(top = 4.dp, end = 4.dp)
        )
    }
}

@Composable
private fun AiBubbleView(message: ChatMessage) {
    var showJson by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(0.88f),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        // Ghost avatar
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(AccentBlue.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "👻", fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column {
            Box(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = 6.dp,
                            topEnd = 20.dp,
                            bottomEnd = 20.dp,
                            bottomStart = 20.dp
                        )
                    )
                    .background(AiBubble)
                    .border(
                        width = 1.dp,
                        color = AiBubbleBorder,
                        shape = RoundedCornerShape(
                            topStart = 6.dp,
                            topEnd = 20.dp,
                            bottomEnd = 20.dp,
                            bottomStart = 20.dp
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Column {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary
                    )

                    // Status chip
                    if (message.status != MessageStatus.SENT) {
                        Spacer(modifier = Modifier.height(8.dp))
                        StatusChip(message)
                    }

                    // Expandable JSON detail
                    if (message.actionJson != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (showJson) "Hide action detail ▲" else "Show action detail ▼",
                            style = MaterialTheme.typography.labelSmall,
                            color = AccentBlue,
                            modifier = Modifier.clickable { showJson = !showJson }
                        )

                        AnimatedVisibility(visible = showJson) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(DarkBackground)
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = message.actionJson,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        letterSpacing = 0.5.sp
                                    ),
                                    color = StatusSuccess
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = formatTimestamp(message.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
            )
        }
    }
}

@Composable
private fun StatusChip(message: ChatMessage) {
    val (label, color) = when (message.status) {
        MessageStatus.EXECUTING -> {
            if (message.totalSteps > 0) {
                "⏳ Step ${message.currentStep}/${message.totalSteps}..." to StatusPending
            } else {
                "⏳ Executing..." to StatusPending
            }
        }
        MessageStatus.SUCCESS -> {
            if (message.totalSteps > 0) {
                "✅ All ${message.totalSteps} steps done" to StatusSuccess
            } else {
                "✅ Done" to StatusSuccess
            }
        }
        MessageStatus.FAILED -> {
            if (message.totalSteps > 0 && message.currentStep > 0) {
                "❌ Failed at step ${message.currentStep}/${message.totalSteps}" to StatusError
            } else {
                "❌ Failed" to StatusError
            }
        }
        else -> return
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold
            ),
            color = color
        )
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
