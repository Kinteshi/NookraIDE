//! One-time pairing codes. `avf-daemon pair` stores a short code (only its SHA-256) with an
//! expiry and an attempt budget; a client that presents the code in `SystemMessage::Pair`
//! receives the session token, so nobody has to copy 64 hexadecimal characters by hand.

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs,
    path::{Path, PathBuf},
    sync::Mutex,
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use subtle::ConstantTimeEq;

/// How long a pairing code stays valid.
pub const PAIRING_TTL: Duration = Duration::from_secs(10 * 60);
/// Wrong codes allowed before the pairing record is discarded.
pub const MAX_ATTEMPTS: u32 = 5;
/// No 0/O, 1/I/L: codes are read off a terminal and typed on a tablet.
const ALPHABET: &[u8] = b"ABCDEFGHJKMNPQRSTUVWXYZ23456789";

/// Serialises redemption so two clients can't both spend the last attempt.
static REDEEM_LOCK: Mutex<()> = Mutex::new(());

#[derive(Debug, Serialize, Deserialize)]
struct PairingRecord {
    code_sha256: String,
    expires_at: u64,
    attempts_left: u32,
}

#[derive(Debug, PartialEq, Eq)]
pub enum PairingError {
    /// No code was issued, or it expired or ran out of attempts.
    Unavailable,
    /// The code didn't match; this many attempts remain.
    WrongCode { attempts_left: u32 },
    Io(String),
}

/// `$AVF_PAIRING_FILE`, else `pairing.json` in the per-user config directory
/// (`$XDG_CONFIG_HOME` or `~/.config` on Unix, `%APPDATA%` on Windows) under `nookra`.
pub fn pairing_path() -> Result<PathBuf, String> {
    if let Some(path) = std::env::var_os("AVF_PAIRING_FILE") {
        return Ok(PathBuf::from(path));
    }
    Ok(config_dir()?.join("pairing.json"))
}

/// The per-user configuration directory for the session token, pairing code and pinned certificate.
pub fn config_dir() -> Result<PathBuf, String> {
    let base = if cfg!(windows) {
        std::env::var_os("APPDATA").map(PathBuf::from)
    } else {
        std::env::var_os("XDG_CONFIG_HOME")
            .map(PathBuf::from)
            .or_else(|| std::env::var_os("HOME").map(|home| PathBuf::from(home).join(".config")))
    };
    let base = base.ok_or_else(|| "cannot find the user's configuration directory".to_owned())?;
    Ok(migrated_config_dir(&base))
}

/// The installer migrates old configurations; standalone invocations can still read them.
fn migrated_config_dir(base: &Path) -> PathBuf {
    let current = base.join("nookra");
    if current.exists() {
        return current;
    }
    for name in ["quirecode", "avf-native"] {
        let former = base.join(name);
        if former.is_dir() {
            return former;
        }
    }
    current
}

/// Issues a new code (replacing any earlier one) and returns it formatted as `XXXX-XXXX`.
pub fn create(path: &Path, ttl: Duration) -> Result<String, String> {
    let code = random_code();
    let record = PairingRecord {
        code_sha256: digest(&code),
        expires_at: now_secs() + ttl.as_secs(),
        attempts_left: MAX_ATTEMPTS,
    };
    write_record(path, &record)?;
    Ok(format!("{}-{}", &code[..4], &code[4..]))
}

/// Spends one attempt: Ok when [presented] matches (the record is then deleted).
pub fn redeem(path: &Path, presented: &str) -> Result<(), PairingError> {
    let _guard = REDEEM_LOCK.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    let record: PairingRecord = match fs::read(path) {
        Ok(bytes) => serde_json::from_slice(&bytes).map_err(|_| PairingError::Unavailable)?,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Err(PairingError::Unavailable),
        Err(error) => return Err(PairingError::Io(error.to_string())),
    };
    if record.expires_at <= now_secs() || record.attempts_left == 0 {
        let _ = fs::remove_file(path);
        return Err(PairingError::Unavailable);
    }
    let presented = normalize(presented);
    let matches = digest(&presented).as_bytes().ct_eq(record.code_sha256.as_bytes()).unwrap_u8() == 1;
    if matches {
        fs::remove_file(path).map_err(|error| PairingError::Io(error.to_string()))?;
        return Ok(());
    }
    let attempts_left = record.attempts_left - 1;
    if attempts_left == 0 {
        let _ = fs::remove_file(path);
    } else {
        write_record(path, &PairingRecord { attempts_left, ..record }).map_err(PairingError::Io)?;
    }
    Err(PairingError::WrongCode { attempts_left })
}

fn normalize(code: &str) -> String {
    code.chars()
        .filter(|ch| !ch.is_whitespace() && *ch != '-')
        .map(|ch| ch.to_ascii_uppercase())
        .collect()
}

