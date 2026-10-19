use serde::{Deserialize, Serialize};
use std::{
    env, fs,
    io::Write,
    path::{Path, PathBuf},
    process::{Command, Stdio},
    sync::atomic::{AtomicU64, Ordering},
};

const CATALOG_JSON: &str = include_str!("../../../guest/tools/catalog.json");
const UV_INSTALL_GUIDANCE: &str =
    "Install uv (https://docs.astral.sh/uv/getting-started/installation/) and rerun `avf-daemon tools install basedpyright`.";
static TEMP_FILE_SEQUENCE: AtomicU64 = AtomicU64::new(0);

#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct ToolManifest {
    pub name: String,
    pub version: String,
    pub kind: String,
    pub languages: Vec<String>,
    pub command: Vec<String>,
    pub install: InstallSpec,
    #[serde(rename = "settingsSection")]
    pub settings_section: String,
    #[serde(rename = "env_allowlist")]
    pub env_allowlist: Vec<String>,
    #[serde(rename = "initializationOptions")]
    pub initialization_options: serde_json::Value,
}

#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct InstallSpec {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub apt: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub uv: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub npm: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cargo: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub download: Option<DownloadSpec>,
}

#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct DownloadSpec {
    pub url: String,
    #[serde(rename = "sha256")]
    pub sha256: String,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct Catalog {
    schema_version: u32,
    tools: Vec<ToolManifest>,
}

#[derive(Clone, Debug, PartialEq)]
pub enum ToolCommand {
    List,
    Catalog,
    Install(String),
}

pub fn parse_cli(args: &[String]) -> Result<ToolCommand, String> {
    match args {
        [action] if action == "list" => Ok(ToolCommand::List),
        [action] if action == "catalog" => Ok(ToolCommand::Catalog),
        [action, name] if action == "install" => Ok(ToolCommand::Install(name.clone())),
        [action] if action == "--help" || action == "-h" || action == "help" => {
            Err(tool_usage().to_owned())
        }
        _ => Err(tool_usage().to_owned()),
    }
}

pub fn run_cli(args: &[String]) -> Result<(), String> {
    match parse_cli(args)? {
        ToolCommand::List => {
            let installed = list_installed(&default_home()?)?;
            if installed.is_empty() {
                println!("No guest tools are installed.");
            } else {
                for tool in installed {
                    println!("{} {}", tool.name, tool.version);
                }
            }
        }
        ToolCommand::Catalog => {
            let catalog = load_catalog()?;
            println!(
                "{}",
                serde_json::to_string_pretty(&catalog.tools).map_err(|e| e.to_string())?
            );
        }
        ToolCommand::Install(name) => {
            let manifest = install_tool(&default_home()?, &name)?;
            println!("Installed {} {}.", manifest.name, manifest.version);
        }
    }
    Ok(())
}

fn tool_usage() -> &'static str {
    "Usage: avf-daemon tools <list|catalog|install <name>>"
}

pub(crate) fn default_home() -> Result<PathBuf, String> {
    env::var_os("HOME")
        .map(PathBuf::from)
        .filter(|path| path.is_absolute())
        .ok_or_else(|| "HOME must be set to an absolute directory path.".to_owned())
}

fn load_catalog() -> Result<Catalog, String> {
    let catalog: Catalog = serde_json::from_str(CATALOG_JSON)
        .map_err(|e| format!("The bundled guest tool catalog is invalid: {e}"))?;
    if catalog.schema_version != 1 {
        return Err(format!(
            "Unsupported guest tool catalog schema version {}.",
            catalog.schema_version
        ));
    }
    let mut names = std::collections::HashSet::new();
    for manifest in &catalog.tools {
        validate_manifest(manifest)?;
        if !names.insert(manifest.name.as_str()) {
            return Err(format!(
                "The bundled guest tool catalog contains duplicate tool '{}'.",
                manifest.name
            ));
        }
    }
    Ok(catalog)
}

fn catalog_lookup(name: &str) -> Result<ToolManifest, String> {
    validate_tool_name(name)?;
    load_catalog()?
        .tools
        .into_iter()
        .find(|tool| tool.name == name)
        .ok_or_else(|| format!("Tool '{name}' is not in the bundled allowlisted catalog."))
}

