#[cfg(feature = "native-protocol")]
use avf_protocol::GitStatusEntry;
use avf_protocol::{
    EntryKind, FileEntry, GitFileStatus, VfsErrorCode, MAX_CHUNK_SIZE, MAX_FILE_SIZE,
    MAX_PAGED_FILE_SIZE,
};
use cap_std::{
    ambient_authority,
    fs::{Dir, OpenOptions},
};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    fs,
    io::{self, Read, Write},
    path::{Component, Path, PathBuf},
    sync::Arc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use uuid::Uuid;

#[derive(Clone)]
struct WorkspaceRoot {
    path: PathBuf,
    dir: Arc<Dir>,
}

fn copy_entry(
    source_dir: &Dir,
    source_path: &Path,
    destination_dir: &Dir,
    destination_name: &Path,
) -> io::Result<()> {
    let metadata = source_dir.symlink_metadata(source_path)?;
    let file_type = metadata.file_type();
    if file_type.is_symlink() {
        let target = source_dir.read_link_contents(source_path)?;
        destination_dir.symlink_contents(target, destination_name)
    } else if file_type.is_dir() {
        destination_dir.create_dir(destination_name)?;
        let source = source_dir.open_dir(source_path)?;
        let destination = destination_dir.open_dir(destination_name)?;
        for entry in source.read_dir(".")? {
            let entry = entry?;
            let name = entry.file_name();
            copy_entry(&source, Path::new(&name), &destination, Path::new(&name))?;
        }
        destination.set_permissions(".", metadata.permissions())
    } else if file_type.is_file() {
        source_dir.copy(source_path, destination_dir, destination_name)?;
        Ok(())
    } else {
        Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "trash copy fallback does not support special files",
        ))
    }
}

fn remove_entry(directory: &Dir, path: &Path) -> io::Result<()> {
    let metadata = directory.symlink_metadata(path)?;
    if metadata.is_dir() && !metadata.file_type().is_symlink() {
        directory.remove_dir_all(path)
    } else {
        directory.remove_file(path)
    }
}

fn current_unix_millis() -> Result<u64, VfsFailure> {
    let elapsed = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|error| {
            VfsFailure::new(
                VfsErrorCode::Io,
                format!("system clock is invalid: {error}"),
            )
        })?;
    u64::try_from(elapsed.as_millis())
        .map_err(|error| VfsFailure::new(VfsErrorCode::Io, error.to_string()))
}

fn trash_base(create: bool) -> Result<PathBuf, VfsFailure> {
    let home = std::env::var_os("HOME")
        .map(PathBuf::from)
        .filter(|path| path.is_absolute())
        .ok_or_else(|| VfsFailure::new(VfsErrorCode::Io, "HOME is not set to an absolute path"))?;
    let canonical_home = fs::canonicalize(&home).map_err(VfsFailure::from_io)?;
    let base = canonical_home
        .join(".local")
        .join("share")
        .join("avf-studio")
        .join("trash");
    if create {
        fs::create_dir_all(&base).map_err(VfsFailure::from_io)?;
    }
    let canonical_base = fs::canonicalize(&base).map_err(VfsFailure::from_io)?;
    if canonical_base != base || !canonical_base.starts_with(&canonical_home) {
        return Err(VfsFailure::new(
            VfsErrorCode::PermissionDenied,
            "trash directory must be a real directory under HOME",
        ));
    }
    let metadata = fs::symlink_metadata(&canonical_base).map_err(VfsFailure::from_io)?;
    if !metadata.is_dir() || metadata.file_type().is_symlink() {
        return Err(VfsFailure::new(
            VfsErrorCode::PermissionDenied,
            "trash path is not a safe directory",
        ));
    }
    Ok(canonical_base)
}

fn trash_item_path(trash_id: Uuid) -> Result<PathBuf, VfsFailure> {
    Ok(trash_base(false)?.join(trash_id.to_string()))
}

#[derive(Clone)]
pub struct Vfs {
    roots: Arc<Vec<WorkspaceRoot>>,
    write_lock: Arc<tokio::sync::Mutex<()>>,
    trash_records: Arc<tokio::sync::Mutex<HashMap<Uuid, TrashRecord>>>,
    git_status: Arc<tokio::sync::RwLock<HashMap<PathBuf, GitFileStatus>>>,
}

#[derive(Clone)]
struct TrashRecord {
    original_path: PathBuf,
    trash_directory: PathBuf,
    expires_at: SystemTime,
}

