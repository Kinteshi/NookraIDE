package io.github.kinteshi.quirecode.workbench

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.kinteshi.quirecode.transport.ConnectionProfile

/** The icon for a [ConnectionState], in the chip and the dialog's status card. */
private fun ConnectionState.icon(): ImageVector = when (this) {
    ConnectionState.Connected -> Icons.Outlined.Cloud
    ConnectionState.Connecting -> Icons.Outlined.CloudSync
    ConnectionState.NotSetUp -> Icons.Outlined.Add
    ConnectionState.PairingRejected -> Icons.Outlined.Link
    ConnectionState.Idle -> Icons.Outlined.Cloud
    else -> Icons.Outlined.CloudOff
}

/** States where the connection is away and something can be done about it: the banner's and the dialog's. */
private val ConnectionState.away: Boolean
    get() = this in setOf(
        ConnectionState.Lost, ConnectionState.Unreachable, ConnectionState.ForwardingStuck,
        ConnectionState.PairingRejected, ConnectionState.Failed,
    )

/**
 * The top bar's connection chip (G22): the whole status in a few words (the connection's name, Connecting…, Offline,
 * Pair again, Set up), calm colours rather than an alarm; a tap opens the Connections dialog.
 */
@Composable
internal fun ConnectionChip(controller: WorkbenchController) {
    val colors = MaterialTheme.colorScheme
    val summary = controller.connection
    val chipColors = when (summary.state) {
        ConnectionState.Connected, ConnectionState.Connecting -> AssistChipDefaults.assistChipColors()
        ConnectionState.NotSetUp, ConnectionState.Idle -> AssistChipDefaults.assistChipColors(
            containerColor = colors.primaryContainer,
            labelColor = colors.onPrimaryContainer,
            leadingIconContentColor = colors.onPrimaryContainer,
        )
        else -> AssistChipDefaults.assistChipColors(
            containerColor = colors.secondaryContainer,
            labelColor = colors.onSecondaryContainer,
            leadingIconContentColor = colors.onSecondaryContainer,
        )
    }
    Tooltip(summary.title) {
        AssistChip(
            onClick = controller::openConnect,
            label = { Text(summary.chip, maxLines = 1) },
            leadingIcon = {
                if (summary.state == ConnectionState.Connecting) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(summary.state.icon(), contentDescription = null, modifier = Modifier.size(18.dp))
                }
            },
            colors = chipColors,
            modifier = Modifier.widthIn(max = 220.dp),
        )
    }
}

/**
 * Under the top bar while the connection is away (G22, in place of the red banner): what happened and that the app
 * still works, with the one step that most likely helps, Details (the Connections dialog), and Hide. `avf.connection.banner`.
 */
@Composable
internal fun ConnectionBanner(controller: WorkbenchController) {
    val summary = controller.connection
    if (!summary.state.away || !controller.settings.connectionBanner || controller.connectionBannerHiddenFor == summary.state) return
    val facts = controller.connectionFacts
    val hint = when (summary.state) {
        ConnectionState.PairingRejected -> "Pair again to reconnect."
        ConnectionState.ForwardingStuck -> "Linux Terminal may need a restart."
        else -> if (facts.workingOffline) "You're working offline meanwhile; QuireCode reconnects by itself." else "QuireCode reconnects by itself."
    }
    WorkbenchBanner(summary.state.icon(), "${summary.title}. $hint", quiet = true) {
        when (summary.state) {
            ConnectionState.PairingRejected -> FilledTonalButton(onClick = { controller.pairAgain() }) { Text("Pair again") }
            ConnectionState.ForwardingStuck -> FilledTonalButton(onClick = controller::openLinuxTerminalAppInfo) { Text("App info") }
            else -> if (facts.tabletGuest) {
                FilledTonalButton(onClick = controller::openLinuxTerminal) { Text("Open Linux Terminal") }
            } else {
                FilledTonalButton(onClick = controller::connectGuest, enabled = !controller.busy) { Text("Retry") }
            }
        }
        TextButton(onClick = controller::openConnect) { Text("Details") }
        TextButton(onClick = { controller.connectionBannerHiddenFor = summary.state }) { Text("Hide") }
    }
}