fn validate_tool_name(name: &str) -> Result<(), String> {
    let valid = !name.is_empty()
        && name.len() <= 64
        && name
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
        && name.as_bytes()[0].is_ascii_lowercase()
        && name.as_bytes()[name.len() - 1].is_ascii_alphanumeric();
    if valid {
        Ok(())
    } else {
        Err(format!("Invalid tool name '{name}'."))
    }
}

pub fn validate_manifest(manifest: &ToolManifest) -> Result<(), String> {
    validate_tool_name(&manifest.name)?;
    if manifest.version.trim().is_empty() || manifest.version.len() > 128 {
        return Err(format!("Tool '{}' has an invalid version.", manifest.name));
    }
    if !matches!(
        manifest.kind.as_str(),
        "lsp" | "dap" | "formatter" | "linter" | "acp-agent" | "cli"
    ) {
        return Err(format!(
            "Tool '{}' has unsupported kind '{}'.",
            manifest.name, manifest.kind
        ));
    }
    if manifest.command.is_empty()
        || manifest.command[0].is_empty()
        || manifest.command[0]
            .chars()
            .any(|character| matches!(character, '/' | '\\' | '\0'))
        || manifest.command[0] == "."
        || manifest.command[0] == ".."
        || manifest.command.iter().any(|part| part.contains('\0'))
    {
        return Err(format!(
            "Tool '{}' must use a bare executable name, not an executable path.",
            manifest.name
        ));
    }
    if manifest.settings_section.trim().is_empty() || !manifest.initialization_options.is_object() {
        return Err(format!(
            "Tool '{}' has invalid settings or initialization options.",
            manifest.name
        ));
    }
    for variable in &manifest.env_allowlist {
        let mut bytes = variable.bytes();
        let first = bytes.next();
        if !first.is_some_and(|b| b.is_ascii_uppercase() || b == b'_')
            || !bytes.all(|b| b.is_ascii_uppercase() || b.is_ascii_digit() || b == b'_')
        {
            return Err(format!(
                "Tool '{}' has invalid environment allowlist entry '{variable}'.",
                manifest.name
            ));
        }
    }
    validate_install_spec(&manifest.name, &manifest.install)
}

fn validate_install_spec(name: &str, install: &InstallSpec) -> Result<(), String> {
    let package_lists = [
        install.apt.as_ref(),
        install.uv.as_ref(),
        install.npm.as_ref(),
        install.cargo.as_ref(),
    ];
    let configured = package_lists
        .iter()
        .filter(|option| option.is_some())
        .count()
        + usize::from(install.download.is_some());
    if configured != 1 {
        return Err(format!(
            "Tool '{name}' must declare exactly one supported install method."
        ));
    }
    for packages in package_lists.into_iter().flatten() {
        if packages.is_empty()
            || packages.iter().any(|package| {
                package.trim().is_empty()
                    || package.starts_with('-')
                    || package.len() > 256
                    || package.contains('\0')
            })
        {
            return Err(format!(
                "Tool '{name}' has an invalid package install list."
            ));
        }
    }
    if let Some(download) = &install.download {
        let valid_hash =
            download.sha256.len() == 64 && download.sha256.bytes().all(|b| b.is_ascii_hexdigit());
        if !download.url.starts_with("https://") || !valid_hash {
            return Err(format!(
                "Tool '{name}' download install requires an HTTPS URL and a 64-digit SHA-256."
            ));
        }
    }
    Ok(())
}

pub fn install_tool(home: &Path, name: &str) -> Result<ToolManifest, String> {
    install_tool_with(home, name, install_from_catalog)
}

fn install_tool_with(
    home: &Path,
    name: &str,
    installer: impl FnOnce(&ToolManifest) -> Result<(), String>,
) -> Result<ToolManifest, String> {
    let manifest = catalog_lookup(name)?;
    if !supports_tool_installation_platform(std::env::consts::OS, std::env::consts::ARCH) {
        return Err(
            "Guest tool installation is supported only on Linux x86_64 and aarch64.".into(),
        );
    }
    installer(&manifest)?;
    let tools_root = ensure_tools_root(home)?;
    let tool_dir = tools_root.join(&manifest.name);
    ensure_directory(&tool_dir)?;
    let manifest_path = tool_dir.join("manifest.json");
    write_manifest_atomically(&manifest_path, &manifest).map_err(|error| {
        format!(
            "The installer completed, but the tool manifest could not be registered at {}: {error}",
            manifest_path.display()
        )
    })?;
    Ok(manifest)
}

