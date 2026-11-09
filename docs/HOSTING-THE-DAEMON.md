# Hosting the QuireCode daemon on a computer

QuireCode's editor runs on the tablet; files, terminals, language servers, Git, search, Jupyter
and the debugger run wherever the **daemon** runs. Usually that is the Linux guest on the tablet
itself (Connect → *Set up or pair…*). This guide covers running it on a **Linux computer** or a
**Windows computer (through WSL2)** on your network, so the tablet works on that machine's files
with its CPU and memory.

## How the connection is secured

* On a computer the daemon listens on the network only with **TLS**. It creates a self-signed
  certificate the first time it starts (`~/.config/quirecode/tls/`).
* The installer prints a **connection string**:
  `avf://192.168.1.10:19870?fp=<certificate fingerprint>&code=K7QM-2XPA`.
  The app **pins** that fingerprint: it trusts that certificate and nothing else, so another
  machine can't impersonate the daemon even on an untrusted network.
* The **code** is a one-time pairing code: valid for 10 minutes, 5 wrong attempts, single use. The
  app trades it for the daemon's session token, which it stores encrypted (Android Keystore).
* Anyone with the token has your user account's access to the workspace folder and a shell. Keep
  the daemon on networks you trust; to reach it from elsewhere, use a VPN (for example Tailscale or
  WireGuard) rather than exposing the port to the internet.

## Linux computer

Requirements: x86_64 or arm64 Linux with systemd, `curl`, `tar`, and Git for Source Control.

1. Get the daemon and its installer. Either download them from the
   [release](../../../releases/latest): `quirecode-daemon-<version>-linux-x86_64` (or `-linux-arm64`), checked against
   `SHA256SUMS`, and `install-native-daemon.sh`, both attached to the release; or build the daemon from this repository
   (needs Rust, https://rustup.rs):

   ```sh
   cargo build --release -p avf-daemon
   ```

2. Install it as a user service, listening on the network (with a downloaded daemon, give its path instead):

   ```sh
   bash scripts/install-native-daemon.sh --remote target/release/quirecode-daemon ~/Projects
   ```

   * `~/Projects` is the workspace the app sees; any absolute folder works.
   * `--port 19870` and `--address 192.168.1.10` override the detected port and LAN address.
   * It installs the tools (uv, ty, ruff, ripgrep, Jupyter bridge, debugpy, Copilot) for your user,
     enables lingering so the service runs without a login session, and prints the connection
     string.

3. Open the port if a firewall is active, for example `sudo ufw allow 19870/tcp`.

4. In QuireCode: **Connect → Set up or pair… → Pair with a computer**, paste the connection string,
   and tap **Pair with computer**. The app saves a profile for that host and reconnects to it on
   launch.

Need a new code later (another tablet, or a reinstall of the app)?

```sh
quirecode-daemon pair --remote 192.168.1.10:19870
```

## Windows computer (WSL2)

The daemon is a Linux program, so on Windows it runs inside a WSL2 distro and a PowerShell script
exposes it on your network.

1. Install WSL2 with a distro that uses systemd (Ubuntu does by default):

   ```powershell
   wsl --install -d Ubuntu
   ```

   If `ps -p 1 -o comm=` inside the distro doesn't print `systemd`, add this to `/etc/wsl.conf` in
   the distro and run `wsl --shutdown`:

   ```ini
   [boot]
   systemd=true
   ```

2. Make the repository available in the distro (clone it there, or use the Windows copy through
   `/mnt/c/...`) and install Rust in the distro (https://rustup.rs) so the script can build the
   daemon, or pass a prebuilt one with `-DaemonPath`.

3. From an **elevated** PowerShell, in the repository's `scripts` folder:

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\install-host-daemon-wsl.ps1 -Distro Ubuntu -Workspace ~/Projects
   ```

   It:
   * checks systemd, builds the daemon in the distro if needed, and runs the Linux installer with
     `--remote`, using **Windows'** LAN address in the connection string;
   * adds a Windows Firewall rule for the port (private and domain networks);
   * exposes the port: with `networkingMode=mirrored` in `%USERPROFILE%\.wslconfig` it adds a
     Hyper-V firewall rule; otherwise (NAT, the default) it adds a port proxy to the distro;
   * registers the logon task *AVF Studio daemon (WSL)*, which keeps the distro running and, in NAT
     mode, points the port proxy at the distro's current address (it changes after restarts).

4. Pair from the app as for Linux. A new code later:

   ```powershell
   wsl -d Ubuntu -- ~/.local/bin/quirecode-daemon pair --remote 192.168.1.10:19870
   ```

## Troubleshooting

| Symptom in the app | Likely cause |
|---|---|
| "Can't reach HOST:PORT" | Different network, firewall closed, the daemon isn't running (`systemctl --user status quirecode`), or on Windows the WSL distro stopped (run the logon task: `Start-ScheduledTask "AVF Studio daemon (WSL)"`). |
| "doesn't match the connection string" | The certificate changed (for example `~/.config/quirecode/tls` was deleted). Run `pair --remote` again and paste the new string. |
| "No pairing code is active" | The code expired, was used, or ran out of attempts. Run `pair --remote` again. |
| "The guest rejected the saved token" | The daemon was reinstalled with a new token. Pair again. |
| Connected, but Search finds nothing | ripgrep is missing: `quirecode-daemon tools install ripgrep`. |

## Removing it

Linux or WSL distro:

```sh
systemctl --user disable --now quirecode.service
rm -r ~/.config/systemd/user/quirecode.service ~/.config/quirecode ~/.local/bin/quirecode-daemon
```

Windows, elevated PowerShell:

```powershell
Unregister-ScheduledTask -TaskName "AVF Studio daemon (WSL)" -Confirm:$false
Remove-NetFirewallRule -DisplayName "AVF Studio daemon"
Get-NetFirewallHyperVRule -Name AVFStudioDaemon -ErrorAction SilentlyContinue | Remove-NetFirewallHyperVRule
netsh interface portproxy delete v4tov4 listenport=19870 listenaddress=0.0.0.0
```

## Limits

* Windows is supported through WSL2 only; there is no native Windows daemon yet (the daemon and
  app assume POSIX paths, and Git's credential prompts use Unix sockets).
* The tools the installer adds are Linux builds for x86_64 and arm64.