#[derive(Deserialize, Serialize)]
struct PersistedTrashRecord {
    original_path: String,
    expires_at_unix_ms: u64,
}

#[derive(Clone)]
pub struct ResolvedPath {
    root: WorkspaceRoot,
    relative: PathBuf,
    pub absolute: PathBuf,
}

pub struct WriteStage {
    resolved: ResolvedPath,
    stage_relative: PathBuf,
    size: usize,
    expected_modified_ts: Option<u64>,
}

impl Drop for WriteStage {
    fn drop(&mut self) {
        let _ = self.resolved.root.dir.remove_file(&self.stage_relative);
    }
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
            trash_records: Arc::new(tokio::sync::Mutex::new(HashMap::new())),
            git_status: Arc::new(tokio::sync::RwLock::new(HashMap::new())),
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
        let git_status = self.git_status.read().await.clone();
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
                    path: resolved.absolute.join(&name).to_string_lossy().into_owned(),
                    is_dir: file_type.is_dir(),
                    is_symlink: file_type.is_symlink(),
                    size: metadata.len(),
                    modified_ts,
                    mode: permission_mode(&metadata.permissions()),
                    is_hidden: name.starts_with('.'),
                    git_status: git_status.get(&resolved.absolute.join(&name)).copied(),
                });
            }
            result.sort_by(|left, right| left.name.cmp(&right.name));
            Ok(result)
        })
        .await
        .map_err(|error| VfsFailure::new(VfsErrorCode::Io, error.to_string()))?
    }

    pub async fn roots(&self) -> Vec<String> {
        self.roots
            .iter()
            .map(|root| root.path.to_string_lossy().into_owned())
            .collect()
    }

    pub fn workspace_root(&self, requested: &str) -> Result<PathBuf, VfsFailure> {
        self.resolve(requested).map(|resolved| resolved.root.path)
    }

    #[cfg(feature = "native-protocol")]
    pub async fn cache_git_status(
        &self,
        repository: &str,
        entries: &[GitStatusEntry],
    ) -> Result<(), VfsFailure> {
        let repository = self.resolve(repository)?;
        let mut refreshed = HashMap::with_capacity(entries.len());
        for entry in entries {
            let path = repository.absolute.join(&entry.path);
            let resolved = self.resolve(&path.to_string_lossy())?;
            if resolved.root.path != repository.root.path {
                return Err(VfsFailure::new(
                    VfsErrorCode::PermissionDenied,
                    "Git status path escaped its workspace root",
                ));
            }
            refreshed.insert(resolved.absolute, entry.status);
        }
        let mut cache = self.git_status.write().await;
        cache.retain(|path, _| !path.starts_with(&repository.absolute));
        cache.extend(refreshed);
        Ok(())
    }

    pub async fn stat(&self, requested: String) -> Result<FileEntry, VfsFailure> {
        let resolved = self.resolve(&requested)?;
        let git_status = self.git_status.read().await.clone();
        tokio::task::spawn_blocking(move || {
            let metadata = resolved
                .root
                .dir
                .symlink_metadata(&resolved.relative)
                .map_err(VfsFailure::from_io)?;
            let name = resolved
                .absolute
                .file_name()
                .and_then(|name| name.to_str())
                .unwrap_or_else(|| resolved.absolute.to_str().unwrap_or_default())
                .to_owned();
            Ok(FileEntry {
                name: name.clone(),
                path: resolved.absolute.to_string_lossy().into_owned(),
                is_dir: metadata.is_dir(),
                is_symlink: metadata.file_type().is_symlink(),
                size: metadata.len(),
                modified_ts: modified_timestamp(metadata.modified().map_err(VfsFailure::from_io)?)?,
                mode: permission_mode(&metadata.permissions()),
                is_hidden: name.starts_with('.'),
                git_status: git_status.get(&resolved.absolute).copied(),
            })
        })
        .await
        .map_err(|error| VfsFailure::new(VfsErrorCode::Io, error.to_string()))?
    }

    pub async fn read_file_range(
        &self,
        requested: String,
        offset: u64,
        len: u32,
    ) -> Result<(Vec<u8>, bool, u64, u64), VfsFailure> {
        if len as usize > MAX_CHUNK_SIZE {
            return Err(VfsFailure::new(
                VfsErrorCode::TooLarge,
                "read chunk exceeds the 4 MiB limit",
            ));
        }
        let resolved = self.resolve(&requested)?;
        tokio::task::spawn_blocking(move || {
            use std::io::{Seek, SeekFrom};

            let mut file = resolved
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
            if metadata.len() > MAX_PAGED_FILE_SIZE as u64 {
                return Err(VfsFailure::new(
                    VfsErrorCode::TooLarge,
                    "file exceeds the 256 MiB VFS paging limit",
                ));
            }
            let modified_ts =
                modified_timestamp(metadata.modified().map_err(VfsFailure::from_io)?)?;
            file.seek(SeekFrom::Start(offset))
                .map_err(VfsFailure::from_io)?;
            let mut content =
                vec![0; (len as u64).min(metadata.len().saturating_sub(offset)) as usize];
            file.read_exact(&mut content).map_err(VfsFailure::from_io)?;
            let eof = offset.saturating_add(content.len() as u64) >= metadata.len();
            Ok((content, eof, modified_ts, metadata.len()))
        })
        .await
        .map_err(|error| VfsFailure::new(VfsErrorCode::Io, error.to_string()))?
    }

    pub async fn begin_write_stage(
        &self,
        requested: String,
        expected_modified_ts: Option<u64>,
    ) -> Result<WriteStage, VfsFailure> {
        let resolved = self.resolve(&requested)?;
        resolved
            .relative
            .file_name()
            .ok_or_else(|| VfsFailure::new(VfsErrorCode::InvalidPath, "a file path is required"))?;
        let parent = resolved
            .relative
            .parent()
            .filter(|parent| !parent.as_os_str().is_empty())
            .unwrap_or_else(|| Path::new("."));
        let parent_dir = resolved
            .root
            .dir
            .open_dir(parent)
            .map_err(VfsFailure::from_io)?;
        let stage_name = format!(".avf-{}.stage", Uuid::new_v4());
        let mut options = OpenOptions::new();
        options.write(true).create_new(true);
        parent_dir
            .open_with(&stage_name, &options)
            .map_err(VfsFailure::from_io)?;
        let stage_relative = parent.join(stage_name);
        Ok(WriteStage {
            resolved,
            stage_relative,
            size: 0,
            expected_modified_ts,
        })
    }

    pub async fn append_write_stage(
        &self,
        stage: &mut WriteStage,
        offset: u64,
        content: Vec<u8>,
    ) -> Result<(), VfsFailure> {
        if content.len() > MAX_CHUNK_SIZE {
            return Err(VfsFailure::new(
                VfsErrorCode::TooLarge,
                "write chunk exceeds the 4 MiB limit",
            ));
        }
        if offset != stage.size as u64 {
            return Err(VfsFailure::new(
                VfsErrorCode::Conflict,
                "write chunks must be contiguous and in order",
            ));
        }
        if stage.size.saturating_add(content.len()) > MAX_FILE_SIZE {
            return Err(VfsFailure::new(
                VfsErrorCode::TooLarge,
                "file exceeds the 16 MiB VFS write limit",
            ));
        }
        let root = stage.resolved.root.clone();
        let stage_relative = stage.stage_relative.clone();
        let added = content.len();
        tokio::task::spawn_blocking(move || {
            let mut options = OpenOptions::new();
            options.write(true).append(true);
            let mut file = root
                .dir
                .open_with(&stage_relative, &options)
                .map_err(VfsFailure::from_io)?;
            file.write_all(&content).map_err(VfsFailure::from_io)?;
            file.sync_all().map_err(VfsFailure::from_io)
        })
        .await
        .map_err(|error| VfsFailure::new(VfsErrorCode::Io, error.to_string()))??;
        stage.size += added;
        Ok(())
    }

    pub async fn flush_write_stage(&self, stage: WriteStage) -> Result<u64, VfsFailure> {
        let root = stage.resolved.root.clone();
        let stage_relative = stage.stage_relative.clone();
        let size = stage.size;
        let path = stage.resolved.absolute.to_string_lossy().into_owned();
        let expected_modified_ts = stage.expected_modified_ts;
        let content = tokio::task::spawn_blocking(move || {
            let file = root
                .dir
                .open(&stage_relative)
                .map_err(VfsFailure::from_io)?;
            let mut content = Vec::with_capacity(size);
            file.take((MAX_FILE_SIZE + 1) as u64)
                .read_to_end(&mut content)
                .map_err(VfsFailure::from_io)?;
            if content.len() != size {
                return Err(VfsFailure::new(
                    VfsErrorCode::Io,
                    "staged file changed before flush",
                ));
            }
            Ok(content)
        })
        .await
        .map_err(|error| VfsFailure::new(VfsErrorCode::Io, error.to_string()))??;
        self.write_file(path, content, expected_modified_ts).await
    }

    pub async fn create(&self, requested: String, kind: EntryKind) -> Result<(), VfsFailure> {
        let resolved = self.resolve(&requested)?;
        tokio::task::spawn_blocking(move || match kind {
            EntryKind::Dir => resolved
                .root
                .dir
                .create_dir_all(&resolved.relative)
                .map_err(VfsFailure::from_io),
            EntryKind::File => {
                let mut options = OpenOptions::new();
                options.write(true).create_new(true);
                resolved
                    .root
                    .dir
                    .open_with(&resolved.relative, &options)
                    .map(drop)
                    .map_err(VfsFailure::from_io)
            }
        })
        .await
        .map_err(|error| VfsFailure::new(VfsErrorCode::Io, error.to_string()))?
    }

    pub async fn delete(
        &self,
        requested: String,
        recursive: bool,
        to_trash: bool,
    ) -> Result<Option<Uuid>, VfsFailure> {
        if to_trash {
            return self.move_to_trash(requested).await.map(Some);
        }
        let resolved = self.resolve(&requested)?;
        if resolved.relative == Path::new(".") {
            return Err(VfsFailure::new(
                VfsErrorCode::PermissionDenied,
                "workspace roots cannot be deleted",
            ));
        }
        tokio::task::spawn_blocking(move || {
            let metadata = resolved
                .root
                .dir
                .symlink_metadata(&resolved.relative)
                .map_err(VfsFailure::from_io)?;
            if metadata.is_dir() && !metadata.file_type().is_symlink() {
                if recursive {
                    resolved
                        .root
                        .dir
                        .remove_dir_all(&resolved.relative)
                        .map_err(VfsFailure::from_io)
                } else {
                    resolved
                        .root
                        .dir
                        .remove_dir(&resolved.relative)
                        .map_err(VfsFailure::from_io)
                }
            } else {
                resolved
                    .root
                    .dir
                    .remove_file(&resolved.relative)
                    .map_err(VfsFailure::from_io)
            }
            .map(|()| None)
        })
        .await
        .map_err(|error| VfsFailure::new(VfsErrorCode::Io, error.to_string()))?
    }

    pub async fn restore(&self, trash_id: Uuid) -> Result<(), VfsFailure> {
        let record = match self.trash_records.lock().await.get(&trash_id).cloned() {
            Some(record) => record,
            None => self.load_trash_record(trash_id).await?,
        };
        if SystemTime::now() >= record.expires_at {
            self.trash_records.lock().await.remove(&trash_id);
            let directory = record.trash_directory.clone();
            tokio::task::spawn_blocking(move || fs::remove_dir_all(directory))
                .await
                .map_err(|error| VfsFailure::new(VfsErrorCode::Io, error.to_string()))?
                .map_err(VfsFailure::from_io)?;
            return Err(VfsFailure::new(
                VfsErrorCode::NotFound,
                "trash undo window expired",
            ));
        }
        let original = self.resolve(record.original_path.to_string_lossy().as_ref())?;
        let trash_directory = record.trash_directory.clone();
        tokio::task::spawn_blocking(move || {
            let file_name = original.absolute.file_name().ok_or_else(|| {
                VfsFailure::new(VfsErrorCode::InvalidPath, "a file path is required")
            })?;
            let parent = original
                .relative
                .parent()
                .filter(|parent| !parent.as_os_str().is_empty())
                .unwrap_or_else(|| Path::new("."));
            let parent_dir = original
                .root
                .dir
                .open_dir(parent)
                .map_err(VfsFailure::from_io)?;
            if parent_dir
                .try_exists(file_name)
                .map_err(VfsFailure::from_io)?
            {
                return Err(VfsFailure::new(
                    VfsErrorCode::AlreadyExists,
                    "cannot restore because the original path already exists",
                ));
            }
            let trash_dir = Dir::open_ambient_dir(&trash_directory, ambient_authority())
                .map_err(VfsFailure::from_io)?;
            if let Err(error) = trash_dir.rename("payload", &parent_dir, file_name) {
                if error.kind() != io::ErrorKind::CrossesDevices {
                    return Err(VfsFailure::from_io(error));
                }
                if let Err(copy_error) = copy_entry(
                    &trash_dir,
                    Path::new("payload"),
                    &parent_dir,
                    Path::new(file_name),
                ) {
                    let _ = remove_entry(&parent_dir, Path::new(file_name));
                    return Err(VfsFailure::from_io(copy_error));
                }
                remove_entry(&trash_dir, Path::new("payload")).map_err(VfsFailure::from_io)?;
            }
            fs::remove_dir_all(&trash_directory).map_err(VfsFailure::from_io)
        })
        .await
        .map_err(|error| VfsFailure::new(VfsErrorCode::Io, error.to_string()))??;
        self.trash_records.lock().await.remove(&trash_id);
        Ok(())
    }

    async fn load_trash_record(&self, trash_id: Uuid) -> Result<TrashRecord, VfsFailure> {
        let directory = trash_item_path(trash_id)?;
        let metadata = tokio::task::spawn_blocking({
            let directory = directory.clone();
            move || {
                let canonical_directory =
                    fs::canonicalize(&directory).map_err(VfsFailure::from_io)?;
                if canonical_directory != directory || !canonical_directory.is_dir() {
                    return Err(VfsFailure::new(
                        VfsErrorCode::PermissionDenied,
                        "trash item directory is not a safe directory",
                    ));
                }
                let item_dir = Dir::open_ambient_dir(&canonical_directory, ambient_authority())
                    .map_err(VfsFailure::from_io)?;
                let metadata = item_dir
                    .symlink_metadata(".avf-metadata.json")
                    .map_err(VfsFailure::from_io)?;
                if !metadata.is_file() || metadata.file_type().is_symlink() || metadata.len() > 4096
                {
                    return Err(VfsFailure::new(
                        VfsErrorCode::PermissionDenied,
                        "trash metadata is not a safe regular file",
                    ));
                }
                let contents = item_dir
                    .read(".avf-metadata.json")
                    .map_err(VfsFailure::from_io)?;
                serde_json::from_slice::<PersistedTrashRecord>(&contents).map_err(|error| {
                    VfsFailure::new(
                        VfsErrorCode::InvalidPath,
                        format!("trash metadata is invalid: {error}"),
                    )
                })
            }
        })
        .await
        .map_err(|error| VfsFailure::new(VfsErrorCode::Io, error.to_string()))??;
        let original_path = self.resolve(&metadata.original_path)?.absolute;
        let expires_at = UNIX_EPOCH
            .checked_add(Duration::from_millis(metadata.expires_at_unix_ms))
            .ok_or_else(|| {
                VfsFailure::new(
                    VfsErrorCode::InvalidPath,
                    "trash expiry timestamp is invalid",
                )
            })?;
        Ok(TrashRecord {
            original_path,
            trash_directory: directory,
            expires_at,
        })
    }

    async fn move_to_trash(&self, requested: String) -> Result<Uuid, VfsFailure> {
        let resolved = self.resolve(&requested)?;
        if resolved.relative == Path::new(".") {
            return Err(VfsFailure::new(
                VfsErrorCode::PermissionDenied,
                "workspace roots cannot be moved to trash",
            ));
        }
        let original_path = resolved.absolute.clone();
        let (trash_id, trash_directory, expires_at_unix_ms) =
            tokio::task::spawn_blocking(move || {
                let base = trash_base(true)?;
                let trash_id = Uuid::new_v4();
                let trash_directory = base.join(trash_id.to_string());
                fs::create_dir(&trash_directory).map_err(VfsFailure::from_io)?;
                set_private_directory_permissions(&trash_directory).map_err(VfsFailure::from_io)?;
                let trash_dir = Dir::open_ambient_dir(&trash_directory, ambient_authority())
                    .map_err(VfsFailure::from_io)?;
                let expires_at_unix_ms = current_unix_millis()?.saturating_add(10_000);
                let metadata = PersistedTrashRecord {
                    original_path: resolved.absolute.to_string_lossy().into_owned(),
                    expires_at_unix_ms,
                };
                let metadata = serde_json::to_vec(&metadata).map_err(|error| {
                    VfsFailure::new(
                        VfsErrorCode::Io,
                        format!("could not encode trash metadata: {error}"),
                    )
                })?;
                if let Err(error) = trash_dir.write(".avf-metadata.json", metadata) {
                    let _ = fs::remove_dir_all(&trash_directory);
                    return Err(VfsFailure::from_io(error));
                }
                if let Err(error) =
                    resolved
                        .root
                        .dir
                        .rename(&resolved.relative, &trash_dir, "payload")
                {
                    if error.kind() != io::ErrorKind::CrossesDevices {
                        let _ = fs::remove_dir_all(&trash_directory);
                        return Err(VfsFailure::from_io(error));
                    }
                    if let Err(copy_error) = copy_entry(
                        &resolved.root.dir,
                        &resolved.relative,
                        &trash_dir,
                        Path::new("payload"),
                    ) {
                        let _ = remove_entry(&trash_dir, Path::new("payload"));
                        let _ = fs::remove_dir_all(&trash_directory);
                        return Err(VfsFailure::from_io(copy_error));
                    }
                    if let Err(remove_error) = remove_entry(&resolved.root.dir, &resolved.relative)
                    {
                        let _ = remove_entry(&trash_dir, Path::new("payload"));
                        let _ = fs::remove_dir_all(&trash_directory);
                        return Err(VfsFailure::from_io(remove_error));
                    }
                }
                Ok((trash_id, trash_directory, expires_at_unix_ms))
            })
            .await
            .map_err(|error| VfsFailure::new(VfsErrorCode::Io, error.to_string()))??;
        let expires_at = UNIX_EPOCH + Duration::from_millis(expires_at_unix_ms);
        self.trash_records.lock().await.insert(
            trash_id,
            TrashRecord {
                original_path,
                trash_directory,
                expires_at,
            },
        );
        Ok(trash_id)
    }

    pub async fn rename(
        &self,
        from: String,
        to: String,
        overwrite: bool,
    ) -> Result<(), VfsFailure> {
        let source = self.resolve(&from)?;
        let destination = self.resolve(&to)?;
        if source.root.path != destination.root.path {
            return Err(VfsFailure::new(
                VfsErrorCode::PermissionDenied,
                "rename across workspace roots is not allowed",
            ));
        }
        tokio::task::spawn_blocking(move || {
            let source_name = source.relative.file_name().ok_or_else(|| {
                VfsFailure::new(VfsErrorCode::InvalidPath, "a source file path is required")
            })?;
            let source_parent = source
                .relative
                .parent()
                .filter(|parent| !parent.as_os_str().is_empty())
                .unwrap_or_else(|| Path::new("."));
            let destination_name = destination.relative.file_name().ok_or_else(|| {
                VfsFailure::new(
                    VfsErrorCode::InvalidPath,
                    "a destination file path is required",
                )
            })?;
            let destination_parent = destination
                .relative
                .parent()
                .filter(|parent| !parent.as_os_str().is_empty())
                .unwrap_or_else(|| Path::new("."));
            let source_dir = source
                .root
                .dir
                .open_dir(source_parent)
                .map_err(VfsFailure::from_io)?;
            let destination_dir = destination
                .root
                .dir
                .open_dir(destination_parent)
                .map_err(VfsFailure::from_io)?;
            if destination_dir
                .try_exists(destination_name)
                .map_err(VfsFailure::from_io)?
                && !overwrite
            {
                return Err(VfsFailure::new(
                    VfsErrorCode::AlreadyExists,
                    "destination already exists",
                ));
            }
            source_dir
                .rename(source_name, &destination_dir, destination_name)
                .map_err(VfsFailure::from_io)
        })
        .await
        .map_err(|error| VfsFailure::new(VfsErrorCode::Io, error.to_string()))?
    }

    pub async fn copy(&self, from: String, to: String, overwrite: bool) -> Result<(), VfsFailure> {
        let source = self.resolve(&from)?;
        let destination = self.resolve(&to)?;
        if source.root.path != destination.root.path {
            return Err(VfsFailure::new(
                VfsErrorCode::PermissionDenied,
                "copy across workspace roots is not allowed",
            ));
        }
        tokio::task::spawn_blocking(move || {
            let source_name = source.relative.file_name().ok_or_else(|| {
                VfsFailure::new(VfsErrorCode::InvalidPath, "a source file path is required")
            })?;
            let source_parent = source
                .relative
                .parent()
                .filter(|parent| !parent.as_os_str().is_empty())
                .unwrap_or_else(|| Path::new("."));
            let destination_name = destination.relative.file_name().ok_or_else(|| {
                VfsFailure::new(
                    VfsErrorCode::InvalidPath,
                    "a destination file path is required",
                )
            })?;
            let destination_parent = destination
                .relative
                .parent()
                .filter(|parent| !parent.as_os_str().is_empty())
                .unwrap_or_else(|| Path::new("."));
            let source_dir = source
                .root
                .dir
                .open_dir(source_parent)
                .map_err(VfsFailure::from_io)?;
            let source_metadata = source_dir
                .symlink_metadata(source_name)
                .map_err(VfsFailure::from_io)?;
            if source_metadata.file_type().is_symlink() {
                return Err(VfsFailure::new(
                    VfsErrorCode::PermissionDenied,
                    "copying symlinks is not allowed",
                ));
            }
            if source_metadata.is_dir() {
                return Err(VfsFailure::new(
                    VfsErrorCode::Unsupported,
                    "directory copy is not implemented",
                ));
            }
            let destination_dir = destination
                .root
                .dir
                .open_dir(destination_parent)
                .map_err(VfsFailure::from_io)?;
            if destination_dir
                .try_exists(destination_name)
                .map_err(VfsFailure::from_io)?
            {
                if !overwrite {
                    return Err(VfsFailure::new(
                        VfsErrorCode::AlreadyExists,
                        "destination already exists",
                    ));
                }
                let metadata = destination_dir
                    .symlink_metadata(destination_name)
                    .map_err(VfsFailure::from_io)?;
                if metadata.is_dir() && !metadata.file_type().is_symlink() {
                    return Err(VfsFailure::new(
                        VfsErrorCode::IsADirectory,
                        "copy destination is a directory",
                    ));
                }
                destination_dir
                    .remove_file(destination_name)
                    .map_err(VfsFailure::from_io)?;
            }
            source_dir
                .copy(source_name, &destination_dir, destination_name)
                .map(|_| ())
                .map_err(VfsFailure::from_io)
        })
        .await
        .map_err(|error| VfsFailure::new(VfsErrorCode::Io, error.to_string()))?
    }

    #[cfg(test)]
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