fn supports_tool_installation_platform(os: &str, arch: &str) -> bool {
    os == "linux" && matches!(arch, "x86_64" | "aarch64")
}

fn install_from_catalog(manifest: &ToolManifest) -> Result<(), String> {
    let command = install_command(manifest)?;
    let effective_uid = current_effective_uid()?;
    let mut command = with_install_privileges(command, effective_uid);
    let manager = command.get_program().to_string_lossy().into_owned();
    command.stdin(Stdio::null());
    let status = command.status().map_err(|error| {
        if error.kind() == std::io::ErrorKind::NotFound {
            if manager == "uv" {
                uv_not_found_message(&manifest.name)
            } else {
                format!(
                    "Cannot install '{}': executable '{manager}' was not found on PATH.",
                    manifest.name
                )
            }
        } else {
            format!(
                "Could not start {manager} to install '{}': {error}.",
                manifest.name,
            )
        }
    })?;
    if !status.success() {
        return Err(format!(
            "{manager} install for '{}' failed with {status}.",
            manifest.name,
        ));
    }
    Ok(())
}

fn current_effective_uid() -> Result<u32, String> {
    let status = fs::read_to_string("/proc/self/status")
        .map_err(|error| format!("Could not determine effective user ID: {error}"))?;
    let uid = status
        .lines()
        .find_map(|line| line.strip_prefix("Uid:"))
        .and_then(|line| line.split_whitespace().nth(1))
        .ok_or_else(|| {
            "Could not determine effective user ID from /proc/self/status.".to_owned()
        })?;
    uid.parse()
        .map_err(|error| format!("Could not parse effective user ID: {error}"))
}

fn with_install_privileges(command: Command, effective_uid: u32) -> Command {
    if effective_uid == 0 || command.get_program() != "apt-get" {
        return command;
    }
    let arguments = command
        .get_args()
        .map(std::ffi::OsStr::to_os_string)
        .collect::<Vec<_>>();
    let mut elevated = Command::new("sudo");
    elevated.args(["-n", "--", "apt-get"]).args(arguments);
    elevated
}

fn install_command(manifest: &ToolManifest) -> Result<Command, String> {
    let command = match &manifest.install {
        InstallSpec {
            uv: Some(packages), ..
        } => {
            let mut command = Command::new("uv");
            command
                .args(["tool", "install", "--upgrade"])
                .args(packages);
            command
        }
        InstallSpec {
            apt: Some(packages),
            ..
        } => {
            let mut command = Command::new("apt-get");
            command
                .args(["install", "--yes", "--no-install-recommends"])
                .args(packages);
            command
        }
        InstallSpec {
            npm: Some(packages),
            ..
        } => {
            let mut command = Command::new("npm");
            command.args(["install", "--global"]).args(packages);
            command
        }
        InstallSpec {
            cargo: Some(packages),
            ..
        } => {
            let mut command = Command::new("cargo");
            command.args(["install", "--locked"]).args(packages);
            command
        }
        InstallSpec {
            download: Some(_), ..
        } => {
            return Err(format!(
                "Tool '{}' uses a download install method, which is not supported by this CLI.",
                manifest.name
            ));
        }
        _ => {
            return Err(format!(
                "Tool '{}' has an invalid install method in the catalog.",
                manifest.name
            ));
        }
    };
    Ok(command)
}

fn uv_not_found_message(tool_name: &str) -> String {
    format!(
        "Cannot install '{tool_name}': executable 'uv' was not found on PATH. {UV_INSTALL_GUIDANCE}"
    )
}

fn ensure_tools_root(home: &Path) -> Result<PathBuf, String> {
    if !home.is_absolute() {
        return Err("Home directory must be an absolute path.".into());
    }
    let home = fs::canonicalize(home)
        .map_err(|error| format!("Cannot resolve home directory {}: {error}", home.display()))?;
    if !home.is_dir() {
        return Err(format!("Home path {} is not a directory.", home.display()));
    }
    let mut root = home;
    for component in [".local", "share", "avf-studio", "tools"] {
        root.push(component);
        ensure_directory(&root)?;
    }
    Ok(root)
}

