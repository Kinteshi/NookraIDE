package io.github.kinteshi.quirecode.workbench

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import io.github.kinteshi.quirecode.transport.parseConnectionString

/** Where the workspace, terminals, language servers, and daemon should run (G35). */
enum class WorkTarget(val title: String, val subtitle: String) {
    TabletGuest("This tablet's Linux guest", "Runs in Android's Linux Terminal on this device"),
    Computer("A computer", "Linux, or Windows through WSL2, using that machine's CPU and files"),
}

/** Where the tablet will connect to a computer from (G35). */
enum class UsageScope(val title: String) {
    HomeWifi("At home on the same Wi-Fi"),
    Anywhere("From anywhere"),
}

/** Operating system of the host computer for tailored install/pair commands (G35). */
enum class ComputerOs(val label: String) {
    Linux("Linux"),
    WindowsWsl("Windows (WSL2)"),
}

/** Connection method recommended by the guided setup (G35). */
enum class RemoteSetupOption(
    val title: String,
    val why: String,
    val warning: String? = null,
    val recommended: Boolean = false,
) {
    Lan(
        title = "Same network (--remote)",
        why = "Direct TLS on your Wi-Fi with a pinned certificate fingerprint; nothing exposed to the internet.",
        recommended = true,
    ),
    Tailscale(
        title = "Tailscale (Recommended)",
        why = "End-to-end encrypted private network between your tablet and computer, no open router ports, free for personal use.",
        recommended = true,
    ),
    CloudflareTunnel(
        title = "Cloudflare Tunnel (--tunnel)",
        why = "Works anywhere when no VPN can run on the tablet, with no open router ports.",
        warning = "Trade-off: Cloudflare decrypts the traffic (tokens and files) on its way through.",
    ),
    RouterPortForward(
        title = "Router port forwarding (last resort)",
        why = "Direct TLS to your router's public IP or dynamic DNS hostname, still certificate-pinned and rate-limited.",
        warning = "Warning: opens TCP port 19870 to internet scanners. Prefer Tailscale or Cloudflare Tunnel whenever possible.",
    ),
}

/** Concrete setup and re-pair commands for the selected option and OS (G35). */
data class GuidedCommands(
    val setupStep: String,
    val pairCommand: String,
    val note: String? = null,
)

internal fun guidedOptionsFor(scope: UsageScope): List<RemoteSetupOption> = when (scope) {
    UsageScope.HomeWifi -> listOf(RemoteSetupOption.Lan)
    UsageScope.Anywhere -> listOf(
        RemoteSetupOption.Tailscale,
        RemoteSetupOption.CloudflareTunnel,
        RemoteSetupOption.RouterPortForward,
    )
}