#[cfg(unix)]
fn permission_mode(permissions: &cap_std::fs::Permissions) -> u32 {
    use cap_std::fs::PermissionsExt;
    permissions.mode()
}

#[cfg(unix)]
fn set_private_directory_permissions(path: &Path) -> io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o700))
}

#[cfg(not(unix))]
fn set_private_directory_permissions(path: &Path) -> io::Result<()> {
    let mut permissions = fs::metadata(path)?.permissions();
    permissions.set_readonly(false);
    fs::set_permissions(path, permissions)
}

#[cfg(not(unix))]
fn permission_mode(permissions: &cap_std::fs::Permissions) -> u32 {
    if permissions.readonly() {
        0o444
    } else {
        0o666
    }
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

    #[tokio::test]
    async fn vfs_paginates_large_files_and_reports_metadata() {
        let temp = tempfile::tempdir().unwrap();
        let root = temp.path().join("project");
        std::fs::create_dir(&root).unwrap();
        let file = root.join(".large.log");
        std::fs::write(&file, vec![b'x'; 20 * 1024 * 1024]).unwrap();
        let vfs = Vfs::new(std::slice::from_ref(&root)).unwrap();

        let (chunk, eof, _, size) = vfs
            .read_file_range(file.to_string_lossy().into_owned(), 4 * 1024 * 1024, 32)
            .await
            .unwrap();
        assert_eq!(chunk, vec![b'x'; 32]);
        assert!(!eof);
        assert_eq!(size, 20 * 1024 * 1024);

        let entry = vfs.stat(file.to_string_lossy().into_owned()).await.unwrap();
        assert!(entry.is_hidden);
        assert_eq!(entry.size, size);
        assert_eq!(entry.git_status, None);
    }

    #[cfg(feature = "native-protocol")]
    #[tokio::test]
    async fn cached_git_status_is_exposed_in_file_metadata() {
        let temp = tempfile::tempdir().unwrap();
        let root = temp.path().join("project");
        std::fs::create_dir(&root).unwrap();
        let file = root.join("new.rs");
        std::fs::write(&file, "fn main() {}").unwrap();
        let vfs = Vfs::new(std::slice::from_ref(&root)).unwrap();
        vfs.cache_git_status(
            &root.to_string_lossy(),
            &[GitStatusEntry {
                path: "new.rs".into(),
                original_path: None,
                index_status: "?".into(),
                worktree_status: "?".into(),
                status: GitFileStatus::Untracked,
            }],
        )
        .await
        .unwrap();

        let entry = vfs.stat(file.to_string_lossy().into_owned()).await.unwrap();
        assert_eq!(entry.git_status, Some(GitFileStatus::Untracked));
        let entries = vfs
            .list_dir(root.to_string_lossy().into_owned())
            .await
            .unwrap();
        assert_eq!(entries[0].git_status, Some(GitFileStatus::Untracked));
        assert!(vfs
            .cache_git_status(
                &root.to_string_lossy(),
                &[GitStatusEntry {
                    path: "../outside".into(),
                    original_path: None,
                    index_status: "?".into(),
                    worktree_status: "?".into(),
                    status: GitFileStatus::Untracked,
                }],
            )
            .await
            .is_err());
    }

    #[test]
    fn trash_copy_fallback_recurses_without_following_symlinks() {
        let source_root = tempfile::tempdir().unwrap();
        let destination_root = tempfile::tempdir().unwrap();
        let source = source_root.path().join("payload");
        std::fs::create_dir(&source).unwrap();
        std::fs::write(source.join("file.txt"), "content").unwrap();
        let outside = tempfile::NamedTempFile::new().unwrap();
        #[cfg(unix)]
        std::os::unix::fs::symlink(outside.path(), source.join("link")).unwrap();
        let source_dir = Dir::open_ambient_dir(source_root.path(), ambient_authority()).unwrap();
        let destination_dir =
            Dir::open_ambient_dir(destination_root.path(), ambient_authority()).unwrap();

        copy_entry(
            &source_dir,
            Path::new("payload"),
            &destination_dir,
            Path::new("payload-copy"),
        )
        .unwrap();
        assert_eq!(
            destination_dir
                .read_to_string("payload-copy/file.txt")
                .unwrap(),
            "content"
        );
        #[cfg(unix)]
        {
            let link = destination_dir
                .symlink_metadata("payload-copy/link")
                .unwrap();
            assert!(link.file_type().is_symlink());
            assert_eq!(
                destination_dir
                    .read_link_contents("payload-copy/link")
                    .unwrap(),
                outside.path()
            );
        }
    }

    #[tokio::test]
    async fn vfs_mutations_are_confined_and_support_overwrite() {
        let temp = tempfile::tempdir().unwrap();
        let first = temp.path().join("first");
        let second = temp.path().join("second");
        std::fs::create_dir(&first).unwrap();
        std::fs::create_dir(&second).unwrap();
        let vfs = Vfs::new(&[first.clone(), second.clone()]).unwrap();
        let source = first.join("source.txt");
        let target = first.join("target.txt");
        let outside_target = second.join("target.txt");

        vfs.create(source.to_string_lossy().into_owned(), EntryKind::File)
            .await
            .unwrap();
        vfs.write_file(
            source.to_string_lossy().into_owned(),
            b"source".to_vec(),
            None,
        )
        .await
        .unwrap();
        std::fs::write(&target, b"old").unwrap();
        vfs.copy(
            source.to_string_lossy().into_owned(),
            target.to_string_lossy().into_owned(),
            true,
        )
        .await
        .unwrap();
        assert_eq!(std::fs::read(&target).unwrap(), b"source");

        let cross_root = vfs
            .rename(
                source.to_string_lossy().into_owned(),
                outside_target.to_string_lossy().into_owned(),
                false,
            )
            .await
            .unwrap_err();
        assert_eq!(cross_root.code, VfsErrorCode::PermissionDenied);

        vfs.rename(
            source.to_string_lossy().into_owned(),
            first.join("renamed.txt").to_string_lossy().into_owned(),
            false,
        )
        .await
        .unwrap();
        vfs.delete(target.to_string_lossy().into_owned(), false, false)
            .await
            .unwrap();
        assert!(!target.exists());
    }

    #[tokio::test]
    async fn trash_delete_can_be_restored_during_undo_window() {
        let temp = tempfile::tempdir().unwrap();
        let root = temp.path().join("project");
        std::fs::create_dir(&root).unwrap();
        let file = root.join("restore-me.txt");
        std::fs::write(&file, b"recoverable").unwrap();
        let vfs = Vfs::new(std::slice::from_ref(&root)).unwrap();

        let trash_id = vfs
            .delete(file.to_string_lossy().into_owned(), false, true)
            .await
            .unwrap()
            .unwrap();
        assert!(!file.exists());
        let restarted_vfs = Vfs::new(std::slice::from_ref(&root)).unwrap();
        restarted_vfs.restore(trash_id).await.unwrap();
        assert_eq!(std::fs::read(&file).unwrap(), b"recoverable");
    }
}
