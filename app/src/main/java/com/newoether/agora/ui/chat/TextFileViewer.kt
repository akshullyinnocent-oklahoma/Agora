package com.newoether.agora.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown

private fun isMarkdownFile(fileName: String): Boolean =
    fileName.endsWith(".md", true) || fileName.endsWith(".markdown", true)

@Composable
fun TextFileViewer(
    content: String,
    fileName: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(enabled = true) { onClose() }

    val isMarkdown = remember(fileName) { isMarkdownFile(fileName) }
    val bgColor = if (isMarkdown) MaterialTheme.colorScheme.background else Color(0xFF1A1A1A)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Content — first child, behind buttons
        if (isMarkdown) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 56.dp)
            ) {
                Markdown(content = content, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(80.dp))
            }
        } else {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(scrollState)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 56.dp)
            ) {
                Text(content, color = Color.White.copy(alpha = 0.9f),
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(80.dp))
            }
        }

        // Top gradient bar — second child
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(fileName, color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelMedium)
        }

        // Close button — last child, on top of everything
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(8.dp)
        ) {
            Icon(Icons.Default.Close, "Close", tint = Color.White)
        }
    }
}
