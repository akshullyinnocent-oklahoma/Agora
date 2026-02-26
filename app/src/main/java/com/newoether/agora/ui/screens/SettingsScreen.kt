package com.newoether.agora.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.data.ApiKeyEntry
import com.newoether.agora.data.SystemPromptEntry
import com.newoether.agora.viewmodel.ChatViewModel

@Composable
fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                content()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(viewModel: ChatViewModel, onBack: () -> Unit) {
    val provider by viewModel.provider.collectAsState()
    val apiKeys by viewModel.apiKeys.collectAsState()
    val activeApiKeyId by viewModel.activeApiKeyId.collectAsState()
    val systemPrompts by viewModel.systemPrompts.collectAsState()
    val activeSystemPromptId by viewModel.activeSystemPromptId.collectAsState()
    val availableModels by viewModel.availableModels.collectAsState()
    val enabledModels by viewModel.enabledModels.collectAsState()
    val modelAliases by viewModel.modelAliases.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val maxContextWindow by viewModel.maxContextWindow.collectAsState()
    val visualizeContextRollout by viewModel.visualizeContextRollout.collectAsState()

    val noOpResponder = remember {
        object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect = localRect
            override suspend fun bringChildIntoView(localRect: () -> Rect?) {}
        }
    }

    var showKeyDialog by remember { mutableStateOf<ApiKeyEntry?>(null) }
    var showPromptDialog by remember { mutableStateOf<SystemPromptEntry?>(null) }
    var showDeleteKeyConfirm by remember { mutableStateOf<ApiKeyEntry?>(null) }
    var showDeletePromptConfirm by remember { mutableStateOf<SystemPromptEntry?>(null) }
    var showModelAliasDialog by remember { mutableStateOf<String?>(null) }
    var showProviderDialog by remember { mutableStateOf(false) }
    
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("General", "Models")
    
    val providers = listOf("Google", "OpenAI (Soon)", "Anthropic (Soon)")

    val pagerState = rememberPagerState(pageCount = { tabs.size })

    LaunchedEffect(selectedTab) {
        if (selectedTab != pagerState.currentPage) {
            pagerState.animateScrollToPage(selectedTab)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        if (selectedTab != pagerState.currentPage) {
            selectedTab = pagerState.currentPage
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    )
                )
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.primary,
                    indicator = { tabPositions ->
                        SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title, style = MaterialTheme.typography.labelLarge) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.padding(padding)
        ) { page ->
            if (page == 0) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    // 1. API Group
                    SettingsGroup(title = "API") {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text("API Provider") },
                            supportingContent = { Text(provider) },
                            leadingContent = {
                                Icon(Icons.Default.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier.clickable { showProviderDialog = true }
                        )

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text("API Keys") },
                            supportingContent = { Text(if (apiKeys.isEmpty()) "No keys configured" else "${apiKeys.size} key(s) configured") },
                            leadingContent = {
                                Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        )
                        
                        apiKeys.forEach { entry ->
                            var showMenu by remember { mutableStateOf(false) }
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = { Text(entry.name, fontWeight = FontWeight.Medium) },
                                supportingContent = { Text(entry.key.take(4) + "••••••••" + entry.key.takeLast(4)) },
                                leadingContent = {
                                    RadioButton(selected = entry.id == activeApiKeyId, onClick = { viewModel.setActiveApiKey(entry.id) })
                                },
                                trailingContent = {
                                    Box {
                                        IconButton(onClick = { showMenu = true }) {
                                            Icon(Icons.Default.MoreVert, contentDescription = "Options")
                                        }
                                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, shape = RoundedCornerShape(12.dp)) {
                                            DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { showMenu = false; showKeyDialog = entry })
                                            DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; showDeleteKeyConfirm = entry })
                                        }
                                    }
                                },
                                modifier = Modifier.clickable { viewModel.setActiveApiKey(entry.id) }.padding(start = 16.dp)
                            )
                        }
                        
                        TextButton(
                            onClick = { showKeyDialog = ApiKeyEntry(name = "", key = "") },
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add New Key")
                        }
                    }

                    // 2. Prompt Group
                    SettingsGroup(title = "PROMPT") {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text("System Instructions") },
                            supportingContent = { Text("Define global personas or rules") },
                            leadingContent = {
                                Icon(Icons.Default.Psychology, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        )
                        
                        systemPrompts.forEach { entry ->
                            var showMenu by remember { mutableStateOf(false) }
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = { Text(entry.title, fontWeight = FontWeight.Medium) },
                                supportingContent = { Text(entry.content, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                                leadingContent = {
                                    RadioButton(selected = entry.id == activeSystemPromptId, onClick = { viewModel.setActiveSystemPrompt(entry.id) })
                                },
                                trailingContent = {
                                    Box {
                                        IconButton(onClick = { showMenu = true }) {
                                            Icon(Icons.Default.MoreVert, contentDescription = "Options")
                                        }
                                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, shape = RoundedCornerShape(12.dp)) {
                                            DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { showMenu = false; showPromptDialog = entry })
                                            DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; showDeletePromptConfirm = entry })
                                        }
                                    }
                                },
                                modifier = Modifier.clickable { viewModel.setActiveSystemPrompt(entry.id) }.padding(start = 16.dp)
                            )
                        }
                        
                        TextButton(
                            onClick = { showPromptDialog = SystemPromptEntry(title = "", content = "") },
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add New Instruction")
                        }
                    }

                    // 3. Memory Group
                    SettingsGroup(title = "MEMORY") {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text("Context Window") },
                            supportingContent = { 
                                Column {
                                    Text("Retain $maxContextWindow recent messages")
                                    Slider(
                                        value = maxContextWindow.toFloat(),
                                        onValueChange = { viewModel.setMaxContextWindow(it.toInt()) },
                                        valueRange = 5f..100f,
                                        steps = 19,
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                    )
                                }
                            },
                            leadingContent = {
                                Icon(Icons.Default.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        )

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text("Visualize Context Roll-Out") },
                            supportingContent = { Text("Dim messages outside the context window") },
                            leadingContent = {
                                Icon(Icons.Default.Visibility, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            },
                            trailingContent = {
                                Switch(checked = visualizeContextRollout, onCheckedChange = { viewModel.setVisualizeContextRollout(it) })
                            },
                            modifier = Modifier.clickable { viewModel.setVisualizeContextRollout(!visualizeContextRollout) }
                        )
                    }
                }
            } else {
                // Models Tab
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    SettingsGroup(title = "ACTIVE MODEL") {
                        if (enabledModels.isEmpty()) {
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = { Text("No models enabled") },
                                supportingContent = { Text("Select models from the list below to enable them.", color = MaterialTheme.colorScheme.error) }
                            )
                        } else {
                            enabledModels.forEach { model ->
                                val alias = modelAliases[model]
                                val displayName = alias ?: model.removePrefix("models/")
                                
                                ListItem(
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    headlineContent = { 
                                        Text(
                                            displayName, 
                                            fontWeight = if (model == selectedModel) FontWeight.Bold else FontWeight.Normal
                                        ) 
                                    },
                                    supportingContent = if (alias != null) { { Text(model.removePrefix("models/")) } } else null,
                                    leadingContent = {
                                        RadioButton(
                                            selected = model == selectedModel,
                                            onClick = { viewModel.setSelectedModel(model) }
                                        )
                                    },
                                    trailingContent = {
                                        IconButton(onClick = { showModelAliasDialog = model }) {
                                            Icon(Icons.Default.Edit, contentDescription = "Rename", tint = MaterialTheme.colorScheme.primary)
                                        }
                                    },
                                    modifier = Modifier.clickable { viewModel.setSelectedModel(model) }
                                )
                            }
                        }
                    }

                    SettingsGroup(title = "AVAILABLE MODELS") {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text("Sync from Provider") },
                            supportingContent = { Text("Fetch the latest model list") },
                            leadingContent = {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier.clickable { viewModel.fetchAvailableModels() }
                        )
                        
                        if (availableModels.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            
                            availableModels.forEach { model ->
                                val isEnabled = enabledModels.contains(model)
                                ListItem(
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    headlineContent = { Text(model.removePrefix("models/")) },
                                    trailingContent = {
                                        Checkbox(checked = isEnabled, onCheckedChange = {
                                            viewModel.setEnabledModels(if (it) enabledModels + model else enabledModels - model)
                                        })
                                    },
                                    modifier = Modifier.clickable {
                                        viewModel.setEnabledModels(if (!isEnabled) enabledModels + model else enabledModels - model)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Provider Selection Dialog
    if (showProviderDialog) {
        AlertDialog(
            onDismissRequest = { showProviderDialog = false },
            title = { Text("Select AI Provider") },
            text = {
                Column {
                    providers.forEach { p ->
                        val isSelected = p == provider
                        val isSupported = p == "Google"
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { 
                                Text(
                                    p, 
                                    color = if (isSupported) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                ) 
                            },
                            leadingContent = {
                                if (isSelected) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                } else {
                                    Spacer(modifier = Modifier.size(24.dp))
                                }
                            },
                            modifier = Modifier.clickable(enabled = isSupported) {
                                if (isSupported) {
                                    viewModel.setProvider(p)
                                    showProviderDialog = false
                                }
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showProviderDialog = false }) { Text("Close") }
            }
        )
    }

    // Model Alias Dialog
    showModelAliasDialog?.let { model ->
        val aliasState = rememberTextFieldState(modelAliases[model] ?: "")
        
        AlertDialog(
            onDismissRequest = { showModelAliasDialog = null },
            title = { Text("Rename Model") },
            text = {
                Column {
                    Text("Current ID: ${model.removePrefix("models/")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.bringIntoViewResponder(noOpResponder)) {
                        TextField(
                            state = aliasState,
                            label = { Text("Alias") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            placeholder = { Text(model.removePrefix("models/")) },
                            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateModelAlias(model, aliasState.text.toString())
                    showModelAliasDialog = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showModelAliasDialog = null }) { Text("Cancel") }
            }
        )
    }

    // API Key Dialog (Add/Edit)
    showKeyDialog?.let { entry ->
        var name by remember { mutableStateOf(entry.name) }
        val keyState = rememberTextFieldState(entry.key)
        val isEdit = apiKeys.any { it.id == entry.id }
        
        AlertDialog(
            onDismissRequest = { showKeyDialog = null },
            title = { Text(if (isEdit) "Edit API Key" else "Add API Key") },
            text = {
                Column {
                    TextField(
                        value = name, onValueChange = { name = it }, 
                        label = { Text("Name (e.g. Workspace)") }, 
                        modifier = Modifier
                            .fillMaxWidth()
                            .bringIntoViewResponder(noOpResponder),
                        shape = MaterialTheme.shapes.large,
                        colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.bringIntoViewResponder(noOpResponder)) {
                        TextField(
                            state = keyState,
                            label = { Text("Google API Key") }, 
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)
                        )
                    }
                }
            },
            confirmButton = { 
                TextButton(onClick = { 
                    val key = keyState.text.toString()
                    if (name.isNotBlank() && key.isNotBlank()) {
                        if (isEdit) viewModel.updateApiKey(entry.id, name, key) else viewModel.addApiKey(name, key)
                        showKeyDialog = null 
                    } 
                }) { Text(if (isEdit) "Save" else "Add") } 
            },
            dismissButton = { TextButton(onClick = { showKeyDialog = null }) { Text("Cancel") } }
        )
    }

    // System Prompt Dialog (Add/Edit)
    showPromptDialog?.let { entry ->
        var title by remember { mutableStateOf(entry.title) }
        val contentState = rememberTextFieldState(entry.content)
        val isEdit = systemPrompts.any { it.id == entry.id }

        AlertDialog(
            onDismissRequest = { showPromptDialog = null },
            title = { Text(if (isEdit) "Edit System Prompt" else "Add System Prompt") },
            text = {
                Column {
                    TextField(
                        value = title, onValueChange = { title = it }, 
                        label = { Text("Title (e.g. Translator)") }, 
                        modifier = Modifier
                            .fillMaxWidth()
                            .bringIntoViewResponder(noOpResponder),
                        shape = MaterialTheme.shapes.large,
                        colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.bringIntoViewResponder(noOpResponder)) {
                        TextField(
                            state = contentState,
                            label = { Text("System Instruction") }, 
                            modifier = Modifier.fillMaxWidth(),
                            lineLimits = TextFieldLineLimits.MultiLine(1, 10),
                            shape = MaterialTheme.shapes.large,
                            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)
                        )
                    }
                }
            },
            confirmButton = { 
                TextButton(onClick = { 
                    val content = contentState.text.toString()
                    if (title.isNotBlank() && content.isNotBlank()) {
                        if (isEdit) viewModel.updateSystemPrompt(entry.id, title, content) else viewModel.addSystemPrompt(title, content)
                        showPromptDialog = null 
                    } 
                }) { Text(if (isEdit) "Save" else "Add") } 
            },
            dismissButton = { TextButton(onClick = { showPromptDialog = null }) { Text("Cancel") } }
        )
    }

    // API Key Delete Confirmation
    showDeleteKeyConfirm?.let { entry ->
        AlertDialog(
            onDismissRequest = { showDeleteKeyConfirm = null },
            title = { Text("Delete API Key?") },
            text = { Text("Are you sure you want to delete '${entry.name}'? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteApiKey(entry.id)
                        showDeleteKeyConfirm = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteKeyConfirm = null }) { Text("Cancel") } }
        )
    }

    // System Prompt Delete Confirmation
    showDeletePromptConfirm?.let { entry ->
        AlertDialog(
            onDismissRequest = { showDeletePromptConfirm = null },
            title = { Text("Delete System Instruction?") },
            text = { Text("Are you sure you want to delete '${entry.title}'? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSystemPrompt(entry.id)
                        showDeletePromptConfirm = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeletePromptConfirm = null }) { Text("Cancel") } }
        )
    }
}
