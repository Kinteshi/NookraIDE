//! Paired devices, the connection log and limits on failed attempts (G30's hardening).
//!
//! Each pairing gives the device a token of its own, kept here only as a SHA-256 digest, so one device can be
//! revoked (`quirecode-daemon devices revoke ID`) without pairing the others again. The installer's shared token
//! (`AVF_TOKEN`) still works, but only from this machine itself (loopback: the tablet's own guest, behind Linux
//! Terminal's forwarding); from the network, a device token is required. Every connection, pairing and refusal is
//! logged to `connections.log`, and an address that fails too often is refused for a while.

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::fs;
use std::io::Write;
use std::net::IpAddr;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use subtle::ConstantTimeEq;

/// Failures an address may have within [FAILURE_WINDOW] before it's refused.
pub const MAX_FAILURES: usize = 10;
pub const FAILURE_WINDOW: Duration = Duration::from_secs(10 * 60);
/// The log is cut back to its newer half past this size.
const LOG_LIMIT_BYTES: u64 = 256 * 1024;

static REGISTRY_LOCK: Mutex<()> = Mutex::new(());

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Device {
    pub id: String,
    pub name: String,
    pub token_sha256: String,
    pub paired_at: u64,
    #[serde(default)]
    pub last_seen: Option<u64>,
    #[serde(default)]
    pub last_address: Option<String>,
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct Registry {
    devices: Vec<Device>,
}

pub fn devices_path(config_dir: &Path) -> PathBuf {
    config_dir.join("devices.json")
}

pub fn log_path(config_dir: &Path) -> PathBuf {
    config_dir.join("connections.log")
}

fn read(path: &Path) -> Result<Registry, String> {
    match fs::read(path) {
        Ok(bytes) => serde_json::from_slice(&bytes).map_err(|error| format!("{} is damaged: {error}", path.display())),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(Registry::default()),
        Err(error) => Err(format!("cannot read {}: {error}", path.display())),
    }
}

fn write(path: &Path, registry: &Registry) -> Result<(), String> {
    let parent = path.parent().ok_or("devices path has no parent directory")?;
    fs::create_dir_all(parent).map_err(|error| format!("cannot create {}: {error}", parent.display()))?;
    let staged = path.with_extension("tmp");
    let bytes = serde_json::to_vec_pretty(registry).map_err(|error| error.to_string())?;
    let mut options = fs::OpenOptions::new();
    options.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut file = options.open(&staged).map_err(|error| format!("cannot write {}: {error}", staged.display()))?;
    file.write_all(&bytes).map_err(|error| error.to_string())?;
    file.sync_all().map_err(|error| error.to_string())?;
    fs::rename(&staged, path).map_err(|error| format!("cannot replace {}: {error}", path.display()))
}

fn digest(token: &str) -> String {
    Sha256::digest(token.as_bytes()).iter().map(|byte| format!("{byte:02x}")).collect()
}

fn random_hex(bytes: usize) -> String {
    let mut out = String::with_capacity(bytes * 2);
    while out.len() < bytes * 2 {
        for byte in uuid::Uuid::new_v4().into_bytes() {
            if out.len() < bytes * 2 {
                out.push_str(&format!("{byte:02x}"));
            }
        }
    }
    out
}

pub fn now_secs() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|elapsed| elapsed.as_secs()).unwrap_or(0)
}

/// Pairs a device called [name]: a new token for it, returned once and kept here only as a digest.
pub fn issue(path: &Path, name: &str) -> Result<(Device, String), String> {
    let _guard = REGISTRY_LOCK.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    let mut registry = read(path)?;
    let token = random_hex(32);
    let mut id = random_hex(3);
    while registry.devices.iter().any(|device| device.id == id) {
        id = random_hex(3);
    }
    let name: String = name.chars().filter(|c| !c.is_control()).take(80).collect();
    let device = Device {
        id,
        name: if name.trim().is_empty() { "Unnamed device".to_owned() } else { name.trim().to_owned() },
        token_sha256: digest(&token),
        paired_at: now_secs(),
        last_seen: None,
        last_address: None,
    };
    registry.devices.push(device.clone());
    write(path, &registry)?;
    Ok((device, token))
}

