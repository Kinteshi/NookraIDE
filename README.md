<p align="center"><img src="docs/branding/nookra-ide-icon.svg" width="96" alt=""></p>

# Nookra IDE

A native code editor for Android tablets, with a Linux machine behind it.

**Early development:** There is no public release yet. Device coverage is limited, and the name and icon have not
received trademark clearance.

Nookra IDE looks and works like VS Code: its keybindings, settings, themes and grammars. It's built for a tablet with a
keyboard. The editor, Explorer, terminals, notebooks and Git are in the app. Language servers, debuggers, Jupyter
kernels and your tools run in the Linux guest of Android's **Linux Terminal**, on the same tablet, through a small daemon
that the app installs there. The daemon can also run on a Linux or Windows computer on your network.

## What you need

* An Android tablet with Android 14 or later and the **Linux Terminal** app, set up with its Debian guest. Android
  offers Linux Terminal on some devices, from Developer options. Nookra IDE is made and tested on a Galaxy Tab S11 Ultra.
* Free space and an internet connection in the guest: setup installs the daemon and its tools (uv, ty, ruff, ripgrep,
  Jupyter, debugpy, TypeScript, Copilot). More tools can be installed later from the app.
* A hardware keyboard is recommended. The on-screen keyboard works too, with a key bar for Esc, Tab, Ctrl, Alt and the arrows.

## Install

No release APK is available yet. To try the current source, build the ARM64 guest daemon and the Android debug app.
This requires Rust, cargo-zigbuild, Zig, JDK 17 and 21, and an Android SDK; see
[CONTRIBUTING.md](CONTRIBUTING.md) and [the build instructions](docs/NATIVE-IDE-HANDOFF.md#build-and-test).

```sh
./scripts/build-guest-bundle.sh
./apps/native-android-ide/gradlew -p apps/native-android-ide :app:testDebugUnitTest :app:assembleDebug
```

The debug APK is `apps/native-android-ide/app/build/outputs/apk/debug/app-debug.apk`. It is a separate development
installation, not a release-signed build. Once releases are available:

1. Download `nookra-<version>.apk` from the [latest release](../../releases/latest) and install it. To get updates,
   watch the releases, or add this repository to [Obtainium](https://github.com/ImranR98/Obtainium).
2. Open **Linux Terminal** once, so the guest is running.
3. Open Nookra IDE and choose **Set up the guest** (on the welcome page, or from the connection chip at the top right,
   under **Add a connection**):
   1. **Copy command**, then **Open Linux Terminal**, and paste the command there. It installs the daemon and its
      tools, which takes a few minutes, then prints a pairing code.
   2. If Android asks to open port 19870, accept it.
   3. Back in Nookra IDE, enter the pairing code and choose **Pair and connect**.

The app remembers the connection. When the guest is running, it reconnects by itself. The connection chip at the top
right always says where things stand; tap it for your connections, adding one, and a troubleshooting check.

Want a new pairing code later, for another tablet or after reinstalling the app? Run `nookra-daemon pair` in Linux
Terminal.

### A computer instead of the tablet's guest

The daemon also runs on a Linux computer, or on Windows through WSL2. Your files, tools and CPU are then the computer's.
The release includes the daemon for both (`nookra-daemon-<version>-linux-x86_64` and `-linux-arm64`). See
[Hosting the daemon on a computer](docs/HOSTING-THE-DAEMON.md). To use the computer from anywhere without opening it to
the internet, put both on [Tailscale](https://tailscale.com) and install with `--tailscale`. Each paired device has its
own token, so one can be revoked (`nookra-daemon devices`), and the daemon logs every connection.

### Without a Linux machine

**Open device folder…** (on the welcome page, or in the menu at the top right) opens a folder on the tablet itself,
such as one in Documents, on a USB drive or synced by another app, as the workspace. Explorer, editing, Quick Open,
find in files and the file actions work there without the guest. Terminals, language servers, Copilot, Git and
debugging run in the guest, so they're only there when it's connected.

## Updating

For installations with the former QuireCode app ID, Nookra IDE installs as a **separate app**: Android does not
automatically transfer its settings, saved connections, offline copies or drafts. Keep the old app installed until
you have saved any unsynced work, configured Nookra IDE and paired it again. The daemon installer migrates the old
configuration and certificate when there is no conflicting Nookra configuration; it stops rather than overwriting
conflicting configurations. Run the new setup before using `nookra-daemon` commands on an older guest.

For later Nookra IDE releases, install the new APK over the old one. If the guest's daemon is from an older release, the app says so and offers
**Update**. That runs the same setup again and keeps the pairing.

## When the guest goes away

* **Android may stop the guest** when the tablet runs low on memory, or when apps start many background processes.
  Android limits them to 32 for the whole device, and the guest uses several. Nookra IDE says when this happens; open
  Linux Terminal to start the guest again, and the app reconnects.
* **Offline:** while the guest is away, the app keeps working: the chip says "Offline", and the files of your last
  folder open from the copies kept on this device. Saves wait and go to the guest when it's back.
* **Port 19870 isn't reachable after the guest starts:** close Linux Terminal fully, from Recents, and open it again.
  The connection chip's dialog has a **Troubleshoot** check that tells whether anything answers on the port.

## Reporting a problem

Use **Settings > About > Copy diagnostics**, or **Help: Copy Diagnostics** in the command palette. Either copies a
plain-text report to the clipboard: versions, device, the guest's state and the app's recent messages. Read it, then
paste it into a [new issue](../../issues/new) with what you did and what happened.

## Privacy

Nookra IDE has no telemetry and no accounts of its own. It talks to the daemon you pair it with. GitHub Copilot and the
chat agents talk to their services only after you sign in to them. Connection tokens are kept encrypted with Android's
Keystore.

## Licence

Nookra IDE is licensed under the [Apache License 2.0](LICENSE). It includes open-source components under their own
licences; **Settings > About > Open-source licences** lists them. Building from source and the project's design
documents are covered in [CONTRIBUTING.md](CONTRIBUTING.md).
