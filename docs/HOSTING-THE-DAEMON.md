# Hosting the QuireCode daemon on a computer

QuireCode's editor runs on the tablet; files, terminals, language servers, Git, search, Jupyter
and the debugger run wherever the **daemon** runs. Usually that is the Linux guest on the tablet
itself (the connection chip → *Add a connection* → *This tablet's Linux guest*). This guide covers running it on a **Linux computer** or a
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
  app trades it for a **token of its own**, which it stores encrypted (Android Keystore). The daemon
  keeps only a digest of each device's token, so one device can be revoked without touching the
  others (see [Paired devices and the connection log](#paired-devices-and-the-connection-log)).
* The installer's shared token (in `~/.config/quirecode/env`) works **only from the machine itself**
  (the tablet's own guest, behind Linux Terminal). From the network, only paired devices connect.
  A connection through a tunnel or reverse proxy on the machine (Cloudflare Tunnel, nginx) counts as
  from the network: it comes from 127.0.0.1, but carries the proxy's headers, and the daemon goes by
  the client address the proxy reports.
* An address that fails 10 times in 10 minutes (wrong tokens or codes) is refused until the oldest
  failure is 10 minutes old; the app says how long to wait.
* Anyone with a device's token has your user account's access to the workspace folder and a shell.
  Keep the daemon on networks you trust; to reach it from elsewhere, use a private network such as
  Tailscale ([below](#away-from-home-tailscale)) rather than exposing the port to the internet.

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

4. In QuireCode: the connection chip at the top right → **Add a connection → Guided setup** (or **A computer on your network** / **Pair with a computer** on the welcome page), scan the QR code or paste the connection string it prints, and tap
   **Pair and connect**. The app saves the connection and reconnects to it on launch; the chip's
   dialog switches between it and the tablet's guest.

Need a new code later (another tablet, or a reinstall of the app)?

```sh
quirecode-daemon pair --all --port 19870
```

`--all` detects both the computer's LAN IP and Tailscale IP (if signed in) and prints one multi-route link and QR code (`--remote HOST:PORT` can also be repeated, and `--tunnel HOST` added alongside). One pairing then covers both routes: QuireCode tries the local network at home, Tailscale away, and lets you set a preferred route per connection in **Settings → Guest**.

## Away from home: Tailscale

To use the computer from anywhere (a café, another city) without opening anything to the internet,
put the computer and the tablet on the same [Tailscale](https://tailscale.com) network (a "tailnet":
a private network between your own devices, free for personal use). The daemon then listens only
on the computer's tailnet address: other devices on your tailnet reach it, nothing on the LAN or
the internet does. TLS, the pinned certificate and the device tokens work as above.

1. Install Tailscale on the computer and sign in: `curl -fsSL https://tailscale.com/install.sh | sh`,
   then `sudo tailscale up`.
2. Install Tailscale on the tablet (Google Play) and sign in to the same account.
3. On the computer, install the daemon with `--tailscale` instead of `--remote`:

   ```sh
   bash install-native-daemon.sh --tailscale
   ```

   It binds to the address `tailscale ip -4` gives (100.x.y.z), keeps retrying at boot until
   Tailscale is up, and prints the connection string with that address (and the machine's
   MagicDNS name, if you'd rather type that).
4. In QuireCode: the connection chip → **Add a connection → A computer on your network**, paste it.

The tablet reaches the computer whenever Tailscale is on, on any network. A new code later:
`quirecode-daemon pair --remote 100.x.y.z:19870`.

On Windows, run Tailscale inside the WSL distro (it needs systemd, as the daemon does) and install
the daemon there with `--tailscale`; the PowerShell script isn't needed then.

> **Using Tailscale alongside the tablet's own Linux guest:** once, turning on Tailscale on the
> tablet while **Linux Terminal** was running stopped Linux Terminal from forwarding
> `127.0.0.1:19870` to the guest. The port still accepted a connection, so nothing looked closed, but
> the daemon never answered through it (Troubleshoot says "Port 127.0.0.1:19870 is open, but nothing
> answers through it"). Force stopping **Terminal** in Android's **App info** and opening it again
> fixed it, and it didn't come back with the VPN still on. If it keeps happening, excluding
> **Terminal** (`com.android.virtualization.terminal`) in Tailscale's **App Split Tunneling** may
> help; that's untested, so tell us if it does.

## Away from home without a VPN on the tablet: Cloudflare Tunnel

If you can't run Tailscale on the tablet (or prefer a domain with a standard public certificate),
you can put the daemon behind a [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/).
The daemon stays on loopback (`127.0.0.1:19870`, nothing opened on your LAN or router), `cloudflared`
makes an outbound-only connection to Cloudflare, and the tablet connects over `wss://<your-hostname>:443/ws`
validated by Android's trusted Root CAs (`fp=public`).

1. On the computer, install the daemon on loopback (without `--remote`):

   ```sh
   bash scripts/install-native-daemon.sh target/release/quirecode-daemon ~/Projects
   ```

2. Point a Cloudflare Tunnel at `http://127.0.0.1:19870` (for a quick test, `cloudflared tunnel --url http://127.0.0.1:19870`;
   for everyday use, a named tunnel on your domain).

3. Issue a pairing string for the tunnel's hostname:

   ```sh
   quirecode-daemon pair --tunnel quirecode.example.com
   ```

   It prints `avf://quirecode.example.com:443?fp=public&code=XXXX-XXXX` and a QR code. Scan the QR code or paste the string into **Add a
   connection → Guided setup** (or **A computer on your network**) in QuireCode.

How the daemon treats tunnel traffic: `cloudflared` connects to the daemon from 127.0.0.1, but adds
`CF-Connecting-IP` with the real client's address. The daemon goes by that address, so through the
tunnel the shared token is refused, failed attempts are limited per client, and `quirecode-daemon log`
shows where each connection came from. This relies on the tunnel being an **HTTP** service as above.
A raw TCP forward to the loopback port (`cloudflared access tcp`, `ssh -L`, `socat`) adds no headers,
so its clients look like the machine itself; use those only between machines you control.

The tunnel's hostname is public: anyone can reach the daemon, and only the pairing code and device
tokens keep them out. That is the same protection as a forwarded router port, with TLS from Cloudflare
instead of a pinned certificate. Cloudflare decrypts the traffic on its way through (tokens and
files included), so this means trusting Cloudflare; Tailscale's connections are end-to-end encrypted.

### Second lock: Cloudflare Access service token

You can place the tunnel behind a [Cloudflare Access](https://developers.cloudflare.com/cloudflare-one/policies/access/) policy using a **Service Token** (`CF-Access-Client-Id` and `CF-Access-Client-Secret`):

1. In Cloudflare Zero Trust → **Access → Service Auth → Service Tokens**, create a token and copy its **Client ID** and **Client Secret**.
2. Create an Access Application for your tunnel hostname with a **Service Auth** policy allowing that Service Token.
3. In QuireCode, enter that **Client ID** and **Client Secret** under **Cloudflare Access (optional)** when pairing or editing the connection (stored encrypted with Android's Keystore alongside your device token). QuireCode sends them on both WebSocket handshakes and Troubleshoot health checks.

## Router port forwarding

If you prefer a direct connection without Tailscale or Cloudflare Tunnel, you can forward a TCP port
on your home router to the computer running `--remote`:

1. Give the computer a static DHCP lease on your router so its LAN address doesn't change, and set
   up dynamic DNS (or use your public IP).
2. Forward external TCP port `19870` (or another port of your choice) on the router to the
   computer's LAN address and port `19870`.
3. Issue a pairing string with your public hostname or IP:

   ```sh
   quirecode-daemon pair --remote home.example.com:19870
   ```

Because an open router port is reachable by internet scanners, **Tailscale** (`--tailscale`) or
**Cloudflare Tunnel** (`--tunnel`) is strongly preferred whenever possible. When a port is forwarded,
the daemon still requires TLS with the pinned certificate fingerprint, rejects the shared loopback
token from the network, requires a one-time pairing code (10 minutes, 5 attempts), and blocks any
address after 10 failed attempts within 10 minutes (`quirecode-daemon log`).

## Paired devices and the connection log

```sh
quirecode-daemon devices               # the devices paired with codes: id, name, when, last seen, from where
quirecode-daemon devices revoke 3fa9c1 # unpair one (by id, or by its name); it's disconnected within 15 s
quirecode-daemon log                   # the last 40 connections, pairings and refusals (-n 200 for more)
```

The same list is in the app: the connection chip's **Connections** dialog, while connected, shows the devices paired
with that computer (the one in use marked "This device"), revokes one, and shows the recent connections.

A device's name is what the app sends when it pairs: "QuireCode on" and the tablet's name. When the
app pairs again with a computer it already has a connection to, it sends its old token along, and
the daemon replaces that earlier pairing (the log says "replacing its earlier pairing"). Pairings are
never replaced by name: two tablets of one model have the same name, and any app can claim one. A
pairing whose token the app no longer has (after reinstalling it) stays listed; revoke it. The log is `~/.config/quirecode/connections.log`, kept under 256 KB. A device paired
before these existed has the shared token: from the network it's refused, so pair it again.

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
| "Port 127.0.0.1:19870 is open, but nothing answers through it" | On the tablet, Linux Terminal's port forwarder accepted the socket on `127.0.0.1:19870` but nothing reached the guest daemon (often after turning on a VPN such as Tailscale). Force stop **Terminal** in Android's **App info** and open it again. If it keeps happening with Tailscale on, try excluding **Terminal** in Tailscale's **App Split Tunneling** (untested). |
| "doesn't match the connection string" | The certificate changed (for example `~/.config/quirecode/tls` was deleted). Run `pair --remote` again and paste the new string. |
| "No pairing code is active" | The code expired, was used, or ran out of attempts. Run `pair --remote` again. |
| "The guest rejected the saved token" | The daemon was reinstalled with a new token, the device was revoked, or it was paired before device tokens existed (the shared token works only on the machine itself). Pair again. |
| "Too many failed attempts from this device" | Ten wrong tokens or codes from this address in 10 minutes. Wait the minutes the app says; `quirecode-daemon log` shows what failed. |
| Can't reach a 100.x.y.z address | Tailscale is off on the tablet or the computer, or they're signed in to different tailnets. |
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
