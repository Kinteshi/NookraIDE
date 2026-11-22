#![cfg(unix)]

use std::{fs, os::unix::fs::PermissionsExt, path::Path, process::Command};

fn executable(path: &Path, body: &str) {
    fs::write(path, format!("#!/bin/sh\n{body}\n")).unwrap();
    fs::set_permissions(path, fs::Permissions::from_mode(0o755)).unwrap();
}

fn install(home: &Path) -> std::process::Output {
    let bin = home.join("mock-bin");
    fs::create_dir_all(&bin).unwrap();
    executable(&bin.join("systemctl"), "echo \"$*\" >> \"$HOME/systemctl.log\"\n[ ! -f \"$HOME/fail-systemctl\" ]");
    for name in ["loginctl", "uv", "tailscale", "sleep"] {
        executable(&bin.join(name), "exit 0");
    }
    executable(&bin.join("findmnt"), "exit 1");
    let daemon = home.join("fixture-daemon");
    executable(&daemon, "exit 0");
    Command::new("bash")
        .arg(concat!(env!("CARGO_MANIFEST_DIR"), "/../../scripts/install-native-daemon.sh"))
        .args(["--remote", "--address", "127.0.0.1"])
        .arg(daemon)
        .env("HOME", home)
        .env("USER", "fixture")
        .env("PATH", format!("{}:/usr/bin:/bin", bin.display()))
        .output()
        .unwrap()
}

#[test]
fn installer_migrates_existing_configuration_and_disables_old_service() {
    let home = tempfile::tempdir().unwrap();
    let old = home.path().join(".config/quirecode");
    fs::create_dir_all(old.join("tls")).unwrap();
    fs::write(old.join("env"), "AVF_TOKEN=fixture\n").unwrap();
    fs::write(old.join("tls/certificate"), "fixture").unwrap();
    let services = home.path().join(".config/systemd/user");
    fs::create_dir_all(&services).unwrap();
    fs::write(services.join("quirecode.service"), "old unit").unwrap();

    let output = install(home.path());
    assert!(output.status.success(), "{}", String::from_utf8_lossy(&output.stderr));
    let new = home.path().join(".config/nookra");
    assert_eq!(fs::read_to_string(new.join("env")).unwrap(), "AVF_TOKEN=fixture\n");
    assert_eq!(fs::read_to_string(new.join("tls/certificate")).unwrap(), "fixture");
    assert!(!old.exists());
    assert!(services.join("quirecode.service").exists());
    let unit = fs::read_to_string(services.join("nookra.service")).unwrap();
    assert!(unit.contains("EnvironmentFile=%h/.config/nookra/env"));
    let calls = fs::read_to_string(home.path().join("systemctl.log")).unwrap();
    assert!(calls.contains("--user disable --now quirecode.service"));
    assert!(calls.contains("--user start nookra.service"));

    let output = install(home.path());
    assert!(output.status.success(), "{}", String::from_utf8_lossy(&output.stderr));
    assert_eq!(fs::read_to_string(new.join("env")).unwrap(), "AVF_TOKEN=fixture\n");
    assert_eq!(fs::read_to_string(new.join("tls/certificate")).unwrap(), "fixture");
}

#[test]
fn installer_stops_if_the_legacy_service_cannot_be_disabled() {
    let home = tempfile::tempdir().unwrap();
    let old = home.path().join(".config/quirecode");
    fs::create_dir_all(&old).unwrap();
    fs::write(old.join("env"), "AVF_TOKEN=fixture\n").unwrap();
    let services = home.path().join(".config/systemd/user");
    fs::create_dir_all(&services).unwrap();
    fs::write(services.join("quirecode.service"), "old unit").unwrap();
    fs::write(home.path().join("fail-systemctl"), "").unwrap();

    let output = install(home.path());
    assert!(!output.status.success());
    assert_eq!(fs::read_to_string(old.join("env")).unwrap(), "AVF_TOKEN=fixture\n");
    assert!(!home.path().join(".config/nookra").exists());
    assert!(!services.join("nookra.service").exists());
}

#[test]
fn installer_refuses_conflicting_configurations_without_touching_them() {
    let home = tempfile::tempdir().unwrap();
    for name in ["quirecode", "nookra"] {
        let config = home.path().join(".config").join(name);
        fs::create_dir_all(&config).unwrap();
        fs::write(config.join("env"), name).unwrap();
    }
    let output = install(home.path());
    assert!(!output.status.success());
    assert!(String::from_utf8_lossy(&output.stderr).contains("Multiple Nookra or legacy configurations"));
    for name in ["quirecode", "nookra"] {
        assert_eq!(fs::read_to_string(home.path().join(".config").join(name).join("env")).unwrap(), name);
    }
    assert!(!home.path().join("systemctl.log").exists());
}
