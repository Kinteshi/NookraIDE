package io.github.kinteshi.nookraide.workbench

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FolderOpen
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kinteshi.nookraide.transport.ConnectionProfile
import io.github.kinteshi.nookraide.transport.ProfileRoute
import io.github.kinteshi.nookraide.transport.RoutePreference

/** The icon for a [ConnectionState], in the chip and the dialog's status card. */
private fun ConnectionState.icon(): ImageVector = when (this) {
    ConnectionState.OnDevice -> Icons.Outlined.TabletAndroid
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
 * The bottom-left remote status indicator (G22), modeled after VS Code's Remote indicator in
 * the status bar: calm Material You colours and concise state; a tap opens the Connections dialog.
 */
@Composable
internal fun ConnectionChip(controller: WorkbenchController, compact: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val summary = controller.connection
    val animate = controller.settings.animations
    val colorMs = if (animate) 160 else 0
    val (targetContainer, targetContent) = when (summary.state) {
        ConnectionState.Connected, ConnectionState.OnDevice -> colors.primaryContainer to colors.onPrimaryContainer
        ConnectionState.Connecting, ConnectionState.Idle -> colors.surfaceContainerHighest to colors.onSurface
        ConnectionState.NotSetUp -> colors.primary to colors.onPrimary
        else -> colors.secondaryContainer to colors.onSecondaryContainer
    }
    val containerColor by animateColorAsState(targetContainer, tween(colorMs), label = "chipContainer")
    val contentColor by animateColorAsState(targetContent, tween(colorMs), label = "chipContent")
    Tooltip(summary.title) {
        Surface(
            onClick = controller::openConnect,
            color = containerColor,
            contentColor = contentColor,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.height(22.dp).widthIn(max = if (compact) 116.dp else 190.dp).animateContentSize(tween(if (animate) 150 else 0)),
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (summary.state == ConnectionState.Connecting) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.6.dp, color = contentColor)
                } else {
                    Icon(summary.state.icon(), contentDescription = summary.title, modifier = Modifier.size(13.dp))
                }
                Text(
                    summary.chip,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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
        else -> if (facts.workingOffline) "You're working offline meanwhile." else "Tap Reconnect when it's ready."
    }
    WorkbenchBanner(summary.state.icon(), "${summary.title}. $hint", quiet = true) {
        when (summary.state) {
            ConnectionState.PairingRejected -> FilledTonalButton(onClick = { controller.pairAgain() }) { Text("Pair again") }
            ConnectionState.ForwardingStuck -> {
                FilledTonalButton(onClick = controller::openLinuxTerminalAppInfo) { Text("App info") }
                TextButton(onClick = controller::connectGuest, enabled = !controller.busy) { Text("Reconnect") }
            }
            else -> if (facts.tabletGuest) {
                FilledTonalButton(onClick = controller::openLinuxTerminal) { Text("Open Linux Terminal") }
                TextButton(onClick = controller::connectGuest, enabled = !controller.busy) { Text("Reconnect") }
            } else {
                FilledTonalButton(onClick = controller::connectGuest, enabled = !controller.busy) { Text("Reconnect") }
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
    var confirmDelete by remember { mutableStateOf<ConnectionProfile?>(null) }
    AlertDialog(
        onDismissRequest = { controller.dialog = null },
        title = { Text("Connections") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (summary.state != ConnectionState.NotSetUp) StatusCard(controller, summary)
                if (controller.profiles.isNotEmpty()) {
                    listOf(
                        ConnectionKind.TabletGuest to "This tablet",
                        ConnectionKind.Computer to "Computers",
                    ).forEach { (kind, title) ->
                        val profiles = controller.profiles.filter { connectionKind(it.endpoint) == kind }
                        if (profiles.isNotEmpty()) {
                            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.primary)
                            Column {
                                profiles.forEach { profile ->
                                    ConnectionRow(controller, profile, onRequestDelete = { confirmDelete = profile })
                                }
                            }
                        }
                    }
                } else {
                    Text("Saved connections", style = MaterialTheme.typography.titleSmall, color = colors.primary)
                    Surface(color = colors.surfaceContainerLow, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("No saved connections yet", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Pair this tablet's Linux guest or a computer once. Saved routes, keys and preferences stay here and reconnect automatically.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (controller.managesDevices) PairedDevicesSection(controller, summary)
                Text("Add a connection", style = MaterialTheme.typography.titleSmall, color = colors.primary)
                Column {
                    AddRow(
                        Icons.Outlined.Explore,
                        "Guided setup (recommended)",
                        "Compare tablet guest, Wi-Fi, Tailscale or a tunnel step-by-step",
                        containerColor = colors.surfaceContainerHighest,
                    ) {
                        controller.dialog = WorkbenchDialog.ConnectionWizard
                    }
                    AddRow(Icons.Outlined.TabletAndroid, "This tablet's Linux guest", "Install the daemon in Linux Terminal, or pair it again") {
                        controller.beginGuestSetup()
                    }
                    AddRow(Icons.Outlined.Computer, "A computer on your network", "Scan a QR code or paste the connection string it prints") {
                        controller.dialog = WorkbenchDialog.ComputerPairing
                    }
                    AddRow(Icons.Outlined.Tune, "By hand", "A WebSocket URL and a token") {
                        controller.dialog = WorkbenchDialog.ConnectionEditor(null)
                    }
                }
                if (controller.profiles.isNotEmpty()) {
                    val animate = controller.settings.animations
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            troubleshoot = !troubleshoot
                            if (troubleshoot && controller.connectionCheck == null) controller.checkConnection()
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Troubleshoot", style = MaterialTheme.typography.titleSmall, color = colors.primary, modifier = Modifier.weight(1f))
                        ExpandChevron(troubleshoot, animate)
                    }
                    VerticalReveal(troubleshoot, animate) { Troubleshooting(controller) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { controller.dialog = null }) { Text("Close") } },
    )

    confirmDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remove ${profile.name}?") },
            text = {
                Text(
                    "Removes this saved connection and its encrypted token from this device. " +
                        "Files on ${profile.name} aren't touched, and any folders kept available offline stay readable.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = null
                        controller.deleteProfile(profile.id)
                    },
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
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
                    ConnectionState.Connected -> {
                        if (controller.daemonVersionNotice?.status == DaemonVersionStatus.Older) {
                            FilledTonalButton(onClick = controller::updateGuestDaemon) { Text("Update the daemon") }
                        }
                        FilledTonalButton(onClick = { controller.showOpenFolder() }) { Text("Browse folders…") }
                        TextButton(onClick = controller::requestWorkOffline, enabled = !controller.busy) { Text("Work offline") }
                    }
                    ConnectionState.OnDevice -> if (facts.workingOffline && controller.profiles.any { it.id == controller.profileId }) {
                        FilledTonalButton(onClick = controller::requestGuestConnection, enabled = !controller.busy) { Text("Reconnect") }
                    }
                    ConnectionState.Idle, ConnectionState.Failed -> FilledTonalButton(onClick = controller::requestGuestConnection, enabled = !controller.busy) { Text("Connect") }
                    ConnectionState.PairingRejected -> FilledTonalButton(onClick = { controller.pairAgain() }) { Text("Pair again") }
                    ConnectionState.ForwardingStuck -> {
                        FilledTonalButton(onClick = controller::openLinuxTerminalAppInfo) { Text("App info") }
                        TextButton(onClick = controller::connectGuest, enabled = !controller.busy) { Text("Reconnect") }
                    }
                    ConnectionState.Lost, ConnectionState.Unreachable -> {
                        if (facts.tabletGuest) FilledTonalButton(onClick = controller::openLinuxTerminal) { Text("Open Linux Terminal") }
                        TextButton(onClick = controller::connectGuest, enabled = !controller.busy) { Text("Reconnect") }
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
private fun ConnectionRow(controller: WorkbenchController, profile: ConnectionProfile, onRequestDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val current = profile.id == controller.profileId
    val tablet = isTabletGuestEndpoint(profile.endpoint)
    var menu by remember { mutableStateOf(false) }
    val order = controller.profiles.indexOfFirst { it.id == profile.id } + 1
    val formattedLastUsed = profile.lastUsedAt?.let {
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(it))
    }
    val rowCopy = connectionRowCopy(
        profile = profile,
        order = order,
        current = current,
        connected = controller.connected,
        busy = controller.busy,
        pairingRejected = controller.pairingRejected,
        connectionState = controller.connection.state,
        activeRouteEndpoint = controller.activeRouteEndpoint,
        formattedLastUsed = formattedLastUsed,
    )
    ListItem(
        headlineContent = { Text(profile.name) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(rowCopy.primary)
                rowCopy.lastUsed?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        },
        leadingContent = {
            Icon(
                if (tablet) Icons.Outlined.TabletAndroid else Icons.Outlined.Computer,
                contentDescription = null,
                tint = if (current) colors.primary else colors.onSurfaceVariant,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tooltip(if (profile.autoConnect) "Auto-connect enabled" else "Auto-connect disabled") {
                    Switch(
                        checked = profile.autoConnect,
                        onCheckedChange = { controller.setConnectionAutoConnect(profile.id, it) },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                Box {
                    Tooltip("More") { IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More for ${profile.name}") } }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (current && controller.connected) {
                            DropdownMenuItem(
                                text = { Text("Work offline") },
                                leadingIcon = { Icon(Icons.Outlined.CloudOff, contentDescription = null) },
                                onClick = { menu = false; controller.requestWorkOffline() },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text(if (current) "Reconnect" else "Switch to ${profile.name}") },
                                leadingIcon = { Icon(Icons.Outlined.Cloud, contentDescription = null) },
                                enabled = !controller.busy,
                                onClick = { menu = false; controller.switchConnection(profile) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Browse folders…") },
                            leadingIcon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                            enabled = !controller.busy,
                            onClick = { menu = false; controller.openHostWorkspacePicker(profile) },
                        )
                        DropdownMenuItem(
                            text = { Text("Move up in auto-connect order") },
                            leadingIcon = { Icon(Icons.Outlined.ArrowUpward, contentDescription = null) },
                            enabled = order > 1,
                            onClick = { menu = false; controller.moveConnection(profile.id, -1) },
                        )
                        DropdownMenuItem(
                            text = { Text("Move down in auto-connect order") },
                            leadingIcon = { Icon(Icons.Outlined.ArrowDownward, contentDescription = null) },
                            enabled = order in 1 until controller.profiles.size,
                            onClick = { menu = false; controller.moveConnection(profile.id, 1) },
                        )
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
                            text = { Text("Delete…") },
                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                            onClick = { menu = false; onRequestDelete() },
                        )
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = if (current) colors.surfaceContainerHighest else colors.surfaceContainerHigh),
        // A tap switches to it, or connects it again when it's the current one and away.
        modifier = Modifier.clickable(enabled = !(current && controller.connected) && !controller.busy) { controller.switchConnection(profile) },
    )
}

@Composable
private fun AddRow(
    icon: ImageVector,
    title: String,
    summary: String,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = containerColor),
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
            check.daemonAnswers == true -> CheckRow(true, "The daemon answers at $target")
            else -> {
                val openTailscale = check.route == ConnectionRoute.Tailscale && check.vpnActive == false && check.tailscaleInstalled
                CheckRow(
                    false,
                    troubleshootDiagnosis(check, facts.tabletGuest),
                    action = if (openTailscale) ({ TextButton(onClick = controller::openTailscale) { Text("Open Tailscale") } }) else null,
                )
            }
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

/** A connection's name, WebSocket URL(s), token and optional Cloudflare Access token (G22, G32, G37). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConnectionEditorDialog(controller: WorkbenchController, editor: WorkbenchDialog.ConnectionEditor) {
    val profile = controller.profiles.firstOrNull { it.id == editor.profileId }
    var name by remember(editor) { mutableStateOf(profile?.name.orEmpty()) }
    var url by remember(editor) { mutableStateOf(profile?.endpoint ?: GUEST_DAEMON_ENDPOINT) }
    var extraUrlsText by remember(editor) { mutableStateOf(profile?.extraRoutes?.joinToString("\n") { it.endpoint }.orEmpty()) }
    var preferredRoute by remember(editor) { mutableStateOf(profile?.preferredRoute ?: RoutePreference.Auto) }
    var token by remember(editor) { mutableStateOf("") }
    var clearPin by remember(editor) { mutableStateOf(false) }
    var accessClientId by remember(editor) { mutableStateOf("") }
    var accessClientSecret by remember(editor) { mutableStateOf("") }
    var clearAccess by remember(editor) { mutableStateOf(false) }

    val pinnedFingerprint = profile?.fingerprint?.takeIf { !clearPin && url.trim() == profile.endpoint }
    val hasToken = controller.connectionHasToken(editor.profileId)
    val urlProblem = connectionUrlProblem(url)
    val extraUrlLines = extraUrlsText.lines().map(String::trim).filter(String::isNotEmpty)
    val extraProblem = extraUrlLines.firstNotNullOfOrNull(::connectionUrlProblem)
    val parsedExtraRoutes = extraUrlLines.map { ep ->
        val existing = profile?.extraRoutes?.firstOrNull { it.endpoint == ep }
        val fp = when {
            existing != null && !clearPin -> existing.fingerprint
            connectionRoute(ep) == ConnectionRoute.Tunnel -> null
            ep.startsWith("wss://", ignoreCase = true) -> pinnedFingerprint
            else -> null
        }
        ProfileRoute(ep, fp)
    }
    val valid = urlProblem == null && extraProblem == null && (hasToken || token.isNotBlank()) &&
        ((accessClientId.isBlank() && accessClientSecret.isBlank()) || (accessClientId.isNotBlank() && accessClientSecret.isNotBlank()))

    fun save(connect: Boolean) {
        controller.saveConnection(
            id = editor.profileId,
            name = name,
            url = url,
            newToken = token,
            connect = connect,
            clearFingerprint = clearPin,
            extraRoutes = parsedExtraRoutes,
            preferredRoute = preferredRoute,
            accessClientId = accessClientId.takeIf { it.isNotBlank() },
            accessClientSecret = accessClientSecret.takeIf { it.isNotBlank() },
            clearAccessToken = clearAccess,
        )
    }

    AlertDialog(
        onDismissRequest = { controller.dialog = WorkbenchDialog.Connect },
        title = { Text(if (profile == null) "New connection" else "Edit ${profile.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Saved on this device; tokens are encrypted with Android's Keystore. Pairing (Add a connection) fills these in for you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    url, { url = it.trim() },
                    label = { Text("WebSocket URL") },
                    placeholder = { Text(GUEST_DAEMON_ENDPOINT) },
                    isError = url.isNotBlank() && urlProblem != null,
                    supportingText = urlProblem?.takeIf { url.isNotBlank() }?.let { problem -> { Text(problem) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (url.trim().startsWith("wss://", ignoreCase = true)) {
                    if (pinnedFingerprint != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Pinned to certificate ${pinnedFingerprint.take(12)}…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { clearPin = true }) { Text("Use public TLS") }
                        }
                    } else {
                        Text(
                            "Verified by Android's trusted CAs (Cloudflare Tunnel or a public certificate).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedTextField(
                    extraUrlsText, { extraUrlsText = it },
                    label = { Text("Fallback routes (one URL per line, optional)") },
                    placeholder = { Text("wss://100.x.y.z:19870/ws\nwss://tunnel.example.com/ws") },
                    isError = extraProblem != null,
                    supportingText = extraProblem?.let { problem -> { Text(problem) } },
                    minLines = 1,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (extraUrlLines.isNotEmpty() || (profile?.allRoutes()?.size ?: 1) > 1) {
                    Text("Preferred route", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RoutePreference.entries.forEach { option ->
                            val short = when (option) {
                                RoutePreference.Auto -> "Auto"
                                RoutePreference.Wifi -> "Wi-Fi"
                                RoutePreference.Tailscale -> "Tailscale"
                                RoutePreference.Tunnel -> "Tunnel"
                            }
                            FilterChip(
                                selected = preferredRoute == option,
                                onClick = { preferredRoute = option },
                                label = { Text(short) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    token, { token = it.trim() },
                    label = { Text(if (hasToken) "Token (blank keeps the saved one)" else "Token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
                Text("Cloudflare Access service token (optional)", style = MaterialTheme.typography.labelLarge)
                if (profile?.hasAccessToken == true && !clearAccess) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Access token saved (encrypted); leave blank to keep.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { clearAccess = true }) { Text("Clear") }
                    }
                }
                OutlinedTextField(
                    accessClientId, { accessClientId = it.trim(); if (it.isNotBlank()) clearAccess = false },
                    label = { Text("CF-Access-Client-Id") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    accessClientSecret, { accessClientSecret = it.trim(); if (it.isNotBlank()) clearAccess = false },
                    label = { Text("CF-Access-Client-Secret") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (valid) save(connect = true) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { save(connect = true) }, enabled = valid && !controller.busy) {
                Text("Save and connect")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { save(connect = false) }, enabled = valid) { Text("Save") }
                TextButton(onClick = { controller.dialog = WorkbenchDialog.Connect }) { Text("Cancel") }
            }
        },
    )
}

/** Pairing with a computer from its connection string or QR code (G22, G36). */
@Composable
internal fun ComputerPairingDialog(controller: WorkbenchController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var connectionString by remember { mutableStateOf("") }
    var accessClientId by remember { mutableStateOf("") }
    var accessClientSecret by remember { mutableStateOf("") }
    var scannerOpen by remember { mutableStateOf(false) }
    var clipboardOffer by remember { mutableStateOf(readClipboardConnectionString(context)) }
    val colors = MaterialTheme.colorScheme

    androidx.compose.runtime.DisposableEffect(lifecycleOwner, context) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && connectionString.isBlank()) {
                clipboardOffer = readClipboardConnectionString(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
                    "Update a former QuireCode daemon first, then run `nookra-daemon pair --all` " +
                        "(or `--remote <address>:19870` / `--tunnel <hostname>`) on the computer. " +
                        "Scan the QR code or paste the `avf://` string it prints.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                PairingInputSection(
                    controller = controller,
                    connectionString = connectionString,
                    onConnectionStringChange = { connectionString = it },
                    clipboardOffer = clipboardOffer,
                    onDismissClipboardOffer = { clipboardOffer = null },
                    onOpenScanner = { scannerOpen = true },
                    accessClientId = accessClientId,
                    onAccessClientIdChange = { accessClientId = it },
                    accessClientSecret = accessClientSecret,
                    onAccessClientSecretChange = { accessClientSecret = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { controller.pairWithConnectionString(connectionString, accessClientId, accessClientSecret) },
                enabled = !controller.busy && extractConnectionStringFromText(connectionString) != null,
            ) { Text(if (controller.busy) "Pairing…" else "Pair and connect") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { controller.dialog = WorkbenchDialog.ConnectionWizard }) { Text("Guided setup") }
                TextButton(onClick = { controller.dialog = WorkbenchDialog.Connect }) { Text("Back") }
            }
        },
    )

    if (scannerOpen) {
        QrScannerDialog(
            onScanned = { scanned ->
                scannerOpen = false
                connectionString = scanned
                controller.pairWithConnectionString(scanned, accessClientId, accessClientSecret)
            },
            onDismiss = { scannerOpen = false },
        )
    }
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
    var confirm by remember { mutableStateOf<io.github.kinteshi.nookraide.transport.PairedDevice?>(null) }
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
        val animate = controller.settings.animations
        Row(
            Modifier.fillMaxWidth().clickable { showLog = !showLog }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Recent connections", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            ExpandChevron(showLog, animate)
        }
        VerticalReveal(showLog, animate) {
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
