package io.github.kinteshi.quirecode.workbench

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private const val TOUR_STEPS = 4

/**
 * 4-step welcome and onboarding tour explaining where Nookra IDE can run code, how the tablet
 * workbench is laid out, what works with zero downloads, and one-tap actions to begin.
 */
@Composable
internal fun OnboardingTourDialog(controller: WorkbenchController) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val animate = controller.settings.animations
    var step by remember { mutableIntStateOf(0) }
    val terminalReady = remember(step) { controller.linuxTerminalAvailable() }
    val tailscaleState = remember(step) { readTailscaleGuideState(context, controller) }

    fun finish(action: (() -> Unit)? = null) {
        controller.completeOnboarding()
        controller.dialog = null
        action?.invoke()
    }

    AlertDialog(
        onDismissRequest = { finish() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Explore, contentDescription = null, tint = colors.primary)
                Column(Modifier.weight(1f)) {
                    Text(tourStepTitle(step), style = MaterialTheme.typography.titleLarge)
                    Text("Step ${step + 1} of $TOUR_STEPS", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
            }
        },
        text = {
            Column(
                Modifier.widthIn(max = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    repeat(TOUR_STEPS) { index ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(if (index <= step) colors.primary else colors.surfaceContainerHighest),
                        )
                    }
                }

                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        val ms = if (animate) 160 else 0
                        fadeIn(tween(ms)) togetherWith fadeOut(tween(ms))
                    },
                    label = "onboardingStep",
                ) { currentStep ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (currentStep) {
                            0 -> {
                                TourItem(
                                    Icons.Outlined.PhoneAndroid,
                                    "On this tablet alone",
                                    "Open any folder from device storage with zero Linux or network setup.",
                                )
                                TourItem(
                                    Icons.Outlined.TabletAndroid,
                                    if (terminalReady) "Tablet Linux guest (Ready)" else "Tablet Linux guest (Off in Developer options)",
                                    "Debian VM in Android's Linux Terminal: compilers, terminals, Jupyter kernels, Git, and Copilot.",
                                    highlight = terminalReady,
                                )
                                TourItem(
                                    Icons.Outlined.Computer,
                                    if (tailscaleState.vpnActive) "Your computer (Tailscale VPN on)" else "Your computer (Wi-Fi, Tailscale, or tunnel)",
                                    "Pair Linux or Windows (WSL2) once; one profile remembers all its routes.",
                                    highlight = tailscaleState.vpnActive,
                                )
                            }
                            1 -> {
                                TourItem(
                                    Icons.Outlined.Widgets,
                                    "Top connection chip",
                                    "Shows active machine and route at a glance. Tap to switch connections, manage routes, or troubleshoot.",
                                )
                                TourItem(
                                    Icons.Outlined.Search,
                                    "Quick Open & Command Palette",
                                    "Press Ctrl+P to jump to files and Ctrl+Shift+P for every command, snippet, and workspace tool.",
                                )
                                TourItem(
                                    Icons.Outlined.Terminal,
                                    "Left rail & bottom panel",
                                    "Explorer, Search, Source Control, Outline, Problems, Terminal, and AI Chat adapt smoothly to tablet or split-screen widths.",
                                )
                            }
                            2 -> {
                                TourItem(
                                    Icons.Outlined.Code,
                                    "20 languages built in + previews",
                                    "Kotlin, Python, TypeScript, Java, SQL, YAML, Markdown and more highlight offline immediately; extra languages fetch on demand.",
                                )
                                TourItem(
                                    Icons.Outlined.Palette,
                                    "Material You & VS Code themes",
                                    "Match your wallpaper in Settings → Appearance or import any VS Code theme JSON / .vsix pack.",
                                )
                                TourItem(
                                    Icons.Outlined.AutoAwesome,
                                    "Language servers & Copilot",
                                    "Install language servers, uv, Jupyter, or GitHub Copilot in Settings → Guest whenever you connect to Linux.",
                                )
                            }
                            else -> {
                                Text(
                                    "Choose how you want to start — you can replay this tour anytime from Settings → About or the Command Palette.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurfaceVariant,
                                )
                                Button(
                                    onClick = { finish { controller.dialog = WorkbenchDialog.ConnectionWizard } },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Outlined.Explore, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(18.dp))
                                    Text("Start guided connection setup")
                                }
                                FilledTonalButton(
                                    onClick = { finish { controller.beginOpenDeviceFolder() } },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(18.dp))
                                    Text("Open a folder on this device")
                                }
                                OutlinedButton(
                                    onClick = { finish { controller.openSamplePlayground() } },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Outlined.Code, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(18.dp))
                                    Text("Try a highlighted sample file")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (step < TOUR_STEPS - 1) {
                Button(onClick = { step++ }) { Text("Next") }
            } else {
                TextButton(onClick = { finish() }) { Text("Done") }
            }
        },
        dismissButton = {
            if (step == 0) {
                TextButton(onClick = { finish() }) { Text("Skip") }
            } else {
                TextButton(onClick = { step-- }) { Text("Back") }
            }
        },
    )
}

private fun tourStepTitle(step: Int): String = when (step) {
    0 -> "Three ways to code"
    1 -> "Around the workbench"
    2 -> "Languages, themes & tools"
    else -> "Ready to start"
}

@Composable
private fun TourItem(
    icon: ImageVector,
    title: String,
    body: String,
    highlight: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                if (highlight) Icons.Outlined.CheckCircle else icon,
                contentDescription = null,
                tint = if (highlight) colors.primary else colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}
