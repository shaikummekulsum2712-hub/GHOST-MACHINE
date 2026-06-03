package com.example.ghostmachine

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ghostmachine.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    isTyping: Boolean,
    onSendMessage: (String) -> Unit,
    onOpenAccessibility: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size, isTyping) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            GhostTopBar(onOpenAccessibility = onOpenAccessibility)
        },
        bottomBar = {
            GhostInputBar(
                inputText = inputText,
                onInputChange = { inputText = it },
                onSend = {
                    if (inputText.isNotBlank()) {
                        onSendMessage(inputText.trim())
                        inputText = ""
                    }
                },
                isEnabled = !isTyping
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(DarkBackground)
        ) {
            if (messages.isEmpty() && !isTyping) {
                // Empty state
                EmptyState()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        ChatBubble(message = message)
                    }

                    if (isTyping) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.Start,
                                verticalAlignment = Alignment.Top
                            ) {
                                // Ghost avatar for typing indicator
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
                                TypingIndicator()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "👻", fontSize = 56.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Ghost Machine",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Tell me what to do on your phone.\nI'll handle the rest.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GhostTopBar(onOpenAccessibility: () -> Unit) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "👻", fontSize = 22.sp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Ghost Machine",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontSize = 20.sp
                    ),
                    color = TextPrimary
                )
            }
        },
        actions = {
            IconButton(onClick = onOpenAccessibility) {
                Text(text = "⚙️", fontSize = 20.sp)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = DarkBackground,
            scrolledContainerColor = DarkSurface
        )
    )
}

@Composable
private fun GhostInputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    isEnabled: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurface)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Text input field
            TextField(
                value = inputText,
                onValueChange = onInputChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                placeholder = {
                    Text(
                        text = "Tell Ghost what to do...",
                        color = TextTertiary,
                        style = MaterialTheme.typography.bodyLarge
                    )
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = InputFieldBackground,
                    unfocusedContainerColor = InputFieldBackground,
                    cursorColor = AccentBlue,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                shape = RoundedCornerShape(24.dp),
                singleLine = false,
                maxLines = 4,
                enabled = isEnabled
            )

            // Send button
            val canSend = inputText.isNotBlank() && isEnabled
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (canSend)
                            Brush.linearGradient(listOf(UserBubbleStart, UserBubbleEnd))
                        else
                            Brush.linearGradient(listOf(DarkSurfaceVariant, DarkSurfaceVariant))
                    )
                    .then(
                        if (canSend) Modifier.clickable { onSend() } else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "↑",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (canSend) Color.White else TextTertiary
                )
            }
        }
    }
}

