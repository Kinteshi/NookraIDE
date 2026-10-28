//! A temp dir on disk for what the daemon starts. In the AVF guest /tmp is a RAM-backed tmpfs of
//! half the guest's memory (2 GB on a 4 GB guest); pip and conda unpack large packages there and
//! fail with "No space left on device".

use std::path::{Path, PathBuf};

/// Whether the mount table (`/proc/self/mounts`) puts /tmp on a tmpfs.
pub fn tmp_is_tmpfs(mounts: &str) -> bool {
    mounts
        .lines()
        .filter_map(|line| {
            let mut fields = line.split_whitespace();
            let _source = fields.next()?;
            Some((fields.next()?, fields.next()?))
        })
        .filter(|(target, _)| *target == "/tmp")
        .last()
        .is_some_and(|(_, fstype)| fstype == "tmpfs")
}

/// The directory to use when /tmp lives in memory and TMPDIR isn't set already.
pub fn disk_tmpdir(home: Option<&Path>, tmpdir: Option<&str>, mounts: &str) -> Option<PathBuf> {
    if tmpdir.is_some_and(|value| !value.is_empty()) || !tmp_is_tmpfs(mounts) {
        return None;
    }
    Some(home?.join(".cache/tmp"))
}

/// Points TMPDIR at `~/.cache/tmp` when /tmp is a tmpfs, so shells, kernels and tools the daemon
/// starts inherit it. Call before any thread starts: it changes the process environment.
pub fn prefer_disk_tmpdir() -> Option<PathBuf> {
    let mounts = std::fs::read_to_string("/proc/self/mounts").ok()?;
    let home = std::env::var_os("HOME").map(PathBuf::from);
    let current = std::env::var("TMPDIR").ok();
    let dir = disk_tmpdir(home.as_deref(), current.as_deref(), &mounts)?;
    std::fs::create_dir_all(&dir).ok()?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(&dir, std::fs::Permissions::from_mode(0o700));
    }
    std::env::set_var("TMPDIR", &dir);
    Some(dir)
}

#[cfg(test)]
mod tests {
    use super::*;

    const GUEST: &str = "/dev/root / ext4 rw,relatime 0 0\n\
        tmpfs /dev/shm tmpfs rw,nosuid,nodev 0 0\n\
        tmpfs /tmp tmpfs rw,nosuid,nodev,size=2001000k 0 0\n";
    const DISK_TMP: &str = "/dev/sda1 / ext4 rw 0 0\n";

    #[test]
    fn detects_tmp_in_memory() {
        assert!(tmp_is_tmpfs(GUEST));
        assert!(!tmp_is_tmpfs(DISK_TMP));
        assert!(!tmp_is_tmpfs("tmpfs /tmp/x tmpfs rw 0 0\n"));
    }

    #[test]
    fn uses_the_home_cache_only_when_needed() {
        let home = Path::new("/home/droid");
        assert_eq!(
            disk_tmpdir(Some(home), None, GUEST),
            Some(PathBuf::from("/home/droid/.cache/tmp"))
        );
        assert_eq!(
            disk_tmpdir(Some(home), Some(""), GUEST),
            Some(PathBuf::from("/home/droid/.cache/tmp"))
        );
        // The user's own TMPDIR wins; a disk-backed /tmp needs nothing.
        assert_eq!(disk_tmpdir(Some(home), Some("/scratch"), GUEST), None);
        assert_eq!(disk_tmpdir(Some(home), None, DISK_TMP), None);
        assert_eq!(disk_tmpdir(None, None, GUEST), None);
    }
}
