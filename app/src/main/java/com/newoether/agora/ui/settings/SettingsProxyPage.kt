package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsProxyPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val enabled by viewModel.settings.proxyEnabled.collectAsState()
    val type by viewModel.settings.proxyType.collectAsState()
    val host by viewModel.settings.proxyHost.collectAsState()
    val port by viewModel.settings.proxyPort.collectAsState()
    val username by viewModel.settings.proxyUsername.collectAsState()
    val password by viewModel.settings.proxyPassword.collectAsState()
    val bypass by viewModel.settings.proxyBypass.collectAsState()
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()

    val proxyTypes = listOf("http" to "HTTP", "https" to "HTTPS", "socks5" to "SOCKS5")

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_proxy),
        onBack = onBack,
        floatingActionButton = { if (showDocFab) DocumentationFab("proxy.md") }
    ) {
        SettingsGroupColumn {
            SettingsGroup(
                title = stringResource(R.string.settings_proxy),
                items = buildList {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.proxy_enable)) },
                            supportingContent = { Text(stringResource(R.string.proxy_enable_desc)) },
                            leadingContent = { Icon(Icons.Default.Lan, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Switch(checked = enabled, onCheckedChange = { viewModel.settings.setProxyEnabled(it) })
                            },
                            modifier = Modifier.clickable { viewModel.settings.setProxyEnabled(!enabled) }
                        )
                    }
                    if (enabled) {
                        add {
                            ProxyCardSlot(icon = Icons.Default.Lan, label = stringResource(R.string.proxy_type)) {
                                PillTabSwitcher(
                                    tabs = proxyTypes.map { it.second },
                                    selectedIndex = proxyTypes.indexOfFirst { it.first == type }.coerceAtLeast(0),
                                    onSelect = { viewModel.settings.setProxyType(proxyTypes[it].first) }
                                )
                            }
                        }
                        add {
                            ProxyFieldCard(
                                icon = Icons.Default.Dns,
                                label = stringResource(R.string.proxy_host),
                                value = host,
                                onChange = { viewModel.settings.setProxyHost(it) }
                            )
                        }
                        add {
                            ProxyFieldCard(
                                icon = Icons.Default.Numbers,
                                label = stringResource(R.string.proxy_port),
                                value = port,
                                onChange = { viewModel.settings.setProxyPort(it.filter { c -> c.isDigit() }) },
                                keyboard = KeyboardType.Number
                            )
                        }
                        add {
                            ProxyFieldCard(
                                icon = Icons.Default.Person,
                                label = stringResource(R.string.proxy_username),
                                value = username,
                                onChange = { viewModel.settings.setProxyUsername(it) }
                            )
                        }
                        add {
                            ProxyFieldCard(
                                icon = Icons.Default.Lock,
                                label = stringResource(R.string.proxy_password),
                                value = password,
                                onChange = { viewModel.settings.setProxyPassword(it) },
                                password = true
                            )
                        }
                    }
                }
            )
            if (enabled) {
                SettingsGroup(
                    title = stringResource(R.string.proxy_bypass),
                    items = listOf({
                        ProxyFieldCard(
                            icon = Icons.AutoMirrored.Filled.AltRoute,
                            label = stringResource(R.string.proxy_bypass),
                            description = stringResource(R.string.proxy_bypass_desc),
                            value = bypass,
                            onChange = { viewModel.settings.setProxyBypass(it) },
                            singleLine = false
                        )
                    })
                )
            }
        }
        if (showDocFab) { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

/** A settings-card slot following the app's in-card layout: a primary-tinted leading icon, a
 *  [bodyLarge]/Medium label, and arbitrary [content] (e.g. a tab switcher) beneath it. */
@Composable
private fun ProxyCardSlot(
    icon: ImageVector,
    label: String,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(10.dp))
                content()
            }
        }
    }
}

/** Canonical proxy input row: matches the field treatment used on the Web Search / Image Gen
 *  settings pages (leading icon, label, then an outlined field with placeholder-free body text). */
@Composable
private fun ProxyFieldCard(
    icon: ImageVector,
    label: String,
    value: String,
    onChange: (String) -> Unit,
    description: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    singleLine: Boolean = true
) {
    var draft by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (value != draft) draft = value }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (description != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(modifier = Modifier.noOpBringIntoView().padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it; onChange(it) },
                        singleLine = singleLine,
                        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                        shape = RoundedCornerShape(16.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