internal fun guidedCommands(option: RemoteSetupOption, os: ComputerOs): GuidedCommands = when (option) {
    RemoteSetupOption.Lan -> when (os) {
        ComputerOs.Linux -> GuidedCommands(
            setupStep = "bash scripts/install-native-daemon.sh --remote target/release/quirecode-daemon ~/Projects",
            pairCommand = "quirecode-daemon pair --remote <LAN-IP>:19870",
            note = "Allow TCP port 19870 in your computer's firewall if enabled (e.g. sudo ufw allow 19870/tcp).",
        )
        ComputerOs.WindowsWsl -> GuidedCommands(
            setupStep = "powershell -ExecutionPolicy Bypass -File .\\install-host-daemon-wsl.ps1 -Distro Ubuntu -Workspace ~/Projects",
            pairCommand = "wsl -d Ubuntu -- ~/.local/bin/quirecode-daemon pair --remote <LAN-IP>:19870",
            note = "Run the PowerShell installer from an elevated PowerShell window inside the scripts folder (with systemd=true in /etc/wsl.conf).",
        )
    }
    RemoteSetupOption.Tailscale -> when (os) {
        ComputerOs.Linux -> GuidedCommands(
            setupStep = "bash scripts/install-native-daemon.sh --tailscale target/release/quirecode-daemon ~/Projects",
            pairCommand = "quirecode-daemon pair --remote <TAILSCALE-IP>:19870",
            note = "Sign in to the same Tailscale account on both the computer and this tablet.",
        )
        ComputerOs.WindowsWsl -> GuidedCommands(
            setupStep = "bash scripts/install-native-daemon.sh --tailscale target/release/quirecode-daemon ~/Projects",
            pairCommand = "wsl -d Ubuntu -- ~/.local/bin/quirecode-daemon pair --remote <TAILSCALE-IP>:19870",
            note = "Install and sign in to Tailscale inside your systemd-enabled WSL distro (then the PowerShell forwarder isn't needed).",
        )
    }
    RemoteSetupOption.CloudflareTunnel -> when (os) {
        ComputerOs.Linux -> GuidedCommands(
            setupStep = "bash scripts/install-native-daemon.sh target/release/quirecode-daemon ~/Projects\ncloudflared tunnel --url http://127.0.0.1:19870",
            pairCommand = "quirecode-daemon pair --tunnel <YOUR-HOSTNAME>",
            note = "Point your Cloudflare HTTP tunnel at http://127.0.0.1:19870; pair prints an avf://...?fp=public link and QR code.",
        )
        ComputerOs.WindowsWsl -> GuidedCommands(
            setupStep = "wsl -d Ubuntu -- bash -lc \"bash scripts/install-native-daemon.sh target/release/quirecode-daemon ~/Projects\"\ncloudflared tunnel --url http://127.0.0.1:19870",
            pairCommand = "wsl -d Ubuntu -- ~/.local/bin/quirecode-daemon pair --tunnel <YOUR-HOSTNAME>",
            note = "Run the daemon on loopback inside WSL and point cloudflared at http://127.0.0.1:19870.",
        )
    }
    RemoteSetupOption.RouterPortForward -> when (os) {
        ComputerOs.Linux -> GuidedCommands(
            setupStep = "bash scripts/install-native-daemon.sh --remote target/release/quirecode-daemon ~/Projects",
            pairCommand = "quirecode-daemon pair --remote <PUBLIC-HOST>:19870",
            note = "Forward external TCP port 19870 on your router to the computer's static LAN IP and port 19870.",
        )
        ComputerOs.WindowsWsl -> GuidedCommands(
            setupStep = "powershell -ExecutionPolicy Bypass -File .\\install-host-daemon-wsl.ps1 -Distro Ubuntu -Workspace ~/Projects",
            pairCommand = "wsl -d Ubuntu -- ~/.local/bin/quirecode-daemon pair --remote <PUBLIC-HOST>:19870",
            note = "Forward external TCP port 19870 on your router to Windows' static LAN IP and port 19870.",
        )
    }
}

/** Trims [raw] and returns it if it is a valid QuireCode `avf://` pairing string (G36). */
internal fun extractConnectionStringFromText(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return trimmed.takeIf { parseConnectionString(it) != null }
}

internal data class TailscaleGuideState(val installed: Boolean, val vpnActive: Boolean)

internal fun readTailscaleGuideState(context: Context, controller: WorkbenchController): TailscaleGuideState {
    val installed = controller.tailscaleAvailable()
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val vpnActive = manager?.activeNetwork?.let(manager::getNetworkCapabilities)
        ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    return TailscaleGuideState(installed = installed, vpnActive = vpnActive)
}

internal fun readClipboardConnectionString(context: Context): String? = runCatching {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    extractConnectionStringFromText(text)
}.getOrNull()

internal fun copyGuideCommand(controller: WorkbenchController, context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    controller.status = "Copied $label"
}

internal fun shareGuideCommands(context: Context, title: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private const val TAILSCALE_PACKAGE_ID = "com.tailscale.ipn"

internal fun openTailscaleInstall(context: Context) {
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$TAILSCALE_PACKAGE_ID")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(market) }.isFailure) {
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$TAILSCALE_PACKAGE_ID"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(web) }
    }
}