/**
 * Connections (G22): one dialog for every connection question. The status and its next step, the saved connections
 * (tap one to switch; edit, pair again or delete from its menu), adding one, and a troubleshooting check.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConnectionsDialog(controller: WorkbenchController) {
    val colors = MaterialTheme.colorScheme
    val summary = controller.connection
    var troubleshoot by remember { mutableStateOf(summary.state.away) }
    AlertDialog(
        onDismissRequest = { controller.dialog = null },
        title = { Text("Connections") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (summary.state != ConnectionState.NotSetUp) StatusCard(controller, summary)
                if (controller.profiles.isNotEmpty()) {
                    Text("Your connections", style = MaterialTheme.typography.titleSmall, color = colors.primary)
                    Column {
                        controller.profiles.forEach { profile -> ConnectionRow(controller, profile) }
                    }
                } else {
                    Text(summary.detail.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                if (controller.managesDevices) PairedDevicesSection(controller, summary)
                Text("Add a connection", style = MaterialTheme.typography.titleSmall, color = colors.primary)
                Column {
                    AddRow(Icons.Outlined.TabletAndroid, "This tablet's Linux guest", "Install the daemon in Linux Terminal, or pair it again") {
                        controller.beginGuestSetup()
                    }
                    AddRow(Icons.Outlined.Computer, "A computer on your network", "Linux, or Windows through WSL: paste the connection string it prints") {
                        controller.dialog = WorkbenchDialog.ComputerPairing
                    }
                    AddRow(Icons.Outlined.Tune, "By hand", "A WebSocket URL and a token") {
                        controller.dialog = WorkbenchDialog.ConnectionEditor(null)
                    }
                }
                if (controller.profiles.isNotEmpty()) {
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            troubleshoot = !troubleshoot
                            if (troubleshoot && controller.connectionCheck == null) controller.checkConnection()
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Troubleshoot", style = MaterialTheme.typography.titleSmall, color = colors.primary, modifier = Modifier.weight(1f))
                        Icon(if (troubleshoot) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
                    }
                    if (troubleshoot) Troubleshooting(controller)
                }
            }
        },
        confirmButton = { TextButton(onClick = { controller.dialog = null }) { Text("Close") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusCard(controller: WorkbenchController, summary: ConnectionSummary) {
    val colors = MaterialTheme.colorScheme
    val facts = controller.connectionFacts
    // Lower than the dialog's own surface, so the card reads as one.
    Surface(color = colors.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (summary.state == ConnectionState.Connecting) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Icon(summary.state.icon(), contentDescription = null, tint = if (summary.state.away) colors.secondary else colors.primary)
                }
                Text(summary.title, style = MaterialTheme.typography.titleMedium)
            }
            summary.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
            if (summary.state == ConnectionState.Connected) controller.daemonVersionNotice?.let { notice ->
                Text(
                    if (notice.status == DaemonVersionStatus.Older) "Its daemon is ${notice.guest}, older than this app's ${notice.app}." else "Its daemon is ${notice.guest}, newer than this app (${notice.app}).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (summary.state) {
                    ConnectionState.Connected -> if (controller.daemonVersionNotice?.status == DaemonVersionStatus.Older) {
                        FilledTonalButton(onClick = controller::updateGuestDaemon) { Text("Update the daemon") }
                    }
                    ConnectionState.Idle, ConnectionState.Failed -> FilledTonalButton(onClick = controller::requestGuestConnection, enabled = !controller.busy) { Text("Connect") }
                    ConnectionState.PairingRejected -> FilledTonalButton(onClick = { controller.pairAgain() }) { Text("Pair again") }
                    ConnectionState.ForwardingStuck -> {
                        FilledTonalButton(onClick = controller::openLinuxTerminalAppInfo) { Text("App info") }
                        TextButton(onClick = controller::connectGuest, enabled = !controller.busy) { Text("Retry") }
                    }
                    ConnectionState.Lost, ConnectionState.Unreachable -> {
                        if (facts.tabletGuest) FilledTonalButton(onClick = controller::openLinuxTerminal) { Text("Open Linux Terminal") }
                        TextButton(onClick = controller::connectGuest, enabled = !controller.busy) { Text("Retry") }
                        if (facts.tabletGuest && summary.state == ConnectionState.Lost) {
                            TextButton(onClick = { controller.dialog = WorkbenchDialog.GuestStopped }) { Text("Why does it stop?") }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun ConnectionRow(controller: WorkbenchController, profile: ConnectionProfile) {
    val colors = MaterialTheme.colorScheme
    val current = profile.id == controller.profileId
    val tablet = isTabletGuestEndpoint(profile.endpoint)
    var menu by remember { mutableStateOf(false) }
    val where = if (tablet) "This tablet's Linux guest" else endpointHost(profile.endpoint) ?: profile.endpoint
    val state = when {
        current && controller.connected -> " · Connected"
        current -> " · Current"
        else -> ""
    }
    ListItem(
        headlineContent = { Text(profile.name) },
        supportingContent = { Text(where + state) },
        leadingContent = {
            Icon(
                if (tablet) Icons.Outlined.TabletAndroid else Icons.Outlined.Computer,
                contentDescription = null,
                tint = if (current) colors.primary else colors.onSurfaceVariant,
            )
        },
        trailingContent = {
            Box {
                Tooltip("More") { IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More for ${profile.name}") } }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Edit…") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = { menu = false; controller.dialog = WorkbenchDialog.ConnectionEditor(profile.id) },
                    )
                    DropdownMenuItem(
                        text = { Text("Pair again…") },
                        leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                        onClick = { menu = false; controller.pairAgain(profile) },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                        onClick = { menu = false; controller.deleteProfile(profile.id) },
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = if (current) colors.surfaceContainerHighest else colors.surfaceContainerHigh),
        // A tap switches to it, or connects it again when it's the current one and away.
        modifier = Modifier.clickable(enabled = !(current && controller.connected) && !controller.busy) { controller.switchConnection(profile) },
    )
}

@Composable
private fun AddRow(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** What can be checked from the app, each with what it means. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Troubleshooting(controller: WorkbenchController) {
    val check = controller.connectionCheck
    val facts = controller.connectionFacts
    // Shown with no check yet (just opened, or the connection changed): run one rather than wait.
    androidx.compose.runtime.LaunchedEffect(check == null, controller.endpoint) {
        if (controller.connectionCheck == null && !controller.connected) controller.checkConnection()
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (facts.tabletGuest) {
            when (check?.terminalAvailable) {
                true -> CheckRow(true, "Linux Terminal is turned on")
                false -> CheckRow(false, "Linux Terminal isn't available. Turn on Linux development environment in Developer options, then open it once.") {
                    TextButton(onClick = controller::openDeveloperOptions) { Text("Developer options") }
                }
                null -> Unit
            }
        }
        val target = check?.target ?: "the daemon's port"
        when {
            controller.connected -> CheckRow(true, "The daemon answers, and accepts this device")
            check == null || check.checking -> CheckRow(null, "Checking $target…")
            check.daemonAnswers == true -> CheckRow(true, "Something answers at $target")
            else -> CheckRow(
                false,
                if (facts.tabletGuest) {
                    "Nothing answers at $target: the guest isn't running, or Linux Terminal isn't forwarding the port. Open Linux " +
                        "Terminal; if it's open already, force stop it in App info and open it again. If Android asks to open port " +
                        "19870, accept it."
                } else {
                    "Nothing answers at $target: is the computer on, on the same network (or both on Tailscale), with its daemon running and the port open in its firewall?"
                },
            )
        }
        if (controller.pairingRejected) {
            CheckRow(false, "The daemon refuses this device's pairing.") { TextButton(onClick = { controller.pairAgain() }) { Text("Pair again") } }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = controller::checkConnection, enabled = check?.checking != true) { Text("Check again") }
            if (facts.tabletGuest) {
                TextButton(onClick = controller::openLinuxTerminalAppInfo) { Text("Linux Terminal's App info") }
                TextButton(onClick = { controller.dialog = WorkbenchDialog.GuestStopped }) { Text("Why the guest stops") }
            }
            TextButton(onClick = controller::copyDiagnostics) { Text("Copy diagnostics") }
        }
    }
}

/** A check: passed (true), failed (false) or not known yet (null), with an optional action under it. */
@Composable
private fun CheckRow(ok: Boolean?, text: String, action: (@Composable () -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when (ok) {
            true -> Icon(Icons.Outlined.CheckCircle, contentDescription = "Passed", tint = colors.primary, modifier = Modifier.size(20.dp))
            false -> Icon(Icons.Outlined.ErrorOutline, contentDescription = "Failed", tint = colors.secondary, modifier = Modifier.size(20.dp))
            null -> Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = "Not known", tint = colors.outline, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            action?.invoke()
        }
    }
}

