package io.github.kinteshi.quirecode.workbench

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.outlined.Add
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.platform.LocalView
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.FilledTonalButton

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun NativeWorkbench() {
    val context = LocalContext.current.applicationContext
    val controller = remember { WorkbenchController(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var assetMode by remember { mutableStateOf("vsix") }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        controller.connectGuest()
    }
    val assetPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { controller.installAssetPack(it, assetMode) }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(controller::openDeviceFile)
    }
    // Files in and out (group 9): save a file or a zip, pick a device folder (save into, import from), pick files to import.
    val saveFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"), controller::onSaveDocumentPicked)
    val saveZipPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), controller::onSaveDocumentPicked)
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(), controller::onTreePicked)
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), controller::onImportFilesPicked)
    DisposableEffect(lifecycleOwner) {
        controller.requestNotificationPermission = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        controller.pickAssetPack = { mode ->
            assetMode = mode
            assetPicker.launch(if (mode == "theme") arrayOf("application/json", "text/*", "*/*") else arrayOf("*/*"))
        }
        controller.pickLocalFile = { filePicker.launch(arrayOf("*/*")) }
        controller.devicePickers = DevicePickers(
            createFile = saveFilePicker::launch,
            createZip = saveZipPicker::launch,
            openTree = { treePicker.launch(null) },
            openFiles = { importPicker.launch(arrayOf("*/*")) },
        )
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    controller.persistDrafts()
                    controller.onAppVisibilityChanged(false)
                }
                Lifecycle.Event.ON_START -> controller.onAppVisibilityChanged(true)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        KeyRouter.handler = controller::routeHardwareKey
        KeyRouter.bypassesIme = controller::bypassesIme
        IntentRouter.handler = controller::handleIntent
        onDispose {
            KeyRouter.handler = null
            KeyRouter.bypassesIme = null
            IntentRouter.handler = null
            controller.pickLocalFile = null
            controller.devicePickers = null
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.dispose()
        }
    }
    val tooltips = remember(controller.settings.tooltips) { Tooltips(controller.settings.tooltips, controller::keybindingFor) }
    AvfTheme(controller.settings, tooltips) {
        BackHandler(enabled = controller.dialog != null || controller.settingsVisible) {
            if (controller.dialog != null) controller.dialog = null else controller.settingsVisible = false
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
            val activity = LocalContext.current as? android.app.Activity
            val view = LocalView.current
            DisposableEffect(view) {
                controller.hapticView = view
                onDispose { controller.hapticView = null }
            }
            DisposableEffect(activity) {
                KeyBar.inject = { event -> activity?.dispatchKeyEvent(event) ?: false }
                onDispose { KeyBar.inject = null }
            }
            // G23: the on-screen keyboard counts when it's taller than a hardware keyboard's suggestion strip.
            val keyboardUp = with(androidx.compose.ui.platform.LocalDensity.current) { WindowInsets.ime.getBottom(this).toDp() } > KEYBOARD_MIN_HEIGHT
            LaunchedEffect(keyboardUp) { controller.onKeyboard(keyboardUp) }
            DisposableEffect(controller) {
                TouchpadPinch.handler = controller::onTouchpadPinch
                onDispose { TouchpadPinch.handler = null }
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .imePadding(),
            ) {
                val anim = controller.settings.animations
                Box(Modifier.weight(1f)) {
                    Crossfade(
                        targetState = controller.settingsVisible,
                        animationSpec = tween(if (anim) 150 else 0),
                        label = "settings",
                    ) { showSettings ->
                        if (showSettings) SettingsScreen(controller) else WorkbenchLayout(controller)
                    }
                    controller.explorerUndo?.let { undo ->
                        Snackbar(
                            action = {
                                TextButton(
                                    onClick = controller::undoExplorerChange,
                                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary),
                                ) { Text("Undo") }
                            },
                            dismissAction = {
                                Tooltip("Dismiss") { IconButton(onClick = controller::dismissExplorerUndo) { Icon(Icons.Outlined.Close, contentDescription = "Dismiss") } }
                            },
                            modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 40.dp).widthIn(max = 560.dp),
                        ) { Text(undo.message, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
                if (controller.settings.keyBar && WindowInsets.isImeVisible) KeyBarRow(onLatch = { controller.haptic(android.view.HapticFeedbackConstants.KEYBOARD_TAP) })
            }
        }
        WorkbenchDialogHost(controller)
        PlotViewerDialog(controller)
    }
}

@Composable
private fun WorkbenchLayout(controller: WorkbenchController) {
    val anim = controller.settings.animations
    val lastSidebar = remember { mutableStateOf(SidebarView.Explorer) }
    controller.sidebar?.let { lastSidebar.value = it }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 600.dp
        val roomy = maxWidth >= 1200.dp
        val windowWidth = maxWidth.value
        val windowHeight = maxHeight.value
        Column(Modifier.fillMaxSize()) {
            TopBar(controller, expanded)
            Column(Modifier.fillMaxWidth().animateContentSize(tween(if (anim) 150 else 0))) {
                ConnectionBanner(controller)
                TransferBanner(controller)
                DaemonVersionBanner(controller)
                GuestDiskBanner(controller)
            }
            if (expanded) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    WorkbenchRail(controller)
                    // Panes keep the size they were dragged to, and always leave the editor some room.
                    val chatOpen = controller.chatVisible
                    val sidebarOpen = controller.sidebar != null
                    val limit = (windowWidth - 80f - MIN_EDITOR_WIDTH).coerceAtLeast(MIN_SIDE_PANE_WIDTH)
                    val chatWidth = (controller.chatWidth ?: if (roomy) 420f else 340f).coerceIn(MIN_SIDE_PANE_WIDTH, limit)
                    val sidebarWidth = (controller.sidebarWidth ?: if (roomy) 300f else 260f)
                        .coerceIn(MIN_SIDE_PANE_WIDTH, (limit - if (chatOpen) chatWidth else 0f).coerceAtLeast(MIN_SIDE_PANE_WIDTH))
                    AnimatedVisibility(
                        visible = sidebarOpen,
                        enter = fadeIn(tween(if (anim) 140 else 0)) + expandHorizontally(tween(if (anim) 160 else 0)),
                        exit = fadeOut(tween(if (anim) 120 else 0)) + shrinkHorizontally(tween(if (anim) 150 else 0)),
                    ) {
                        Row(Modifier.fillMaxHeight()) {
                            Box(Modifier.width(sidebarWidth.dp).fillMaxHeight().paneShape()) {
                                Crossfade(
                                    targetState = lastSidebar.value,
                                    animationSpec = tween(if (anim) 120 else 0),
                                    label = "sidebar",
                                ) { view ->
                                    SidebarContent(controller, view, Modifier.fillMaxSize())
                                }
                            }
                            PaneGutter(
                                onDrag = { delta -> controller.sidebarWidth = (sidebarWidth + delta).coerceIn(MIN_SIDE_PANE_WIDTH, limit - if (chatOpen) chatWidth else 0f) },
                                onStopped = controller::savePaneSizes,
                            )
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Box(Modifier.weight(1f).fillMaxWidth().paneShape()) { EditorArea(controller) }
                        // The panel leaves the editor its tabs and a few lines.
                        VerticalReveal(
                            visible = controller.panelShown,
                            animate = anim,
                            expandFrom = Alignment.Bottom,
                        ) {
                            PanelArea(controller, Modifier.paneShape(), maxHeight = windowHeight - MIN_EDITOR_HEIGHT)
                        }
                    }
                    AnimatedVisibility(
                        visible = chatOpen,
                        enter = fadeIn(tween(if (anim) 140 else 0)) + expandHorizontally(tween(if (anim) 160 else 0), expandFrom = Alignment.End),
                        exit = fadeOut(tween(if (anim) 120 else 0)) + shrinkHorizontally(tween(if (anim) 150 else 0), shrinkTowards = Alignment.End),
                    ) {
                        Row(Modifier.fillMaxHeight()) {
                            PaneGutter(
                                onDrag = { delta -> controller.chatWidth = (chatWidth - delta).coerceIn(MIN_SIDE_PANE_WIDTH, limit - if (sidebarOpen) sidebarWidth else 0f) },
                                onStopped = controller::savePaneSizes,
                            )
                            CopilotChatPane(
                                controller,
                                Modifier.width(chatWidth.dp).padding(end = 8.dp).paneShape(),
                                onClose = { controller.chatVisible = false },
                            )
                        }
                    }
                    val endGap by animateDpAsState(if (chatOpen) 0.dp else 8.dp, tween(if (anim) 160 else 0), label = "endGap")
                    if (endGap > 0.dp) Spacer(Modifier.width(endGap))
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) { CompactContent(controller) }
                CompactNavigation(controller)
            }
            StatusBar(controller, expanded)
        }
    }
}

