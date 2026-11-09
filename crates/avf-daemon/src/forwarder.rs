//! A fix for Linux Terminal's port forwarder. In the AVF guest, `forwarder_guest_launcher` reads
//! `/usr/sbin/tcpstates-libbpf` and splits its lines on whitespace; a process or thread name with
//! a space (Python 3.14 thread names) makes it exit, and the host then forwards no port (the
//! daemon's included) until the guest restarts. The fix diverts the tool and puts a wrapper in its
//! place that joins such names.

use std::{
    fs, io,
    path::Path,
    process::Command,
};

const LAUNCHER: &str = "/usr/bin/forwarder_guest_launcher";
const TOOL: &str = "/usr/sbin/tcpstates-libbpf";
const REAL_TOOL: &str = "/usr/sbin/tcpstates-libbpf.real";
const WRAPPER: &str = include_str!("../../../guest/tools/tcpstates-space-safe.pl");
// The app's former name: the marker finds wrappers installed by earlier versions, so it stays.
const MARKER: &str = "MaterialCode tcpstates wrapper";

/// Whether the fix is installed and whether the running forwarder already uses it (it starts
/// with the guest). Both None outside an AVF Terminal guest.
pub fn state() -> (Option<bool>, Option<bool>) {
    if !Path::new(LAUNCHER).exists() {
        return (None, None);
    }
    let installed = is_wrapper(Path::new(TOOL)) && Path::new(REAL_TOOL).exists();
    (Some(installed), Some(installed && wrapper_running(Path::new("/proc"))))
}

fn is_wrapper(path: &Path) -> bool {
    fs::read_to_string(path).is_ok_and(|text| text.contains(MARKER))
}

/// The wrapper starts the real tool, so a process running `tcpstates-libbpf.real` means the
/// forwarder started since the fix went in.
fn wrapper_running(proc_dir: &Path) -> bool {
    let Ok(entries) = fs::read_dir(proc_dir) else {
        return false;
    };
    entries.flatten().any(|entry| {
        entry.file_name().to_string_lossy().bytes().all(|byte| byte.is_ascii_digit())
            && fs::read(entry.path().join("cmdline"))
                .is_ok_and(|cmdline| String::from_utf8_lossy(&cmdline).contains("tcpstates-libbpf.real"))
    })
}

/// `quirecode-daemon guest fix-port-forwarding [--undo]`, run with sudo. The forwarder isn't
/// restarted: a restart is exactly what loses the forwards, so the fix waits for the next boot.
pub fn run_cli(args: &[String]) -> io::Result<()> {
    match args {
        [command] if command == "fix-port-forwarding" => apply(),
        [command, undo] if command == "fix-port-forwarding" && undo == "--undo" => undo_fix(),
        _ => Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "usage: quirecode-daemon guest fix-port-forwarding [--undo]",
        )),
    }
}

fn apply() -> io::Result<()> {
    if !Path::new(LAUNCHER).exists() {
        return Err(io::Error::new(io::ErrorKind::NotFound, "this isn't a Linux Terminal guest (no forwarder_guest_launcher)"));
    }
    if !Path::new(REAL_TOOL).exists() {
        dpkg_divert(&["--local", "--rename", "--divert", REAL_TOOL, "--add", TOOL])?;
    }
    let staged = format!("{TOOL}.materialcode-new");
    fs::write(&staged, WRAPPER)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&staged, fs::Permissions::from_mode(0o755))?;
    }
    fs::rename(&staged, TOOL)?;
    println!("Port forwarding fix installed. It takes effect when the guest restarts: close Linux Terminal and open it again.");
    Ok(())
}

fn undo_fix() -> io::Result<()> {
    if is_wrapper(Path::new(TOOL)) {
        fs::remove_file(TOOL)?;
    }
    if Path::new(REAL_TOOL).exists() {
        dpkg_divert(&["--local", "--rename", "--divert", REAL_TOOL, "--remove", TOOL])?;
    }
    println!("Port forwarding fix removed. The original tool is back from the next guest restart.");
    Ok(())
}

fn dpkg_divert(args: &[&str]) -> io::Result<()> {
    let status = Command::new("dpkg-divert").args(args).status()?;
    if status.success() {
        Ok(())
    } else {
        Err(io::Error::other(format!("dpkg-divert failed ({status})")))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_wrapper_carries_its_marker() {
        assert!(WRAPPER.contains(MARKER));
        assert!(WRAPPER.contains(REAL_TOOL));
    }

    #[test]
    fn finds_the_real_tool_among_processes() {
        let proc_dir = tempfile::tempdir().unwrap();
        let process = proc_dir.path().join("467");
        fs::create_dir(&process).unwrap();
        fs::write(process.join("cmdline"), b"/usr/sbin/tcpstates-libbpf\0").unwrap();
        fs::create_dir(proc_dir.path().join("self")).unwrap();
        assert!(!wrapper_running(proc_dir.path()));
        fs::write(process.join("cmdline"), b"/usr/bin/stdbuf\0-oL\0/usr/sbin/tcpstates-libbpf.real\0").unwrap();
        assert!(wrapper_running(proc_dir.path()));
    }
}