/** A connection's name, WebSocket URL and token, by hand (G22: what the Connect dialog was). */
@Composable
internal fun ConnectionEditorDialog(controller: WorkbenchController, editor: WorkbenchDialog.ConnectionEditor) {
    val profile = controller.profiles.firstOrNull { it.id == editor.profileId }
    var name by remember(editor) { mutableStateOf(profile?.name.orEmpty()) }
    var url by remember(editor) { mutableStateOf(profile?.endpoint ?: GUEST_DAEMON_ENDPOINT) }
    var token by remember(editor) { mutableStateOf("") }
    val hasToken = controller.connectionHasToken(editor.profileId)
    val valid = url.trim().let { it.startsWith("ws://") || it.startsWith("wss://") } && (hasToken || token.isNotBlank())
    AlertDialog(
        onDismissRequest = { controller.dialog = WorkbenchDialog.Connect },
        title = { Text(if (profile == null) "New connection" else "Edit ${profile.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Saved on this device; the token is encrypted with Android's Keystore. Pairing (Add a connection) fills these in for you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    url, { url = it.trim() },
                    label = { Text("WebSocket URL") },
                    placeholder = { Text(GUEST_DAEMON_ENDPOINT) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    token, { token = it.trim() },
                    label = { Text(if (hasToken) "Token (blank keeps the saved one)" else "Token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (valid) controller.saveConnection(editor.profileId, name, url, token, connect = true) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { controller.saveConnection(editor.profileId, name, url, token, connect = true) }, enabled = valid && !controller.busy) {
                Text("Save and connect")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { controller.saveConnection(editor.profileId, name, url, token, connect = false) }, enabled = valid) { Text("Save") }
                TextButton(onClick = { controller.dialog = WorkbenchDialog.Connect }) { Text("Cancel") }
            }
        },
    )
}

/** Pairing with a computer from its connection string (G22: apart from the tablet's own setup). */
@Composable
internal fun ComputerPairingDialog(controller: WorkbenchController) {
    var connectionString by remember { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = { controller.dialog = null },
        title = { Text("Pair with a computer") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Your files, tools and CPU are then the computer's: Linux, or Windows through WSL2, on the same network. " +
                        "Away from home too, without opening anything to the internet: put both on Tailscale and install with --tailscale.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "1. On the computer, run the release's install-native-daemon.sh with --remote (on Windows, install-host-daemon-wsl.ps1; " +
                        "see docs/HOSTING-THE-DAEMON.md on GitHub). It prints a connection string starting with avf://. To pair again " +
                        "later, run `quirecode-daemon pair --remote <its address>:19870` there.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                Text("2. Paste it here.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = connectionString,
                    onValueChange = { connectionString = it.trim() },
                    label = { Text("Connection string") },
                    placeholder = { Text("avf://192.168.1.10:19870?fp=…&code=…") },
                    singleLine = true,
                    isError = controller.pairingError != null,
                    supportingText = controller.pairingError?.let { error -> { Text(error) } },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { controller.pairWithConnectionString(connectionString) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { controller.pairWithConnectionString(connectionString) },
                enabled = !controller.busy && connectionString.startsWith("avf://"),
            ) { Text(if (controller.busy) "Pairing…" else "Pair and connect") }
        },
        dismissButton = { TextButton(onClick = { controller.dialog = WorkbenchDialog.Connect }) { Text("Back") } },
    )
}

/**
 * The devices paired with the connected daemon (G30): which is this one, when and from where each last connected,
 * Revoke (asked first), and the daemon's connection log. Only daemons that list their devices show it.
 */
@Composable
private fun PairedDevicesSection(controller: WorkbenchController, summary: ConnectionSummary) {
    val colors = MaterialTheme.colorScheme
    val info = controller.pairedDevices
    var showLog by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<io.github.kinteshi.quirecode.transport.PairedDevice?>(null) }
    androidx.compose.runtime.LaunchedEffect(controller.connected, controller.endpoint) { controller.loadPairedDevices() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Devices paired with ${controller.connectionFacts.name}", style = MaterialTheme.typography.titleSmall, color = colors.primary, modifier = Modifier.weight(1f))
        if (controller.pairedDevicesLoading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
    }
    controller.pairedDevicesProblem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
    when {
        info == null -> Unit
        info.devices.isEmpty() -> Text(
            "None yet. A device paired before devices had tokens of their own uses the installer's token, which works only on the same machine.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        else -> Column {
            info.devices.forEach { device ->
                val self = device.id == info.thisDevice
                val seen = device.lastSeen?.let { "Last connected ${formatEpoch(it)}" + (device.lastAddress?.let { address -> " from $address" } ?: "") } ?: "Never connected"
                ListItem(
                    headlineContent = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(device.name, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                            if (self) {
                                Surface(color = colors.primaryContainer, contentColor = colors.onPrimaryContainer, shape = MaterialTheme.shapes.small) {
                                    Text("This device", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                                }
                            }
                        }
                    },
                    supportingContent = { Text("$seen · paired ${formatEpoch(device.pairedAt)} · ${device.id}") },
                    leadingContent = { Icon(Icons.Outlined.TabletAndroid, contentDescription = null, tint = colors.onSurfaceVariant) },
                    trailingContent = {
                        Tooltip("Revoke") {
                            IconButton(onClick = { confirm = device }, enabled = !controller.pairedDevicesLoading) {
                                Icon(Icons.Outlined.LinkOff, contentDescription = "Revoke ${device.name}")
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = colors.surfaceContainerHigh),
                )
            }
        }
    }
    if (info != null && info.recent.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().clickable { showLog = !showLog }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Recent connections", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Icon(if (showLog) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
        }
        if (showLog) {
            Surface(color = colors.surfaceContainerLow, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    info.recent.asReversed().take(15).forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                }
            }
        }
    }
    confirm?.let { device ->
        val self = device.id == info?.thisDevice
        // Pairing again leaves the earlier pairing listed under the same name: the id tells them apart.
        val sameName = info?.devices.orEmpty().count { it.name == device.name } > 1
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Revoke ${device.name}${if (sameName) " (${device.id})" else ""}?") },
            text = {
                Text(
                    if (self) {
                        "This is the device you're using: it's disconnected within 15 seconds and has to pair again with a new code to connect."
                    } else {
                        "It's disconnected within 15 seconds and has to pair again with a new code to connect. Nothing else on ${controller.connectionFacts.name} changes."
                    },
                )
            },
            confirmButton = { TextButton(onClick = { confirm = null; controller.revokePairedDevice(device) }) { Text("Revoke") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

/** Seconds since the epoch, in the device's short date and time. */
private fun formatEpoch(seconds: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(seconds * 1000))
