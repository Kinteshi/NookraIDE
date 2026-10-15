use avf_protocol::{FileEntry, VfsErrorCode, MAX_FILE_SIZE};
use cap_std::{
    ambient_authority,
    fs::{Dir, OpenOptions},
};
use std::{
    io::{self, Read, Write},
    path::{Component, Path, PathBuf},
    sync::Arc,
};
use uuid::Uuid;

#[derive(Clone)]
struct WorkspaceRoot {
    path: PathBuf,
    dir: Arc<Dir>,
}

#[derive(Clone)]
pub struct Vfs {
    roots: Arc<Vec<WorkspaceRoot>>,
    write_lock: Arc<tokio::sync::Mutex<()>>,
}

#[derive(Clone)]
pub struct ResolvedPath {
    root: WorkspaceRoot,
    relative: PathBuf,
    pub absolute: PathBuf,
}

#[derive(Debug)]
pub struct VfsFailure {
    pub code: VfsErrorCode,
    pub message: String,
}

impl VfsFailure {
    fn new(code: VfsErrorCode, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }
}

impl Vfs {
    pub fn new(paths: &[PathBuf]) -> io::Result<Self> {
        if paths.is_empty() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "at least one AVF_WORKSPACE_ROOTS directory is required",
            ));
        }

        let mut roots = Vec::with_capacity(paths.len());
        for path in paths {
            let canonical = std::fs::canonicalize(path)?;
            if !canonical.is_dir() {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    format!("workspace root is not a directory: {}", canonical.display()),
                ));
            }
            if canonical.to_str().is_none() {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "workspace roots must be valid UTF-8 paths",
                ));
            }
            let dir = Dir::open_ambient_dir(&canonical, ambient_authority())?;
            roots.push(WorkspaceRoot {
                path: canonical,
                dir: Arc::new(dir),
            });
        }
        roots.sort_by_key(|root| std::cmp::Reverse(root.path.components().count()));
        roots.dedup_by(|left, right| left.path == right.path);
        Ok(Self {
            roots: Arc::new(roots),
            write_lock: Arc::new(tokio::sync::Mutex::new(())),
        })
    }

    pub fn resolve(&self, requested: &str) -> Result<ResolvedPath, VfsFailure> {
        let path = Path::new(requested);
        if !path.is_absolute()
            || requested.contains('\0')
            || requested
                .split('/')
                .any(|component| matches!(component, "." | ".."))
            || path
                .components()
                .any(|component| matches!(component, Component::ParentDir | Component::CurDir))
        {
            return Err(VfsFailure::new(
                VfsErrorCode::InvalidPath,
                "VFS paths must be normalized absolute paths without '.' or '..' components",
            ));
        }

        let root = self
            .roots
            .iter()
            .find(|root| path.starts_with(&root.path))
            .cloned()
            .ok_or_else(|| {
                VfsFailure::new(
                    VfsErrorCode::PermissionDenied,
                    "path is outside the configured project roots",
                )
            })?;
        let relative = path.strip_prefix(&root.path).map_err(|_| {
            VfsFailure::new(VfsErrorCode::InvalidPath, "could not resolve project path")
        })?;
        let absolute = if relative.as_os_str().is_empty() {
            root.path.clone()
        } else {
            root.path.join(relative)
        };
        Ok(ResolvedPath {
            root,
            relative: if relative.as_os_str().is_empty() {
                PathBuf::from(".")
            } else {
                relative.to_path_buf()
            },
            absolute,
        })
    }

    pub async fn list_dir(&self, requested: String) -> Result<Vec<FileEntry>, VfsFailure> {
        let resolved = self.resolve(&requested)?;
        tokio::task::spawn_blocking(move || {
            let entries = resolved
                .root
                .dir
                .read_dir(&resolved.relative)
                .map_err(VfsFailure::from_io)?;
            let mut result = Vec::new();
            for entry in entries {
                let entry = entry.map_err(VfsFailure::from_io)?;
                let name = entry.file_name().into_string().map_err(|_| {
                    VfsFailure::new(
                        VfsErrorCode::InvalidPath,
                        "directory contains a filename that is not valid UTF-8",
                    )
                })?;
                let file_type = entry.file_type().map_err(VfsFailure::from_io)?;
                let metadata = entry.metadata().map_err(VfsFailure::from_io)?;
                let modified_ts =
                    modified_timestamp(metadata.modified().map_err(VfsFailure::from_io)?)?;
                result.push(FileEntry {
                    name: name.clone(),
                    path: resolved.absolute.join(name).to_string_lossy().into_owned(),
                    is_dir: file_type.is_dir(),
                    is_symlink: file_type.is_symlink(),
                    size: metadata.len(),
                    modified_ts,
                });
            }
            result.sort_by(|left, right| left.name.cmp(&right.name));
            Ok(result)
        })
        .await
        .map_err(|error| VfsFailure::new(VfsErrorCode::Io, error.to_string()))?
    }

    pub async fn read_file(&self, requested: String) -> Result<(Vec<u8>, u64), VfsFailure> {
        let resolved = self.resolve(&requested)?;
        tokio::task::spawn_blocking(move || {
            let file = resolved
                .root
                .dir
                .open(&resolved.relative)
                .map_err(VfsFailure::from_io)?;
            let metadata = file.metadata().map_err(VfsFailure::from_io)?;
            if metadata.is_dir() {
                return Err(VfsFailure::new(
                    VfsErrorCode::IsADirectory,
                    "cannot read a directory as a file",
                ));
            }
            if metadata.len() > MAX_FILE_SIZE as u64 {
                return Err(VfsFailure::new(
                    VfsErrorCode::TooLarge,
                    "file exceeds the 16 MiB VFS read limit",
                ));
            }
            let mut content = Vec::with_capacity(metadata.len() as usize);
            file.take((MAX_FILE_SIZE + 1) as u64)
                .read_to_end(&mut content)
                .map_err(VfsFailure::from_io)?;
            if content.len() > MAX_FILE_SIZE {
                return Err(VfsFailure::new(
                    VfsErrorCode::TooLarge,
                    "file exceeds the 16 MiB VFS read limit",
                ));
            }
            let modified_ts =
                modified_timestamp(metadata.modified().map_err(VfsFailure::from_io)?)?;
            Ok((content, modified_ts))
        })
        .await
        .map_err(|error| VfsFailure::new(VfsErrorCode::Io, error.to_string()))?
    }

    pub async fn write_file(
        &self,
        requested: String,
        content: Vec<u8>,
        expected_modified_ts: Option<u64>,
    ) -> Result<u64, VfsFailure> {
        if content.len() > MAX_FILE_SIZE {
            return Err(VfsFailure::new(
                VfsErrorCode::TooLarge,
                "file exceeds the 16 MiB VFS write limit",
            ));
        }
        let resolved = self.resolve(&requested)?;
        let write_lock = Arc::clone(&self.write_lock);
        let _write_guard = write_lock.lock().await;
        tokio::task::spawn_blocking(move || {
            let file_name = resolved.relative.file_name().ok_or_else(|| {
                VfsFailure::new(VfsErrorCode::InvalidPath, "a file path is required")
            })?;
            let parent = resolved
                .relative
                .parent()
                .filter(|path| !path.as_os_str().is_empty())
                .unwrap_or_else(|| Path::new("."));
            let parent_dir = resolved
                .root
                .dir
                .open_dir(parent)
                .map_err(VfsFailure::from_io)?;

            let existing = parent_dir.symlink_metadata(file_name);
            let existing_permissions = match existing {
                Ok(link_metadata) => {
                    if link_metadata.is_dir() {
                        return Err(VfsFailure::new(
                            VfsErrorCode::IsADirectory,
                            "cannot write file content to a directory",
                        ));
                    }
                    match parent_dir.metadata(file_name) {
                        Ok(metadata) => {
                            if metadata.is_dir() {
                                return Err(VfsFailure::new(
                                    VfsErrorCode::IsADirectory,
                                    "cannot write file content to a directory",
                                ));
                            }
                            if let Some(expected) = expected_modified_ts {
                                let current = modified_timestamp(
                                    metadata.modified().map_err(VfsFailure::from_io)?,
                                )?;
                                if current != expected {
                                    return Err(VfsFailure::new(
                                        VfsErrorCode::Conflict,
                                        "file changed since it was read",
                                    ));
                                }
                            }
                            Some(metadata.permissions())
                        }
                        Err(error)
                            if link_metadata.file_type().is_symlink()
                                && error.kind() == io::ErrorKind::NotFound
                                && expected_modified_ts.is_none() =>
                        {
                            None
                        }
                        Err(error)
                            if link_metadata.file_type().is_symlink()
                                && error.kind() == io::ErrorKind::PermissionDenied
                                && expected_modified_ts.is_none() =>
                        {
                            None
                        }
                        Err(error) if error.kind() == io::ErrorKind::NotFound => {
                            return Err(VfsFailure::new(
                                VfsErrorCode::Conflict,
                                "file no longer exists",
                            ));
                        }
                        Err(error) => return Err(VfsFailure::from_io(error)),
                    }
                }
                Err(error)
                    if error.kind() == io::ErrorKind::NotFound
                        && expected_modified_ts.is_none() =>
                {
                    None
                }
                Err(error) if error.kind() == io::ErrorKind::NotFound => {
                    return Err(VfsFailure::new(
                        VfsErrorCode::Conflict,
                        "file no longer exists",
                    ));
                }
                Err(error) => return Err(VfsFailure::from_io(error)),
            };

            let temporary_name = format!(".avf-{}.tmp", Uuid::new_v4());
            let mut options = OpenOptions::new();
            options.write(true).create_new(true);
            let mut temporary = parent_dir
                .open_with(&temporary_name, &options)
                .map_err(VfsFailure::from_io)?;
            let write_result = temporary.write_all(&content).and_then(|()| {
                if let Some(permissions) = existing_permissions {
                    temporary.set_permissions(permissions)?;
                }
                temporary.sync_all()
            });
            drop(temporary);
            if let Err(error) = write_result {
                let _ = parent_dir.remove_file(&temporary_name);
                return Err(VfsFailure::from_io(error));
            }
            if let Some(expected) = expected_modified_ts {
                let current = match parent_dir.metadata(file_name) {
                    Ok(metadata) => metadata,
                    Err(error) if error.kind() == io::ErrorKind::NotFound => {
                        let _ = parent_dir.remove_file(&temporary_name);
                        return Err(VfsFailure::new(
                            VfsErrorCode::Conflict,
                            "file no longer exists",
                        ));
                    }
                    Err(error) => {
                        let _ = parent_dir.remove_file(&temporary_name);
                        return Err(VfsFailure::from_io(error));
                    }
                };
                let current_timestamp = match current
                    .modified()
                    .map_err(VfsFailure::from_io)
                    .and_then(modified_timestamp)
                {
                    Ok(timestamp) => timestamp,
                    Err(error) => {
                        let _ = parent_dir.remove_file(&temporary_name);
                        return Err(error);
                    }
                };
                if current_timestamp != expected {
                    let _ = parent_dir.remove_file(&temporary_name);
                    return Err(VfsFailure::new(
                        VfsErrorCode::Conflict,
                        "file changed while the write was being prepared",
                    ));
                }
            }
            if let Err(error) = parent_dir.rename(&temporary_name, &parent_dir, file_name) {
                let _ = parent_dir.remove_file(&temporary_name);
                return Err(VfsFailure::from_io(error));
            }
            let modified = parent_dir
                .metadata(file_name)
                .map_err(VfsFailure::from_io)?;
            modified_timestamp(modified.modified().map_err(VfsFailure::from_io)?)
        })
        .await
        .map_err(|error| VfsFailure::new(VfsErrorCode::Io, error.to_string()))?
    }

    pub async fn validate_watch(&self, requested: &str) -> Result<(String, PathBuf), VfsFailure> {
        let resolved = self.resolve(requested)?;
        let root = resolved.root.clone();
        let relative = resolved.relative.clone();
        let absolute = resolved.absolute.clone();
        tokio::task::spawn_blocking(move || {
            let metadata = root.dir.metadata(&relative).map_err(VfsFailure::from_io)?;
            if !metadata.is_dir() {
                return Err(VfsFailure::new(
                    VfsErrorCode::NotADirectory,
                    "watch path must be a directory",
                ));
            }
            let canonical = std::fs::canonicalize(&absolute).map_err(VfsFailure::from_io)?;
            if !canonical.starts_with(&root.path) {
                return Err(VfsFailure::new(
                    VfsErrorCode::PermissionDenied,
                    "watch path resolves outside its configured project root",
                ));
            }
            Ok((absolute.to_string_lossy().into_owned(), canonical))
        })
        .await
        .map_err(|error| VfsFailure::new(VfsErrorCode::Io, error.to_string()))?
    }
}

