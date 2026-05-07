package com.newoether.agora.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import java.io.File

private data class SearchMethodOption(val key: String, @androidx.annotation.StringRes val labelRes: Int)

private val searchMethods = listOf(
    SearchMethodOption("keyword", R.string.search_method_keyword),
    SearchMethodOption("rag", R.string.search_method_rag)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSearchPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val accessPastConversations by viewModel.accessPastConversations.collectAsState()
    val modelSearchMethod by viewModel.modelSearchMethod.collectAsState()
    val manualSearchMethod by viewModel.manualSearchMethod.collectAsState()
    val embeddingSource by viewModel.embeddingSource.collectAsState()
    val embeddingModel by viewModel.embeddingModel.collectAsState()
    val embeddingBaseUrl by viewModel.embeddingBaseUrl.collectAsState()
    val localEmbeddingModelUrl by viewModel.localEmbeddingModelUrl.collectAsState()
    val localEmbeddingModelPath by viewModel.localEmbeddingModelPath.collectAsState()
    var showEmbeddingDialog by remember { mutableStateOf(false) }
    var editEmbeddingModel by remember { mutableStateOf("") }
    var editEmbeddingUrl by remember { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                )
            )
        }
    ) { padding ->
        val fm = LocalFocusManager.current
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { fm.clearFocus() }
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            SettingsGroup(title = stringResource(R.string.memory_access_title)) {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(stringResource(R.string.memory_access_past)) },
                    supportingContent = { Text(stringResource(R.string.memory_access_past_desc)) },
                    leadingContent = { Icon(Icons.Default.Chat, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        Switch(checked = accessPastConversations, onCheckedChange = { viewModel.setAccessPastConversations(it) })
                    },
                    modifier = Modifier.clickable { viewModel.setAccessPastConversations(!accessPastConversations) }
                )
            }

            SettingsGroup(title = stringResource(R.string.search_methods_title)) {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(stringResource(R.string.model_search_method)) },
                    supportingContent = { Text(stringResource(R.string.model_search_method_desc)) },
                    leadingContent = { Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary) }
                )
                searchMethods.forEach { method ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(stringResource(method.labelRes)) },
                        leadingContent = {
                            RadioButton(
                                selected = modelSearchMethod == method.key,
                                onClick = { viewModel.setModelSearchMethod(method.key) }
                            )
                        },
                        modifier = Modifier.clickable { viewModel.setModelSearchMethod(method.key) }
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(stringResource(R.string.manual_search_method)) },
                    supportingContent = { Text(stringResource(R.string.manual_search_method_desc)) },
                    leadingContent = { Icon(Icons.Default.ManageSearch, null, tint = MaterialTheme.colorScheme.primary) }
                )
                searchMethods.forEach { method ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(stringResource(method.labelRes)) },
                        leadingContent = {
                            RadioButton(
                                selected = manualSearchMethod == method.key,
                                onClick = { viewModel.setManualSearchMethod(method.key) }
                            )
                        },
                        modifier = Modifier.clickable { viewModel.setManualSearchMethod(method.key) }
                    )
                }
            }

            SettingsGroup(title = stringResource(R.string.embedding_title)) {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(stringResource(R.string.embedding_source_label)) },
                    supportingContent = { Text(if (embeddingSource == "local") stringResource(R.string.embedding_source_local) else stringResource(R.string.embedding_source_remote)) },
                    leadingContent = { Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        Switch(
                            checked = embeddingSource == "local",
                            onCheckedChange = { viewModel.setEmbeddingSource(if (it) "local" else "remote") }
                        )
                    },
                    modifier = Modifier.clickable { viewModel.setEmbeddingSource(if (embeddingSource == "local") "remote" else "local") }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                if (embeddingSource == "remote") {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(stringResource(R.string.embedding_model_label)) },
                        supportingContent = { Text(embeddingModel) },
                        leadingContent = { Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable {
                            editEmbeddingModel = embeddingModel
                            editEmbeddingUrl = embeddingBaseUrl
                            showEmbeddingDialog = true
                        }
                    )
                } else {
                    val scope = rememberCoroutineScope()
                    val context = LocalContext.current
                    val filePickerLauncher = rememberLauncherForActivityResult(
                        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
                    ) { uri ->
                        if (uri != null) {
                            isImporting = true
                            scope.launch {
                                try {
                                    val destFile = File(context.filesDir, "embedding_model.tflite")
                                    context.contentResolver.openInputStream(uri)?.use { input ->
                                        destFile.outputStream().use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                    viewModel.setLocalEmbeddingModelPath(destFile.absolutePath)
                                } catch (_: Exception) { }
                                isImporting = false
                            }
                        }
                    }
                    val modelReady = localEmbeddingModelPath.isNotBlank() && remember(localEmbeddingModelPath) {
                        com.newoether.agora.api.LocalEmbeddingEngine.isModelReady(localEmbeddingModelPath)
                    }
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(stringResource(R.string.local_model_status)) },
                        supportingContent = {
                            Text(
                                if (isImporting) stringResource(R.string.importing_model)
                                else if (modelReady) stringResource(R.string.local_model_ready)
                                else stringResource(R.string.local_model_not_ready),
                                color = if (modelReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        leadingContent = {
                            if (isImporting)
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            else
                                Icon(
                                    if (modelReady) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    null,
                                    tint = if (modelReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                        },
                        trailingContent = {
                            TextButton(onClick = { filePickerLauncher.launch(arrayOf("*/*")) }) {
                                Text(stringResource(R.string.import_model))
                            }
                        }
                    )
                }
            }
        }

        if (showEmbeddingDialog) {
            AlertDialog(
                onDismissRequest = { showEmbeddingDialog = false },
                title = { Text(stringResource(R.string.embedding_title)) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = editEmbeddingModel,
                            onValueChange = { editEmbeddingModel = it },
                            label = { Text(stringResource(R.string.embedding_model_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = editEmbeddingUrl,
                            onValueChange = { editEmbeddingUrl = it },
                            label = { Text(stringResource(R.string.embedding_base_url_label)) },
                            placeholder = { Text("https://api.openai.com/v1") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.setEmbeddingModel(editEmbeddingModel)
                        viewModel.setEmbeddingBaseUrl(editEmbeddingUrl)
                        showEmbeddingDialog = false
                    }) { Text(stringResource(R.string.save)) }
                },
                dismissButton = {
                    TextButton(onClick = { showEmbeddingDialog = false }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }
    }
}