fn random_code() -> String {
    // uuid v4 draws from the OS CSPRNG; 8 symbols from 31 is about 40 bits. Bytes at or above
    // 248 (= 8 × 31) are skipped so every symbol is equally likely.
    let limit = (256 / ALPHABET.len() * ALPHABET.len()) as u8;
    let mut code = String::with_capacity(8);
    while code.len() < 8 {
        for byte in uuid::Uuid::new_v4().into_bytes() {
            if byte < limit && code.len() < 8 {
                code.push(ALPHABET[usize::from(byte) % ALPHABET.len()] as char);
            }
        }
    }
    code
}

fn digest(code: &str) -> String {
    Sha256::digest(code.as_bytes()).iter().map(|byte| format!("{byte:02x}")).collect()
}

fn now_secs() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|elapsed| elapsed.as_secs()).unwrap_or(0)
}

fn write_record(path: &Path, record: &PairingRecord) -> Result<(), String> {
    let parent = path.parent().ok_or("pairing path has no parent directory")?;
    fs::create_dir_all(parent).map_err(|error| format!("cannot create {}: {error}", parent.display()))?;
    let staged = path.with_extension("tmp");
    let bytes = serde_json::to_vec(record).map_err(|error| error.to_string())?;
    let mut options = fs::OpenOptions::new();
    options.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    {
        use std::io::Write;
        let mut file = options.open(&staged).map_err(|error| format!("cannot write {}: {error}", staged.display()))?;
        file.write_all(&bytes).map_err(|error| error.to_string())?;
    }
    fs::rename(&staged, path).map_err(|error| format!("cannot write {}: {error}", path.display()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn config_dir_uses_legacy_until_installer_migrates_it() {
        let base = tempfile::tempdir().unwrap();
        let former = base.path().join("avf-native");
        std::fs::create_dir_all(former.join("tls")).unwrap();
        std::fs::write(former.join("env"), "AVF_TOKEN=x\n").unwrap();
        let dir = migrated_config_dir(base.path());
        assert_eq!(dir, former);
        assert_eq!(std::fs::read_to_string(dir.join("env")).unwrap(), "AVF_TOKEN=x\n");
        assert!(dir.join("tls").is_dir());
        let current = base.path().join("nookra");
        std::fs::create_dir_all(&current).unwrap();
        assert_eq!(migrated_config_dir(base.path()), current);
        assert!(former.exists());
    }

    #[test]
    fn config_dir_is_new_without_a_former_folder() {
        let base = tempfile::tempdir().unwrap();
        assert_eq!(migrated_config_dir(base.path()), base.path().join("nookra"));
        assert!(!base.path().join("nookra").exists());
    }

    #[test]
    fn config_dir_prefers_quirecode_to_older_avf_native() {
        let base = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(base.path().join("avf-native")).unwrap();
        std::fs::create_dir_all(base.path().join("quirecode")).unwrap();
        assert_eq!(migrated_config_dir(base.path()), base.path().join("quirecode"));
    }

    #[test]
    fn issued_code_redeems_once_ignoring_case_and_dashes() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("pairing.json");
        let code = create(&path, PAIRING_TTL).unwrap();
        assert_eq!(code.len(), 9);
        assert!(code.chars().all(|ch| ch == '-' || ALPHABET.contains(&(ch as u8))));
        assert_eq!(redeem(&path, &code.to_lowercase().replace('-', " ")), Ok(()));
        assert_eq!(redeem(&path, &code), Err(PairingError::Unavailable));
    }

    #[test]
    fn wrong_codes_spend_attempts_until_the_record_is_gone() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("pairing.json");
        let code = create(&path, PAIRING_TTL).unwrap();
        for remaining in (1..MAX_ATTEMPTS).rev() {
            assert_eq!(redeem(&path, "WRONG-CODE"), Err(PairingError::WrongCode { attempts_left: remaining }));
        }
        assert_eq!(redeem(&path, "WRONG-CODE"), Err(PairingError::WrongCode { attempts_left: 0 }));
        assert_eq!(redeem(&path, &code), Err(PairingError::Unavailable));
    }

    #[test]
    fn expired_codes_are_rejected() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("pairing.json");
        let code = create(&path, Duration::ZERO).unwrap();
        assert_eq!(redeem(&path, &code), Err(PairingError::Unavailable));
        assert!(!path.exists());
    }

    #[cfg(unix)]
    #[test]
    fn the_record_is_private_to_the_user() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("pairing.json");
        create(&path, PAIRING_TTL).unwrap();
        assert_eq!(fs::metadata(&path).unwrap().permissions().mode() & 0o777, 0o600);
    }
}