impl VfsFailure {
    fn from_io(error: io::Error) -> Self {
        let code = match error.kind() {
            io::ErrorKind::NotFound => VfsErrorCode::NotFound,
            io::ErrorKind::PermissionDenied => VfsErrorCode::PermissionDenied,
            io::ErrorKind::AlreadyExists => VfsErrorCode::AlreadyExists,
            io::ErrorKind::IsADirectory => VfsErrorCode::IsADirectory,
            io::ErrorKind::NotADirectory => VfsErrorCode::NotADirectory,
            _ => VfsErrorCode::Io,
        };
        Self::new(code, error.to_string())
    }
}

fn modified_timestamp(time: cap_std::time::SystemTime) -> Result<u64, VfsFailure> {
    let duration = time
        .into_std()
        .duration_since(std::time::SystemTime::UNIX_EPOCH)
        .map_err(|error| VfsFailure::new(VfsErrorCode::Io, error.to_string()))?;
    u64::try_from(duration.as_millis()).map_err(|error| {
        VfsFailure::new(
            VfsErrorCode::Io,
            format!("file modification timestamp is out of range: {error}"),
        )
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::symlink;

    #[tokio::test]
    async fn vfs_reads_lists_and_atomically_writes_with_conflict_checks() {
        let temp = tempfile::tempdir().unwrap();
        let root = temp.path().join("project");
        std::fs::create_dir(&root).unwrap();
        let vfs = Vfs::new(std::slice::from_ref(&root)).unwrap();
        let file = root.join("hello.txt");
        let path = file.to_string_lossy().into_owned();

        let timestamp = vfs
            .write_file(path.clone(), b"hello".to_vec(), None)
            .await
            .unwrap();
        let (content, read_timestamp) = vfs.read_file(path.clone()).await.unwrap();
        assert_eq!(content, b"hello");
        assert_eq!(read_timestamp, timestamp);
        assert_eq!(
            vfs.list_dir(root.to_string_lossy().into_owned())
                .await
                .unwrap()
                .len(),
            1
        );

        let conflict = vfs
            .write_file(
                path,
                b"replacement".to_vec(),
                Some(timestamp.saturating_add(1)),
            )
            .await
            .unwrap_err();
        assert_eq!(conflict.code, VfsErrorCode::Conflict);
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn atomic_write_preserves_existing_permissions() {
        use std::os::unix::fs::{MetadataExt, PermissionsExt};

        let temp = tempfile::tempdir().unwrap();
        let root = temp.path().join("project");
        std::fs::create_dir(&root).unwrap();
        let file = root.join("script.sh");
        std::fs::write(&file, b"old contents").unwrap();
        std::fs::set_permissions(&file, std::fs::Permissions::from_mode(0o751)).unwrap();
        let vfs = Vfs::new(std::slice::from_ref(&root)).unwrap();
        let (old_content, timestamp) = vfs
            .read_file(file.to_string_lossy().into_owned())
            .await
            .unwrap();
        assert_eq!(old_content, b"old contents");

        vfs.write_file(
            file.to_string_lossy().into_owned(),
            b"new contents".to_vec(),
            Some(timestamp),
        )
        .await
        .unwrap();
        assert_eq!(std::fs::metadata(file).unwrap().mode() & 0o777, 0o751);
    }

    #[tokio::test]
    async fn vfs_rejects_paths_outside_roots_and_symlink_escapes() {
        let temp = tempfile::tempdir().unwrap();
        let root = temp.path().join("project");
        let outside = temp.path().join("outside.txt");
        std::fs::create_dir(&root).unwrap();
        std::fs::write(&outside, b"private").unwrap();
        symlink(&outside, root.join("escape.txt")).unwrap();
        let vfs = Vfs::new(std::slice::from_ref(&root)).unwrap();

        let outside_read = vfs
            .read_file(outside.to_string_lossy().into_owned())
            .await
            .unwrap_err();
        assert_eq!(outside_read.code, VfsErrorCode::PermissionDenied);
        let symlink_read = vfs
            .read_file(root.join("escape.txt").to_string_lossy().into_owned())
            .await
            .unwrap_err();
        assert_eq!(symlink_read.code, VfsErrorCode::PermissionDenied);
        assert_eq!(
            vfs.resolve(&root.join("../outside.txt").to_string_lossy())
                .err()
                .unwrap()
                .code,
            VfsErrorCode::InvalidPath
        );
    }

    #[tokio::test]
    async fn vfs_enforces_file_size_limits() {
        let temp = tempfile::tempdir().unwrap();
        let root = temp.path().join("project");
        std::fs::create_dir(&root).unwrap();
        let vfs = Vfs::new(std::slice::from_ref(&root)).unwrap();
        let too_large = root.join("too-large");
        std::fs::write(&too_large, vec![0; MAX_FILE_SIZE + 1]).unwrap();
        let read_error = vfs
            .read_file(too_large.to_string_lossy().into_owned())
            .await
            .unwrap_err();
        assert_eq!(read_error.code, VfsErrorCode::TooLarge);
        let error = vfs
            .write_file(
                root.join("too-large").to_string_lossy().into_owned(),
                vec![0; MAX_FILE_SIZE + 1],
                None,
            )
            .await
            .unwrap_err();
        assert_eq!(error.code, VfsErrorCode::TooLarge);
    }
}