private const val MIN_SIDE_PANE_WIDTH = 200f
/** Samsung's strip of suggestions over a hardware keyboard is about 50 dp; an on-screen keyboard is well over 160. */
private val KEYBOARD_MIN_HEIGHT = 160.dp
private const val MIN_EDITOR_WIDTH = 360f
private const val MIN_EDITOR_HEIGHT = 300f

/** Content panes sit in rounded cards on the container colour, like other Material 3 apps. */
private fun Modifier.paneShape(): Modifier = padding(bottom = 4.dp).clip(RoundedCornerShape(16.dp))

@Composable
private fun TopBar(controller: WorkbenchController, expanded: Boolean) {
    val colors = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (expanded) {
            Tooltip("Toggle side bar", "workbench.action.toggleSidebarVisibility") { IconButton(onClick = { controller.runCommand("workbench.action.toggleSidebarVisibility") }) {
                Icon(Icons.Outlined.Menu, contentDescription = "Toggle side bar")
            } }
        }
        // The workspace name opens Open Folder (switch to another or a recent one).
        Column(
            Modifier.widthIn(max = 220.dp).clip(RoundedCornerShape(8.dp))
                .clickable { if (controller.connected) controller.showOpenFolder() else controller.openConnect() }
                .padding(horizontal = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("QuireCode", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                DevBadge()
            }
            Text(
                controller.workspaceRoot?.let(controller::displayName) ?: "No workspace",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Surface(
            onClick = { controller.runCommand("workbench.action.quickOpen") },
            color = colors.surfaceContainerHighest,
            shape = CircleShape,
            modifier = Modifier.weight(1f).height(40.dp).padding(horizontal = 8.dp),
        ) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Text(
                    if (expanded) "Search files  ·  Ctrl+P" else "Search files",
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 10.dp),
                    maxLines = 1,
                )
            }
        }
        ConnectionChip(controller)
        Box {
            Tooltip("More") { IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More") } }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MenuItem("Command palette", "Ctrl+Shift+P", Icons.Outlined.Widgets) { menu = false; controller.runCommand("workbench.action.showCommands") }
                MenuItem("Open folder…", "Ctrl+K Ctrl+O", Icons.Outlined.FolderOpen) { menu = false; controller.showOpenFolder() }
                MenuItem("Open device folder…", "", Icons.Outlined.PhoneAndroid) { menu = false; controller.beginOpenDeviceFolder() }
                MenuItem("New file…", "", Icons.Outlined.NoteAdd) { menu = false; controller.beginNewEntry(folder = false) }
                MenuItem("New untitled file", "Ctrl+N", Icons.Outlined.NoteAdd) { menu = false; controller.newUntitledFile() }
                MenuItem("New terminal", "Ctrl+Shift+`", Icons.Outlined.Terminal) { menu = false; controller.newTerminal() }
                MenuItem("Keyboard shortcuts", "Ctrl+K Ctrl+S", Icons.Outlined.Keyboard) { menu = false; controller.dialog = WorkbenchDialog.Keybindings }
                MenuItem("Settings", "Ctrl+,", Icons.Outlined.Settings) { menu = false; controller.settingsVisible = true }
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, shortcut: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        trailingIcon = { Text(shortcut, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        onClick = onClick,
    )
}

@Composable
private fun WorkbenchRail(controller: WorkbenchController) {
    NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        RailItem(Icons.Outlined.Folder, "Explorer", "workbench.view.explorer", controller.sidebar == SidebarView.Explorer) {
            controller.runCommand("workbench.view.explorer")
        }
        RailItem(Icons.Outlined.Search, "Search", "workbench.view.search", controller.sidebar == SidebarView.Search) {
            controller.runCommand("workbench.view.search")
        }
        NavigationRailItem(
            selected = controller.sidebar == SidebarView.SourceControl,
            onClick = { controller.runCommand("workbench.view.scm") },
            icon = {
                val changes = controller.gitStatus?.entries?.size ?: 0
                Tooltip("Source Control", "workbench.view.scm") {
                    BadgedBox(badge = { if (changes > 0) Badge { Text("$changes") } }) {
                        Icon(Icons.AutoMirrored.Outlined.CallSplit, contentDescription = null)
                    }
                }
            },
            label = { Text("Git") },
        )
        RailItem(Icons.Outlined.AccountTree, "Outline", "outline.focus", controller.sidebar == SidebarView.Outline) {
            controller.runCommand("outline.focus")
        }
        NavigationRailItem(
            selected = controller.sidebar == SidebarView.Problems,
            onClick = { controller.sidebar = if (controller.sidebar == SidebarView.Problems) null else SidebarView.Problems },
            icon = {
                Tooltip("Problems", "workbench.actions.view.problems") {
                    BadgedBox(badge = { if (controller.diagnostics.isNotEmpty()) Badge { Text("${controller.diagnostics.size}") } }) {
                        Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
                    }
                }
            },
            label = { Text("Problems") },
        )
        RailItem(Icons.Outlined.Terminal, "Terminal", "workbench.action.terminal.toggleTerminal", controller.panelShown && controller.panelTab == PanelTab.Terminal) {
            controller.runCommand("workbench.action.terminal.toggleTerminal")
        }
        RailItem(Icons.Outlined.AutoAwesome, "Chat", "workbench.action.chat.open", controller.chatVisible) { controller.runCommand("workbench.action.chat.open") }
        Spacer(Modifier.weight(1f))
        RailItem(Icons.Outlined.Settings, "Settings", "workbench.action.openSettings", false) { controller.settingsVisible = true }
    }
}

@Composable
private fun RailItem(icon: ImageVector, label: String, command: String?, selected: Boolean, onClick: () -> Unit) {
    NavigationRailItem(
        selected = selected,
        onClick = onClick,
        icon = { Tooltip(label, command) { Icon(icon, contentDescription = null) } },
        label = { Text(label) },
    )
}

@Composable
private fun CompactContent(controller: WorkbenchController) {
    Crossfade(
        targetState = controller.compactDestination,
        animationSpec = tween(if (controller.settings.animations) 140 else 0),
        label = "compact",
    ) { destination ->
        when (destination) {
            CompactDestination.Files -> SidebarContent(controller, SidebarView.Explorer)
            CompactDestination.Editor -> EditorArea(controller)
            // The bottom panel's tabs (Problems lives under More) and "+", which the wide layout has in its panel.
            CompactDestination.Terminal -> Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    (listOf(PanelTab.Terminal, PanelTab.Debug, PanelTab.Output, PanelTab.Ports, PanelTab.Variables, PanelTab.Interactive) +
                        listOfNotNull(PanelTab.Docs.takeIf { controller.expandedDocs != null })).forEach { tab ->
                        PanelTabChip(controller, tab)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (controller.panelTab) {
                        PanelTab.Debug -> DebugPanel(controller)
                        PanelTab.Output -> OutputLog(controller)
                        PanelTab.Ports -> PortsView(controller)
                        PanelTab.Variables -> VariablesView(controller)
                        PanelTab.Interactive -> InteractiveView(controller)
                        PanelTab.Docs -> DocsView(controller)
                        else -> TerminalView(controller)
                    }
                }
            }
            CompactDestination.Chat -> CopilotChatPane(controller)
            CompactDestination.More -> {
                var view by remember { mutableStateOf(SidebarView.Problems) }
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(SidebarView.Search, SidebarView.SourceControl, SidebarView.Problems, SidebarView.Outline, SidebarView.References, SidebarView.CallHierarchy).forEach {
                            FilterChip(selected = view == it, onClick = { view = it }, label = { Text(it.title) })
                        }
                        FilterChip(selected = false, onClick = { controller.settingsVisible = true }, label = { Text("Settings") })
                    }
                    SidebarContent(controller, view, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CompactNavigation(controller: WorkbenchController) {
    NavigationBar {
        listOf(
            CompactDestination.Files to Icons.Outlined.Folder,
            CompactDestination.Editor to Icons.Outlined.Code,
            CompactDestination.Terminal to Icons.Outlined.Terminal,
            CompactDestination.Chat to Icons.Outlined.AutoAwesome,
            CompactDestination.More to Icons.AutoMirrored.Outlined.Article,
        ).forEach { (destination, icon) ->
            NavigationBarItem(
                selected = controller.compactDestination == destination,
                onClick = {
                    controller.compactDestination = destination
                    if (destination == CompactDestination.Terminal && controller.terminals.isEmpty()) controller.newTerminal()
                },
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(destination.title) },
            )
        }
    }
}

@Composable
private fun StatusBar(controller: WorkbenchController, expanded: Boolean) {
    controller.documentRevision
    val colors = MaterialTheme.colorScheme
    val document = controller.activeDocument
    Row(
        Modifier
            .fillMaxWidth()
            // Android swallows most taps in the bottom gesture zone (44 px on the tablet), which
            // covered the whole bar; sit above it.
            .windowInsetsPadding(WindowInsets.mandatorySystemGestures.exclude(WindowInsets.safeDrawing).only(WindowInsetsSides.Bottom))
            .height(28.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val dotColor by animateColorAsState(
            if (controller.connected) colors.primary else colors.outline,
            tween(if (controller.settings.animations) 180 else 0),
            label = "statusDot",
        )
        Box(
            Modifier.size(8.dp).background(dotColor, CircleShape),
        )
        // VS Code's left end: the branch and the problem counts, each a tap from its view.
        if (expanded) {
            controller.gitStatus?.branch?.takeIf { controller.connected }?.let { branch ->
                StatusItem(branch, Icons.AutoMirrored.Outlined.CallSplit, tooltip = "Source Control", command = "workbench.view.scm") {
                    controller.runCommand("workbench.view.scm")
                }
            }
            val errors = controller.diagnostics.count { it.severity == 1 || it.severity == null }
            val warnings = controller.diagnostics.count { it.severity == 2 }
            Tooltip("Problems", "workbench.actions.view.problems") {
                Row(
                    Modifier.clickable { controller.runCommand("workbench.actions.view.problems") },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = "Errors", modifier = Modifier.size(14.dp), tint = if (errors > 0) colors.error else colors.onSurfaceVariant)
                    Text("$errors", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    Spacer(Modifier.width(3.dp))
                    Icon(Icons.Outlined.Warning, contentDescription = "Warnings", modifier = Modifier.size(14.dp), tint = if (warnings > 0) colors.tertiary else colors.onSurfaceVariant)
                    Text("$warnings", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
            }
        }
        Text(
            controller.status,
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clickable { controller.runCommand("workbench.action.output.toggleOutput") },
        )
        controller.guestStats?.takeIf { controller.connected && controller.settings.statusBarGuestResources }?.let { stats ->
            val pressure = stats.totalMb > 0 && stats.footprintMb * 100 / stats.totalMb >= 80
            Row(
                Modifier.clickable { controller.dialog = WorkbenchDialog.GuestResources },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Memory,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (pressure) colors.error else colors.onSurfaceVariant,
                )
                val storage = if (stats.diskTotalMb > 0) " · %.0f/%.0f GB".format(stats.diskUsedMb / 1024f, stats.diskTotalMb / 1024f) else ""
                Text(
                    " Guest %.1f GB · %d%%%s".format(stats.footprintMb / 1024f, stats.cpuPercent.toInt(), storage),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (pressure) colors.error else colors.onSurfaceVariant,
                )
            }
        }
        // Language servers' work in progress (indexing, loading the project), the newest first.
        controller.serverProgress.values.lastOrNull()?.let { progress ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = colors.onSurfaceVariant)
                Text(progress, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
            }
        }
        StatusItem(
            if (controller.copilotEnabled) "Copilot · ${controller.copilotStatus}" else "Copilot off",
            Icons.Outlined.AutoAwesome,
            onClick = controller::onCopilotStatusClick,
            tooltip = "Copilot status",
        )
        // An image has no lines, encoding or language to show.
        if (document != null && controller.activeNotebook == null && !document.binary) {
            // There from the start (a file just opened has its cursor at the top), so the items beside it don't move at the first tap.
            val cursor = controller.cursorPosition
            StatusItem("Ln ${(cursor?.line ?: 0) + 1}, Col ${(cursor?.character ?: 0) + 1}", tooltip = "Go to Line/Column", command = "workbench.action.gotoLine") { controller.showGoToLine() }
            StatusItem(controller.indentation(document).label, tooltip = "Select Indentation") { controller.dialog = WorkbenchDialog.IndentationPicker }
            StatusItem(document.encoding, tooltip = "Select Encoding") { controller.dialog = WorkbenchDialog.Encoding }
            StatusItem(if (document.eol == "\r\n") "CRLF" else "LF", tooltip = "Select End of Line Sequence") { controller.dialog = WorkbenchDialog.LineEnding }
            if (controller.lspLanguageId(document.path) == "python") {
                // VS Code's interpreter chip: the environment completion, lint and debugging use.
                val environment = pythonEnvironmentName(controller.settings.pythonInterpreter)
                StatusItem(if (controller.settings.pythonInterpreter.isBlank()) environment else "Python: $environment", tooltip = controller.settings.pythonInterpreter.ifBlank { "Select Interpreter" }) {
                    controller.chooseInterpreter()
                }
            }
            val language = controller.lspLanguageId(document.path) ?: document.languageId.ifEmpty { "plain text" }
            // Untitled files take their language from here (saved ones from their name).
            StatusItem(language, tooltip = if (document.path.startsWith("scratch:")) "Select Language Mode" else "", onClick = if (document.path.startsWith("scratch:")) {
                { controller.dialog = WorkbenchDialog.UntitledLanguage }
            } else {
                null
            })
        }
    }
}

@Composable
private fun StatusItem(text: String, icon: ImageVector? = null, tooltip: String = "", command: String? = null, onClick: (() -> Unit)? = null) {
    Tooltip(tooltip, command) { Row(
        Modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    } }
}

/**
 * The daemon saw disk errors in the guest. On the tablet that followed Android running out of
 * memory and meant corrupted memory in the guest, which only a VM restart clears.
 */
@Composable
private fun GuestDiskBanner(controller: WorkbenchController) {
    val problem = controller.guestStats?.diskProblem?.takeIf { controller.connected } ?: return
    WorkbenchBanner(
        Icons.Outlined.Warning,
        "$problem. Files saved now may be damaged. Copy out what you need, then restart the guest: " +
            "Force stop Linux Terminal and open it again.",
    ) {
        TextButton(onClick = controller::openLinuxTerminalAppInfo) { Text("App info") }
    }
}

/** The guest's daemon is from another release than the app: update it (or the app), or not now. */
@Composable
private fun DaemonVersionBanner(controller: WorkbenchController) {
    val notice = controller.daemonVersionNotice ?: return
    if (!controller.connected) return
    val message = when (notice.status) {
        DaemonVersionStatus.Older -> "The guest's daemon is ${notice.guest}; this app is ${notice.app}. Update the daemon to match: the pairing is kept."
        else -> "The guest's daemon is ${notice.guest}, newer than this app (${notice.app}). Update the app to match."
    }
    WorkbenchBanner(Icons.Outlined.Info, message, tonal = true) {
        if (notice.status == DaemonVersionStatus.Older) {
            TextButton(onClick = controller::updateGuestDaemon) { Text("Update") }
        }
        TextButton(onClick = controller::dismissDaemonVersionNotice) { Text("Not now") }
    }
}