/// The device [token] belongs to, its last use noted; None when no paired device has it.
pub fn authenticate(path: &Path, token: &str, address: Option<IpAddr>) -> Result<Option<Device>, String> {
    let _guard = REGISTRY_LOCK.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    let mut registry = read(path)?;
    let presented = digest(token);
    let Some(index) = registry
        .devices
        .iter()
        .position(|device| device.token_sha256.as_bytes().ct_eq(presented.as_bytes()).unwrap_u8() == 1)
    else {
        return Ok(None);
    };
    let device = &mut registry.devices[index];
    device.last_seen = Some(now_secs());
    device.last_address = address.map(|address| address.to_string());
    let found = device.clone();
    // Noting the last use is a convenience; a failed write doesn't refuse the device.
    let _ = write(path, &registry);
    Ok(Some(found))
}

/// Whether the device [id] is still paired (a revoked one's connections are closed).
pub fn is_paired(path: &Path, id: &str) -> bool {
    read(path).map(|registry| registry.devices.iter().any(|device| device.id == id)).unwrap_or(true)
}

pub fn list(path: &Path) -> Result<Vec<Device>, String> {
    read(path).map(|registry| registry.devices)
}

/// Unpairs the device whose id (or name, when only one has it) is [which].
pub fn revoke(path: &Path, which: &str) -> Result<Device, String> {
    let _guard = REGISTRY_LOCK.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    let mut registry = read(path)?;
    let index = match registry.devices.iter().position(|device| device.id == which) {
        Some(index) => index,
        None => {
            let named: Vec<usize> = registry
                .devices
                .iter()
                .enumerate()
                .filter(|(_, device)| device.name.eq_ignore_ascii_case(which))
                .map(|(index, _)| index)
                .collect();
            match named.as_slice() {
                [index] => *index,
                [] => return Err(format!("no paired device is called {which}; 'quirecode-daemon devices' lists them")),
                _ => return Err(format!("several devices are called {which}; revoke one by its id")),
            }
        }
    };
    let device = registry.devices.remove(index);
    write(path, &registry)?;
    Ok(device)
}

/// The shared token works only from this machine; from the network a device's own token is needed.
pub fn shared_token_allowed(peer: Option<IpAddr>) -> bool {
    match peer {
        None => true,
        Some(address) => address.is_loopback() || address.to_canonical().is_loopback(),
    }
}

/// Failed attempts per address, to refuse one that keeps failing. Loopback is never limited: on the tablet every
/// connection is the app's own, through Linux Terminal's forwarding, and a stale token retrying must not lock it out.
#[derive(Default)]
pub struct AttemptLimiter {
    failures: Mutex<HashMap<IpAddr, Vec<Instant>>>,
}

impl AttemptLimiter {
    /// How long [address] must wait, or None when it may try.
    pub fn blocked_for(&self, address: Option<IpAddr>) -> Option<Duration> {
        let address = address.filter(|address| !shared_token_allowed(Some(*address)))?;
        let mut failures = self.failures.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        let now = Instant::now();
        let times = failures.get_mut(&address)?;
        times.retain(|time| now.duration_since(*time) < FAILURE_WINDOW);
        if times.len() < MAX_FAILURES {
            return None;
        }
        times.first().map(|oldest| FAILURE_WINDOW.saturating_sub(now.duration_since(*oldest)))
    }

    pub fn failed(&self, address: Option<IpAddr>) {
        let Some(address) = address.filter(|address| !shared_token_allowed(Some(*address))) else { return };
        let mut failures = self.failures.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        let times = failures.entry(address).or_default();
        times.push(Instant::now());
        if times.len() > MAX_FAILURES * 2 {
            times.drain(..times.len() - MAX_FAILURES * 2);
        }
    }
}