fn ensure_directory(path: &Path) -> Result<(), String> {
    match fs::symlink_metadata(path) {
        Ok(metadata) if metadata.file_type().is_symlink() => Err(format!(
            "Refusing symlink in tool registry path: {}.",
            path.display()
        )),
        Ok(metadata) if metadata.is_dir() => Ok(()),
        Ok(_) => Err(format!(
            "Tool registry path component is not a directory: {}.",
            path.display()
        )),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            fs::create_dir(path).map_err(|error| {
                format!(
                    "Cannot create tool registry directory {}: {error}",
                    path.display()
                )
            })?;
            let metadata = fs::symlink_metadata(path)
                .map_err(|error| format!("Cannot inspect {}: {error}", path.display()))?;
            if metadata.file_type().is_symlink() || !metadata.is_dir() {
                return Err(format!(
                    "Refusing unsafe tool registry directory: {}.",
                    path.display()
                ));
            }
            Ok(())
        }
        Err(error) => Err(format!(
            "Cannot inspect tool registry path {}: {error}",
            path.display()
        )),
    }
}

fn write_manifest_atomically(path: &Path, manifest: &ToolManifest) -> Result<(), String> {
    validate_manifest(manifest)?;
    let parent = path
        .parent()
        .ok_or_else(|| "Manifest path has no parent directory.".to_owned())?;
    let contents = serde_json::to_vec_pretty(manifest).map_err(|e| e.to_string())?;
    let mut temporary_path = None;
    let mut temporary_file = None;
    for _ in 0..16 {
        let sequence = TEMP_FILE_SEQUENCE.fetch_add(1, Ordering::Relaxed);
        let candidate = parent.join(format!(".manifest-{}-{sequence}.tmp", std::process::id()));
        let mut options = fs::OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        match options.open(&candidate) {
            Ok(file) => {
                temporary_path = Some(candidate);
                temporary_file = Some(file);
                break;
            }
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => continue,
            Err(error) => return Err(format!("Cannot create temporary manifest: {error}")),
        }
    }
    let (temporary_path, mut temporary_file) = match (temporary_path, temporary_file) {
        (Some(path), Some(file)) => (path, file),
        _ => return Err("Could not allocate a temporary manifest name.".to_owned()),
    };
    let result = (|| {
        temporary_file
            .write_all(&contents)
            .map_err(|error| format!("Cannot write temporary manifest: {error}"))?;
        temporary_file
            .sync_all()
            .map_err(|error| format!("Cannot sync temporary manifest: {error}"))?;
        drop(temporary_file);
        fs::rename(&temporary_path, path)
            .map_err(|error| format!("Cannot publish manifest: {error}"))?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(temporary_path);
    }
    result
}

pub fn list_installed(home: &Path) -> Result<Vec<ToolManifest>, String> {
    let tools_root = ensure_tools_root(home)?;
    let catalog = load_catalog()?;
    let entries = fs::read_dir(&tools_root).map_err(|error| {
        format!(
            "Cannot read tool registry {}: {error}",
            tools_root.display()
        )
    })?;
    let mut installed = Vec::new();
    for entry in entries {
        let entry = entry.map_err(|error| format!("Cannot read tool registry entry: {error}"))?;
        let file_type = entry
            .file_type()
            .map_err(|error| format!("Cannot inspect tool registry entry: {error}"))?;
        if !file_type.is_dir() {
            continue;
        }
        let name = entry.file_name().to_string_lossy().into_owned();
        validate_tool_name(&name)?;
        let manifest_path = entry.path().join("manifest.json");
        let metadata = match fs::symlink_metadata(&manifest_path) {
            Ok(metadata) => metadata,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => continue,
            Err(error) => {
                return Err(format!(
                    "Cannot inspect tool manifest {}: {error}",
                    manifest_path.display()
                ))
            }
        };
        if metadata.file_type().is_symlink() || !metadata.is_file() {
            return Err(format!(
                "Refusing non-regular tool manifest: {}.",
                manifest_path.display()
            ));
        }
        let contents = fs::read_to_string(&manifest_path)
            .map_err(|error| format!("Cannot read {}: {error}", manifest_path.display()))?;
        let manifest: ToolManifest = serde_json::from_str(&contents).map_err(|error| {
            format!("Invalid tool manifest {}: {error}", manifest_path.display())
        })?;
        validate_manifest(&manifest)?;
        if manifest.name != name {
            return Err(format!(
                "Tool directory '{}' does not match manifest name '{}'.",
                name, manifest.name
            ));
        }
        let expected = catalog
            .tools
            .iter()
            .find(|tool| tool.name == name)
            .ok_or_else(|| format!("Installed tool '{name}' is not in the allowlisted catalog."))?;
        if &manifest != expected {
            return Err(format!(
                "Installed tool '{name}' does not match its allowlisted catalog manifest."
            ));
        }
        installed.push(manifest);
    }
    installed.sort_by(|a, b| a.name.cmp(&b.name));
    Ok(installed)
}

#[allow(dead_code)]
pub(crate) fn installed_tool_map(
    home: &Path,
) -> Result<std::collections::HashMap<String, ToolManifest>, String> {
    Ok(list_installed(home)?
        .into_iter()
        .map(|manifest| (manifest.name.clone(), manifest))
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn catalog_lookup_only_returns_allowlisted_tools() {
        assert_eq!(catalog_lookup("basedpyright").unwrap().kind, "lsp");
        assert_eq!(catalog_lookup("ruff").unwrap().command, ["ruff", "server"]);
        assert_eq!(
            catalog_lookup("vtsls").unwrap().languages,
            ["typescript", "javascript"]
        );
        assert_eq!(catalog_lookup("rust-analyzer").unwrap().languages, ["rust"]);
        assert!(catalog_lookup("unknown-tool")
            .unwrap_err()
            .contains("not in the bundled allowlisted catalog"));
        assert!(catalog_lookup("../basedpyright").is_err());
    }

    #[test]
    fn tool_installation_supports_linux_x86_64_and_aarch64() {
        assert!(supports_tool_installation_platform("linux", "x86_64"));
        assert!(supports_tool_installation_platform("linux", "aarch64"));
        assert!(!supports_tool_installation_platform("linux", "arm"));
        assert!(!supports_tool_installation_platform("android", "aarch64"));
        assert!(!supports_tool_installation_platform("windows", "x86_64"));
    }

    #[test]
    fn catalog_installer_commands_are_manager_specific_and_shell_free() {
        let basedpyright = catalog_lookup("basedpyright").unwrap();
        let basedpyright_command = install_command(&basedpyright).unwrap();
        assert_eq!(basedpyright_command.get_program(), "uv");
        assert_eq!(
            basedpyright_command
                .get_args()
                .map(|arg| arg.to_string_lossy().into_owned())
                .collect::<Vec<_>>(),
            ["tool", "install", "--upgrade", "basedpyright"]
        );

        let ruff = catalog_lookup("ruff").unwrap();
        let ruff_command = install_command(&ruff).unwrap();
        assert_eq!(ruff_command.get_program(), "uv");
        assert_eq!(
            ruff_command
                .get_args()
                .map(|arg| arg.to_string_lossy().into_owned())
                .collect::<Vec<_>>(),
            ["tool", "install", "--upgrade", "ruff"]
        );

        let vtsls = catalog_lookup("vtsls").unwrap();
        let vtsls_command = install_command(&vtsls).unwrap();
        assert_eq!(vtsls_command.get_program(), "npm");
        assert_eq!(
            vtsls_command
                .get_args()
                .map(|arg| arg.to_string_lossy().into_owned())
                .collect::<Vec<_>>(),
            ["install", "--global", "@vtsls/language-server"]
        );

        let rust_analyzer = catalog_lookup("rust-analyzer").unwrap();
        let rust_command = install_command(&rust_analyzer).unwrap();
        assert_eq!(rust_command.get_program(), "apt-get");
        assert_eq!(
            rust_command
                .get_args()
                .map(|arg| arg.to_string_lossy().into_owned())
                .collect::<Vec<_>>(),
            [
                "install",
                "--yes",
                "--no-install-recommends",
                "rust-analyzer"
            ]
        );
    }

    #[test]
    fn apt_installs_use_noninteractive_sudo_only_for_non_root_users() {
        let manifest = catalog_lookup("rust-analyzer").unwrap();
        let apt_command = install_command(&manifest).unwrap();

        let root_command = with_install_privileges(apt_command, 0);
        assert_eq!(root_command.get_program(), "apt-get");

        let apt_command = install_command(&manifest).unwrap();
        let user_command = with_install_privileges(apt_command, 1000);
        assert_eq!(user_command.get_program(), "sudo");
        assert_eq!(
            user_command
                .get_args()
                .map(|arg| arg.to_string_lossy().into_owned())
                .collect::<Vec<_>>(),
            [
                "-n",
                "--",
                "apt-get",
                "install",
                "--yes",
                "--no-install-recommends",
                "rust-analyzer"
            ]
        );
    }

    #[test]
    fn manifest_validation_rejects_client_executable_paths() {
        let mut manifest = catalog_lookup("basedpyright").unwrap();
        manifest.command[0] = "/tmp/attacker".into();
        assert!(validate_manifest(&manifest)
            .unwrap_err()
            .contains("bare executable name"));
    }

    #[test]
    fn install_behavior_is_testable_without_running_an_installer() {
        let home = tempdir().unwrap();
        let mut invoked = false;
        let installed = install_tool_with(home.path(), "basedpyright", |manifest| {
            invoked = true;
            assert_eq!(
                manifest.install.uv.as_deref(),
                Some(&["basedpyright".to_owned()][..])
            );
            Ok(())
        })
        .unwrap();
        assert!(invoked);
        let registered: ToolManifest = serde_json::from_slice(
            &fs::read(
                home.path()
                    .join(".local/share/avf-studio/tools/basedpyright/manifest.json"),
            )
            .unwrap(),
        )
        .unwrap();
        assert_eq!(installed, registered);
        assert_eq!(list_installed(home.path()).unwrap(), vec![installed]);
    }

    #[test]
    fn installer_failure_does_not_register_a_tool() {
        let home = tempdir().unwrap();
        let result = install_tool_with(home.path(), "basedpyright", |_| {
            Err("simulated installer failure".into())
        });
        assert_eq!(result.unwrap_err(), "simulated installer failure");
        assert!(!home.path().join(".local").exists());
    }

    #[test]
    fn uv_missing_error_is_actionable_and_does_not_claim_success() {
        let message = uv_not_found_message("basedpyright");
        assert_eq!(
            message,
            "Cannot install 'basedpyright': executable 'uv' was not found on PATH. \
             Install uv (https://docs.astral.sh/uv/getting-started/installation/) and rerun \
             `avf-daemon tools install basedpyright`."
        );
        assert!(!message.contains("Installed"));
    }

    #[test]
    fn refuses_symlinked_registry_directories_and_manifest_files() {
        let home = tempdir().unwrap();
        let outside = tempdir().unwrap();
        let tools_path = home.path().join(".local/share/avf-studio/tools");
        fs::create_dir_all(tools_path.parent().unwrap()).unwrap();
        #[cfg(unix)]
        std::os::unix::fs::symlink(outside.path(), &tools_path).unwrap();
        #[cfg(unix)]
        assert!(ensure_tools_root(home.path())
            .unwrap_err()
            .contains("Refusing symlink"));

        let home = tempdir().unwrap();
        let tools = ensure_tools_root(home.path()).unwrap();
        let tool = tools.join("basedpyright");
        fs::create_dir(&tool).unwrap();
        let target = outside.path().join("manifest.json");
        fs::write(&target, "{}").unwrap();
        #[cfg(unix)]
        std::os::unix::fs::symlink(target, tool.join("manifest.json")).unwrap();
        #[cfg(unix)]
        assert!(list_installed(home.path())
            .unwrap_err()
            .contains("Refusing non-regular tool manifest"));
    }

    #[test]
    fn parser_accepts_install_and_rejects_extra_arguments() {
        assert_eq!(
            parse_cli(&["install".into(), "basedpyright".into()]).unwrap(),
            ToolCommand::Install("basedpyright".into())
        );
        assert!(parse_cli(&["install".into(), "basedpyright".into(), "/bin/sh".into()]).is_err());
    }

    #[test]
    fn unsafe_tool_names_never_reach_the_installer() {
        let home = tempdir().unwrap();
        let mut invoked = false;
        let result = install_tool_with(home.path(), "../basedpyright", |_| {
            invoked = true;
            Ok(())
        });
        assert!(result.unwrap_err().contains("Invalid tool name"));
        assert!(!invoked);
    }
}
