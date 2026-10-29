# Linux Terminal: port forwarding stops for good when a guest process name contains a space

Draft for the Android issue tracker, Android Virtualization Framework / Terminal app. Not filed
yet.

## Summary

In the Linux Terminal guest, `forwarder_guest_launcher` exits whenever any process or thread
with a space in its name changes a TCP socket's state:

```
Error: Invalid LPORT format
Caused by:
    invalid digit found in string
```

systemd restarts the launcher within a second. After that, the host doesn't forward guest ports
any more, including ports that were already forwarded. Connections from Android to
`127.0.0.1:<port>` stop reaching the guest, even though the guest server still listens. Only
restarting the VM (closing Linux Terminal and opening it again) brings forwarding back.

Thread names with spaces are common. Python 3.14 copies `Thread.name` to the OS thread name, so
plain `threading.Thread(target=run)` threads are named `Thread-1 (run)`. Django's development
server handles requests in such threads, and ipykernel has a `Shell channel` thread. Java's JVM
threads have names with spaces as well.

## Environment

* Device: Samsung Galaxy Tab S11 Ultra (SM-X930), Android 16 (SDK 36), build
  BP4A.251205.006.X930XXS9BZI1, security patch 2026-09-05.
* Linux Terminal (`com.android.virtualization.terminal`): versionName 16, versionCode 36.
* Guest: Debian 13.7, kernel 6.12.63-android16-6-g8ee72b352c6a-ab14789739-4k.
* Guest packages: `forwarder-guest-launcher` 0.1.1-1, `libbpf-tools` 0.31.0+ds-7.

## Steps to reproduce

1. Open Linux Terminal and start a server the host forwards, for example
   `python3 -m http.server 8000`. Accept the port when the notification asks, and check that
   `http://127.0.0.1:8000` loads in an Android browser.
2. In a second tab, run any program whose name contains a space and that changes a socket's
   state. This needs no extra packages:

   ```sh
   cp "$(command -v nc)" "/tmp/my nc"
   timeout 1 "/tmp/my nc" -l 127.0.0.1 9999
   ```

   With Python 3.14 a thread does the same:

   ```python
   import socket, threading
   def go():
       s = socket.socket(); s.bind(("127.0.0.1", 0)); s.listen(); s.close()
   threading.Thread(target=go).start()  # OS thread name "Thread-1 (go)"
   ```

3. Reload `http://127.0.0.1:8000` in the Android browser.

**Expected:** the page loads; other programs' names don't affect forwarding.

**Actual:** the connection fails from then on. Stopping and restarting the forwarded server
doesn't help. Forwarding comes back only after restarting the VM.

## Logs

`sudo journalctl -u forwarder-guest-launcher` at the moment of step 2:

```
debian bash[456]: Error: Invalid LPORT format
debian bash[456]: Caused by:
debian bash[456]:     invalid digit found in string
debian systemd[1]: forwarder-guest-launcher.service: Main process exited, code=exited, status=1/FAILURE
debian systemd[1]: forwarder-guest-launcher.service: Scheduled restart job, restart counter is at 3.
debian systemd[1]: Started forwarder-guest-launcher.service - Port forwarding service in guest VM.
debian bash[14992]: DEBUG forwarder_guest_launcher] Starting to monitor and forwarding ports
debian bash[14992]: DEBUG forwarder_guest_launcher] Collecting already opened ports
debian bash[14992]: DEBUG forwarder_guest_launcher] Port 19870 is already opened by "avf-native-daem"
debian bash[14992]: DEBUG forwarder_guest_launcher] Successfully reported active ports to the host
debian bash[14992]: DEBUG forwarder_guest_launcher] Starting monitoring ports
```

Before the crash, every new connection from the host showed up as
`executing forwarder_guest with guest_tcp_port: …`. After the restart, no such line appears
again, even though the launcher reported the open port to the host. Restarting the forwarded
server later only logs `Listening port 19870 by tokio-rt-w is now closed`, and the port isn't
reported as listening again.

## Cause

The launcher reads the text output of `/usr/sbin/tcpstates-libbpf`:

```
SKADDR           PID     COMM       LADDR           LPORT RADDR           RPORT OLDSTATE    -> NEWSTATE    MS
ffffff80ce9ab0c0 857     my nc      127.0.0.1       9999  0.0.0.0         0     CLOSE       -> LISTEN      0.000
```

It seems to split each line on whitespace and take the fifth field as LPORT. When COMM contains
a space, every later column moves one place to the right. For the line above the fifth field is
`127.0.0.1`, so parsing it as a port fails, and that error ends the process.

## Two problems

1. **The parser crashes on a valid line.** COMM is up to 15 bytes chosen by the program and may
   contain spaces. Possible fixes:
   * Count the fixed fields from the end of the line: the last eight are LADDR, LPORT, RADDR,
     RPORT, OLDSTATE, `->`, NEWSTATE, MS.
   * Ask the tool for a machine-readable format.
   * Read the ports from `/proc/net/tcp{,6}` or a BPF ring buffer directly.

   Independently of the format, one line that doesn't parse should be logged and skipped, not
   end the launcher.
2. **Forwarding doesn't recover after the launcher restarts.** The restarted launcher reports
   the open ports, but the host never sets up forwarding for them again. Any launcher exit, not
   just this one, therefore cuts the user off from all guest servers until the VM restarts. The
   host should resynchronise when the launcher reconnects.

## Workaround

Put a wrapper in front of the tool that joins a spaced COMM into one field:

1. Divert the real binary:
   `dpkg-divert --local --rename --divert /usr/sbin/tcpstates-libbpf.real --add /usr/sbin/tcpstates-libbpf`
2. Install a script at `/usr/sbin/tcpstates-libbpf` that runs the `.real` binary through
   `stdbuf -oL` and rewrites lines with more than 11 fields. It joins everything between PID and
   the last eight fields with `_`.
3. Restart the VM. Restarting only the launcher loses the forwarding, because of the second
   problem.

With the wrapper, the reproduction above leaves forwarding intact, and the launcher logs
`New listening port 60783 by name_with`.