/// `2026-11-09 13:40:05 UTC`, from seconds since the epoch.
pub fn format_time(secs: u64) -> String {
    let days = (secs / 86_400) as i64;
    let rest = secs % 86_400;
    // Days to a civil date (Howard Hinnant's algorithm).
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = yoe + era * 400 + i64::from(month <= 2);
    format!("{year:04}-{month:02}-{day:02} {:02}:{:02}:{:02} UTC", rest / 3_600, rest % 3_600 / 60, rest % 60)
}

/// Appends a line to the connection log: when, from where, what. Cut back to its newer half when it grows large.
pub fn log(path: &Path, address: Option<IpAddr>, what: &str) {
    let line = format!(
        "{} {} {}\n",
        format_time(now_secs()),
        address.map(|address| address.to_string()).unwrap_or_else(|| "-".to_owned()),
        what.replace('\n', " "),
    );
    let _ = (|| -> std::io::Result<()> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }
        if fs::metadata(path).map(|meta| meta.len() > LOG_LIMIT_BYTES).unwrap_or(false) {
            let text = fs::read_to_string(path).unwrap_or_default();
            let keep = text.len() / 2;
            let cut = text[keep..].find('\n').map(|at| keep + at + 1).unwrap_or(text.len());
            fs::write(path, &text[cut..])?;
        }
        let mut options = fs::OpenOptions::new();
        options.create(true).append(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        options.open(path)?.write_all(line.as_bytes())
    })();
}

