package io.github.kinteshi.quirecode.workbench

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.kinteshi.quirecode.transport.parseConnectionString

/**
 * Guided "Add a connection" wizard (G35): asks where code runs and where the tablet connects from,
 * recommends the setup with why, checks Tailscale state, offers copy/share commands, and pairs via clipboard or QR (G36).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConnectionWizardDialog(controller: WorkbenchController) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val colors = MaterialTheme.colorScheme

    var workTarget by remember { mutableStateOf(WorkTarget.Computer) }
    var scope by remember { mutableStateOf(UsageScope.Anywhere) }
    var selectedOption by remember { mutableStateOf(RemoteSetupOption.Tailscale) }
    var os by remember { mutableStateOf(ComputerOs.Linux) }
    var connectionString by remember { mutableStateOf("") }
    var accessClientId by remember { mutableStateOf("") }
    var accessClientSecret by remember { mutableStateOf("") }
    var scannerOpen by remember { mutableStateOf(false) }
    var terminalReady by remember { mutableStateOf(controller.linuxTerminalAvailable()) }
    var tailscaleState by remember { mutableStateOf(readTailscaleGuideState(context, controller)) }
    var clipboardOffer by remember { mutableStateOf(readClipboardConnectionString(context)) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                terminalReady = controller.linuxTerminalAvailable()
                tailscaleState = readTailscaleGuideState(context, controller)
                if (connectionString.isBlank()) {
                    clipboardOffer = readClipboardConnectionString(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(scope) {
        val allowed = guidedOptionsFor(scope)
        if (selectedOption !in allowed) selectedOption = allowed.first()
    }

    AlertDialog(
        onDismissRequest = { controller.dialog = WorkbenchDialog.Connect },
        title = { Text("Guided connection setup") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("1. Where should your code run?", style = MaterialTheme.typography.titleSmall, color = colors.primary)
                WorkTarget.entries.forEach { target ->
                    val chosen = workTarget == target
                    Surface(
                        color = if (chosen) colors.secondaryContainer else colors.surfaceContainerLow,
                        contentColor = if (chosen) colors.onSecondaryContainer else colors.onSurface,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().clickable { workTarget = target },
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(if (target == WorkTarget.TabletGuest) Icons.Outlined.TabletAndroid else Icons.Outlined.Computer, contentDescription = null)
                            Column(Modifier.weight(1f)) {
                                Text(target.title, style = MaterialTheme.typography.titleSmall)
                                Text(target.subtitle, style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(
                                if (chosen) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                                contentDescription = null,
                            )
                        }
                    }
                }

                if (workTarget == WorkTarget.TabletGuest) {
                    Surface(color = colors.surfaceContainerLow, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(
                                if (terminalReady) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber,
                                contentDescription = null,
                                tint = if (terminalReady) colors.primary else colors.secondary,
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    if (terminalReady) "Linux Terminal is ready on this tablet" else "Linux Terminal isn't enabled yet",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    if (terminalReady) {
                                        "Everything stays on the tablet with no Wi-Fi, Tailscale, or external computer needed."
                                    } else {
                                        "Turn on Linux development environment in Developer options, open Linux Terminal once to finish its Debian install, then come back."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                            if (!terminalReady) {
                                TextButton(onClick = controller::openDeveloperOptions) { Text("Developer options") }
                            }
                        }
                    }
                } else {
                    Text("2. Where will you connect from?", style = MaterialTheme.typography.titleSmall, color = colors.primary)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        UsageScope.entries.forEachIndexed { index, item ->
                            SegmentedButton(
                                selected = scope == item,
                                onClick = { scope = item },
                                shape = SegmentedButtonDefaults.itemShape(index, UsageScope.entries.size),
                                label = { Text(item.title, maxLines = 1) },
                            )
                        }
                    }

                    val options = guidedOptionsFor(scope)
                    Text(
                        if (options.size == 1) "Recommended setup" else "Choose how to reach your computer",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.primary,
                    )
                    options.forEach { option ->
                        val chosen = selectedOption == option
                        Surface(
                            color = if (chosen) colors.surfaceContainerHighest else colors.surfaceContainerLow,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().clickable { selectedOption = option },
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(
                                        if (chosen) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = if (chosen) colors.primary else colors.onSurfaceVariant,
                                    )
                                    Text(option.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                }
                                Text(option.why, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                option.warning?.let { warn ->
                                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(Icons.Outlined.WarningAmber, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.secondary)
                                        Text(warn, style = MaterialTheme.typography.bodySmall, color = colors.secondary)
                                    }
                                }
                            }
                        }
                    }

                    if (selectedOption == RemoteSetupOption.Tailscale) {
                        TailscaleStatusCard(
                            state = tailscaleState,
                            onInstall = { openTailscaleInstall(context) },
                            onOpen = controller::openTailscale,
                        )
                    }

                    Text("3. Run on your computer", style = MaterialTheme.typography.titleSmall, color = colors.primary)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ComputerOs.entries.forEachIndexed { index, item ->
                            SegmentedButton(
                                selected = os == item,
                                onClick = { os = item },
                                shape = SegmentedButtonDefaults.itemShape(index, ComputerOs.entries.size),
                                label = { Text(item.label) },
                            )
                        }
                    }
                    val commands = guidedCommands(selectedOption, os)
                    CommandCard(
                        title = "First-time install",
                        command = commands.setupStep,
                        note = commands.note,
                        onCopy = { copyGuideCommand(controller, context, "install command", commands.setupStep) },
                        onShare = { shareGuideCommands(context, "QuireCode host install", commands.setupStep) },
                    )
                    CommandCard(
                        title = "New pairing code / QR later",
                        command = commands.pairCommand,
                        note = null,
                        onCopy = { copyGuideCommand(controller, context, "pair command", commands.pairCommand) },
                        onShare = { shareGuideCommands(context, "QuireCode pair command", commands.pairCommand) },
                    )

                    Text("4. Pair with the printed QR code or link", style = MaterialTheme.typography.titleSmall, color = colors.primary)
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
            }
        },
        confirmButton = {
            if (workTarget == WorkTarget.TabletGuest) {
                FilledTonalButton(onClick = controller::beginGuestSetup) { Text("Set up Linux guest") }
            } else {
                TextButton(
                    onClick = { controller.pairWithConnectionString(connectionString, accessClientId, accessClientSecret) },
                    enabled = !controller.busy && extractConnectionStringFromText(connectionString) != null,
                ) { Text(if (controller.busy) "Pairing…" else "Pair and connect") }
            }
        },
        dismissButton = {
            TextButton(onClick = { controller.dialog = WorkbenchDialog.Connect }) { Text("Back") }
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

@Composable
private fun TailscaleStatusCard(state: TailscaleGuideState, onInstall: () -> Unit, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainerLow, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.installed && state.vpnActive) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = colors.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    when {
                        !state.installed -> "Tailscale isn't installed on this tablet"
                        !state.vpnActive -> "Tailscale is installed, VPN is off"
                        else -> "Tailscale VPN is on"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    when {
                        !state.installed -> "Install Tailscale and sign in to the same account as your computer."
                        !state.vpnActive -> "Turn on the VPN in Tailscale before pairing."
                        else -> "This tablet can reach your tailnet."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            when {
                !state.installed -> TextButton(onClick = onInstall) { Text("Get Tailscale") }
                !state.vpnActive -> TextButton(onClick = onOpen) { Text("Open Tailscale") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommandCard(
    title: String,
    command: String,
    note: String?,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainerLow, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(command, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCopy) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(" Copy", modifier = Modifier.padding(start = 4.dp))
                }
                TextButton(onClick = onShare) {
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(" Share to computer", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

/** Shared clipboard offer, QR scan trigger, and `avf://` text input used by both guided setup and computer pairing (G36). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PairingInputSection(
    controller: WorkbenchController,
    connectionString: String,
    onConnectionStringChange: (String) -> Unit,
    clipboardOffer: String?,
    onDismissClipboardOffer: () -> Unit,
    onOpenScanner: () -> Unit,
    accessClientId: String = "",
    onAccessClientIdChange: (String) -> Unit = {},
    accessClientSecret: String = "",
    onAccessClientSecretChange: (String) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    var showAccess by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        clipboardOffer?.let { offer ->
            val parsed = parseConnectionString(offer)
            Surface(color = colors.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Connection string found on clipboard", style = MaterialTheme.typography.labelLarge, color = colors.onSecondaryContainer)
                    Text(
                        parsed?.let {
                            val extra = if (it.alternateRoutes.isNotEmpty()) " + ${it.alternateRoutes.size} route(s)" else ""
                            "${it.host}:${it.port}$extra (code ${it.code})"
                        } ?: offer,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSecondaryContainer,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = {
                            onConnectionStringChange(offer)
                            onDismissClipboardOffer()
                            controller.pairWithConnectionString(offer, accessClientId, accessClientSecret)
                        }, enabled = !controller.busy) {
                            Text("Pair from clipboard")
                        }
                        TextButton(onClick = {
                            onConnectionStringChange(offer)
                            onDismissClipboardOffer()
                        }) { Text("Fill") }
                        TextButton(onClick = onDismissClipboardOffer) { Text("Dismiss") }
                    }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onOpenScanner, enabled = !controller.busy) {
                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Scan QR code", modifier = Modifier.padding(start = 6.dp))
            }
            OutlinedButton(onClick = {
                readClipboardConnectionString(context)?.let { found ->
                    onConnectionStringChange(found)
                    onDismissClipboardOffer()
                } ?: run {
                    controller.status = "Clipboard doesn't hold an avf:// connection string"
                }
            }, enabled = !controller.busy) {
                Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Paste link", modifier = Modifier.padding(start = 6.dp))
            }
        }
        OutlinedTextField(
            value = connectionString,
            onValueChange = { onConnectionStringChange(it.trim()) },
            label = { Text("Connection string") },
            placeholder = { Text("avf://192.168.1.10:19870?fp=…&code=…") },
            singleLine = true,
            isError = controller.pairingError != null,
            supportingText = controller.pairingError?.let { error -> { Text(error) } },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { controller.pairWithConnectionString(connectionString, accessClientId, accessClientSecret) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth().clickable { showAccess = !showAccess }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Cloudflare Access service token (optional)", style = MaterialTheme.typography.labelLarge, color = colors.primary, modifier = Modifier.weight(1f))
            ExpandChevron(showAccess, controller.settings.animations)
        }
        VerticalReveal(showAccess, controller.settings.animations) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Needed only when pairing a tunnel protected by Cloudflare Access (`CF-Access-Client-Id` & `Secret`, stored encrypted).",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = accessClientId,
                    onValueChange = { onAccessClientIdChange(it.trim()) },
                    label = { Text("CF-Access-Client-Id") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = accessClientSecret,
                    onValueChange = { onAccessClientSecretChange(it.trim()) },
                    label = { Text("CF-Access-Client-Secret") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
