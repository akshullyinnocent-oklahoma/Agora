package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.newoether.agora.R
import com.newoether.agora.viewmodel.ChatViewModel

private data class LanguageOption(val code: String, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsLanguagePage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val appLanguage by viewModel.appLanguage.collectAsState()
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity

    val restartMessage = stringResource(R.string.language_restart_message)
    val restartAction = stringResource(R.string.language_restart_action)

    val languages = listOf(
        LanguageOption("system", stringResource(R.string.language_system_default)),
        LanguageOption("en", "English"),
        LanguageOption("zh", "简体中文")
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.language_title), fontWeight = FontWeight.Bold) },
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
        Column(
            modifier = Modifier
                .padding(padding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            val changeLanguage: (String) -> Unit = { code ->
                if (code != appLanguage) {
                    viewModel.setAppLanguage(code)
                    viewModel.emitSnackbar(message = restartMessage, actionLabel = restartAction) {
                        activity?.let {
                            it.finish()
                            it.startActivity(it.intent)
                            it.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                        }
                    }
                }
            }

            SettingsGroup(
                title = stringResource(R.string.language_title),
                items = languages.map { lang ->
                    {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { changeLanguage(lang.code) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = appLanguage == lang.code,
                                onClick = { changeLanguage(lang.code) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                lang.label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (appLanguage == lang.code) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            )
        }
    }
}