/// `quirecode-daemon devices [revoke ID]` and `quirecode-daemon log [-n N]`.
pub fn run_cli(command: &str, args: &[String], config_dir: &Path) -> Result<(), String> {
    match command {
        "devices" => match args {
            [] => {
                let devices = list(&devices_path(config_dir))?;
                if devices.is_empty() {
                    println!("No device has paired with a code of its own yet.");
                    println!("(Devices paired before this version use the shared token, which works only from this machine.)");
                    return Ok(());
                }
                println!("{:<8} {:<32} {:<24} {:<24} FROM", "ID", "NAME", "PAIRED", "LAST SEEN");
                for device in devices {
                    println!(
                        "{:<8} {:<32} {:<24} {:<24} {}",
                        device.id,
                        device.name.chars().take(32).collect::<String>(),
                        format_time(device.paired_at),
                        device.last_seen.map(format_time).unwrap_or_else(|| "never".to_owned()),
                        device.last_address.unwrap_or_else(|| "-".to_owned()),
                    );
                }
                println!();
                println!("'quirecode-daemon devices revoke ID' unpairs one; it's disconnected within 15 seconds.");
                Ok(())
            }
            [verb, which] if verb == "revoke" => {
                let device = revoke(&devices_path(config_dir), which)?;
                log(&log_path(config_dir), None, &format!("revoked device {} ({})", device.id, device.name));
                println!("Revoked {} ({}). It has to pair again to connect.", device.id, device.name);
                Ok(())
            }
            _ => Err("usage: quirecode-daemon devices [revoke ID]".to_owned()),
        },
        "log" => {
            let lines = match args {
                [] => 40,
                [flag, count] if flag == "-n" => count.parse::<usize>().map_err(|_| "-n takes a number")?,
                _ => return Err("usage: quirecode-daemon log [-n LINES]".to_owned()),
            };
            let text = fs::read_to_string(log_path(config_dir)).unwrap_or_default();
            let all: Vec<&str> = text.lines().collect();
            if all.is_empty() {
                println!("No connections logged yet.");
            }
            for line in &all[all.len().saturating_sub(lines)..] {
                println!("{line}");
            }
            Ok(())
        }
        _ => Err(format!("unknown command {command}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("quirecode-devices-{name}-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn issued_tokens_authenticate_until_revoked() {
        let dir = temp_dir("issue");
        let path = devices_path(&dir);
        let (tablet, tablet_token) = issue(&path, "QuireCode on SM-X930").unwrap();
        let (_, phone_token) = issue(&path, "QuireCode on Pixel").unwrap();
        assert_eq!(tablet_token.len(), 64);
        assert_ne!(tablet_token, phone_token);
        let address: IpAddr = "100.64.0.2".parse().unwrap();
        let found = authenticate(&path, &tablet_token, Some(address)).unwrap().unwrap();
        assert_eq!(found.id, tablet.id);
        let listed = list(&path).unwrap();
        assert_eq!(listed[0].last_address.as_deref(), Some("100.64.0.2"));
        assert!(listed[0].last_seen.is_some());
        // Only the digest is stored.
        assert!(!fs::read_to_string(&path).unwrap().contains(&tablet_token));
        assert!(authenticate(&path, &"0".repeat(64), None).unwrap().is_none());
        assert_eq!(revoke(&path, &tablet.id).unwrap().name, "QuireCode on SM-X930");
        assert!(!is_paired(&path, &tablet.id));
        assert!(authenticate(&path, &tablet_token, None).unwrap().is_none());
        assert!(authenticate(&path, &phone_token, None).unwrap().is_some());
        // By name, when only one has it.
        assert!(revoke(&path, "quirecode on pixel").is_ok());
        assert!(revoke(&path, "nobody").is_err());
    }

    #[test]
    fn the_shared_token_is_for_this_machine_only() {
        assert!(shared_token_allowed(None));
        assert!(shared_token_allowed(Some("127.0.0.1".parse().unwrap())));
        assert!(shared_token_allowed(Some("::1".parse().unwrap())));
        assert!(shared_token_allowed(Some("::ffff:127.0.0.1".parse().unwrap())));
        assert!(!shared_token_allowed(Some("100.64.0.2".parse().unwrap())));
        assert!(!shared_token_allowed(Some("192.0.2.66".parse().unwrap())));
    }

    #[test]
    fn an_address_that_keeps_failing_waits_but_loopback_never_does() {
        let limiter = AttemptLimiter::default();
        let remote: IpAddr = "100.64.0.9".parse().unwrap();
        let local: IpAddr = "127.0.0.1".parse().unwrap();
        for _ in 0..MAX_FAILURES - 1 {
            limiter.failed(Some(remote));
            limiter.failed(Some(local));
        }
        assert!(limiter.blocked_for(Some(remote)).is_none());
        limiter.failed(Some(remote));
        limiter.failed(Some(local));
        let wait = limiter.blocked_for(Some(remote)).unwrap();
        assert!(wait > Duration::from_secs(9 * 60) && wait <= FAILURE_WINDOW);
        assert!(limiter.blocked_for(Some(local)).is_none());
        assert!(limiter.blocked_for(Some("100.64.0.10".parse().unwrap())).is_none());
    }

    #[test]
    fn times_are_utc_dates() {
        assert_eq!(format_time(0), "1970-01-01 00:00:00 UTC");
        assert_eq!(format_time(1_762_695_605), "2025-11-09 13:40:05 UTC");
        assert_eq!(format_time(951_782_400), "2000-02-29 00:00:00 UTC");
    }

    #[test]
    fn the_log_appends_and_stays_small() {
        let dir = temp_dir("log");
        let path = log_path(&dir);
        log(&path, Some("100.64.0.2".parse().unwrap()), "connected device ab12cd (QuireCode on SM-X930)");
        log(&path, None, "revoked device ab12cd");
        let text = fs::read_to_string(&path).unwrap();
        assert!(text.lines().next().unwrap().ends_with("100.64.0.2 connected device ab12cd (QuireCode on SM-X930)"));
        assert_eq!(text.lines().count(), 2);
        let long = "x".repeat(1_000);
        for _ in 0..400 {
            log(&path, None, &long);
        }
        assert!(fs::metadata(&path).unwrap().len() < LOG_LIMIT_BYTES + 2_000);
    }
}
