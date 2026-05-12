package com.newoether.agora

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.service.AgoraForegroundService
import com.newoether.agora.service.AppForegroundTracker
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.ui.chat.ChatApp
import com.newoether.agora.ui.chat.FullScreenImageViewer
import com.newoether.agora.ui.chat.TextFileViewer
import com.newoether.agora.ui.chat.VideoPlayer
import com.newoether.agora.ui.chat.ZoomableImage
import com.newoether.agora.ui.settings.SettingsScreen
import com.newoether.agora.ui.theme.AgoraTheme
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.viewmodel.ChatViewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val langCode = kotlinx.coroutines.runBlocking {
            SettingsManager(newBase).appLanguage.first()
        }
        val locale = when (langCode) {
            "zh" -> java.util.Locale("zh", "CN")
            "en" -> java.util.Locale("en")
            else -> null
        }
        if (locale != null) {
            java.util.Locale.setDefault(locale)
            val config = android.content.res.Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        com.newoether.agora.util.DebugLog.init(this)
        AgoraForegroundService.createChannel(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            }
        }

        val storedVersion = ChatDatabase.getStoredVersion(this)
        val needsErrorDialog = storedVersion > ChatDatabase.CURRENT_VERSION

        val memoryManager = MemoryManager(applicationContext)
        val settingsManager = SettingsManager(applicationContext)

        enableEdgeToEdge()
        setContent {
            AgoraTheme {
                val activity = LocalActivity.current

                if (needsErrorDialog) {
                    AlertDialog(
                        onDismissRequest = { activity?.finish() },
                        title = { Text(stringResource(R.string.database_incompatible)) },
                        text = { Text(stringResource(R.string.database_incompatible_desc)) },
                        dismissButton = {
                            TextButton(onClick = { activity?.finish() }) { Text(stringResource(R.string.quit)) }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                applicationContext.deleteDatabase(ChatDatabase.DB_NAME)
                                activity?.recreate()
                            }) { Text(stringResource(R.string.clear_database)) }
                        }
                    )
                } else {
                    val database = ChatDatabase.build(this)
                    val factory = ChatViewModelFactory(application, settingsManager, database.chatDao(), memoryManager, this@MainActivity)
                    val viewModel: ChatViewModel = viewModel(factory = factory)
                    MainNavigation(viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppForegroundTracker.isInForeground = true
    }

    override fun onPause() {
        super.onPause()
        AppForegroundTracker.isInForeground = false
    }
}

@Composable
fun MainNavigation(viewModel: ChatViewModel) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var fullScreenImageUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collect { event ->
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = event.message,
                actionLabel = event.actionLabel,
                duration = if (event.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                event.onAction?.invoke()
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ChatApp(
                viewModel = viewModel,
                onOpenSettings = {
                    showSettings = true
                },
                onImageClick = { url ->
                    focusManager.clearFocus()
                    fullScreenImageUrl = url
                },
                onFileContentClick = { name, content ->
                    focusManager.clearFocus()
                    viewModel.showFilePreview(name, content)
                },
                onPdfPagesClick = { pages, idx ->
                    focusManager.clearFocus()
                    viewModel.showPdfPreview(pages, idx)
                }
            )

            // Scrim that fades in behind the settings page
            AnimatedVisibility(
                visible = showSettings,
                enter = fadeIn(animationSpec = tween(400)),
                exit = fadeOut(animationSpec = tween(400))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .pointerInput(Unit) {
                            detectTapGestures { showSettings = false }
                        }
                )
            }

            AnimatedVisibility(
                visible = showSettings,
                enter = slideInHorizontally(animationSpec = tween(400)) { it },
                exit = slideOutHorizontally(animationSpec = tween(400)) { it }
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SettingsScreen(
                        viewModel = viewModel,
                        onBack = {
                            showSettings = false
                        }
                    )
                }
            }

            // Full screen image/video preview
            AnimatedVisibility(
                visible = fullScreenImageUrl != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                var lastUrl by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(fullScreenImageUrl) {
                    if (fullScreenImageUrl != null) lastUrl = fullScreenImageUrl
                }
                val url = lastUrl ?: return@AnimatedVisibility
                val context = LocalContext.current
                val mimeType = remember(url) {
                    try { context.contentResolver.getType(android.net.Uri.parse(url)) } catch (_: Exception) { null }
                }
                val isVideo = mimeType?.startsWith("video/") == true ||
                    url.endsWith(".mp4", true) || url.endsWith(".webm", true) ||
                    url.endsWith(".mov", true) || url.endsWith(".avi", true) ||
                    url.contains("vid_original_")

                if (isVideo) {
                    VideoPlayer(
                        uri = url,
                        onClose = { fullScreenImageUrl = null }
                    )
                } else {
                    FullScreenImageViewer(
                        url = url,
                        onClose = { fullScreenImageUrl = null }
                    )
                }
            }

            // PDF page viewer
            val pdfPages by viewModel.previewPdfPages.collectAsState()
            val pdfIndex by viewModel.previewPdfIndex.collectAsState()
            if (pdfPages.isNotEmpty()) {
                var currentPage by remember { mutableIntStateOf(pdfIndex) }
                Box(modifier = Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
                    ZoomableImage(model = pdfPages[currentPage], modifier = Modifier.fillMaxSize())
                    Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.TopStart).padding(12.dp)) {
                        Text("${currentPage + 1} / ${pdfPages.size}", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                    }
                    IconButton(onClick = { viewModel.clearPreviews() }, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).background(Color.Black.copy(alpha = 0.4f), CircleShape)) {
                        Icon(Icons.Default.Close, "Close", tint = Color.White)
                    }
                    BackHandler(enabled = true) { viewModel.clearPreviews() }
                    if (currentPage > 0) Box(Modifier.fillMaxHeight().fillMaxWidth(0.15f).align(Alignment.CenterStart).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { currentPage-- })
                    if (currentPage < pdfPages.size - 1) Box(Modifier.fillMaxHeight().fillMaxWidth(0.15f).align(Alignment.CenterEnd).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { currentPage++ })
                }
            }

            // Text file viewer
            val fileContent by viewModel.previewFileContent.collectAsState()
            val fileName by viewModel.previewFileName.collectAsState()
            if (fileContent != null && fileName != null) {
                TextFileViewer(content = fileContent!!, fileName = fileName!!, onClose = { viewModel.clearPreviews() })
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
            )
        }
    }
}
